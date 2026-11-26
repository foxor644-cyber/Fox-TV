package com.haider.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/*  EASY SETTINGS - change these values to suit you                    */
/* ------------------------------------------------------------------ */
object Config {
    const val CITY = "بغداد"          // city name shown next to the weather
    const val LAT = 33.3152           // city latitude
    const val LON = 44.3661           // city longitude
    val CYAN = Color(0xFF00E5FF)      // focus glow colour
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherApp() }
    }
}

/* ----------------------------- data ------------------------------- */

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap)

object Prefs {
    private const val KEY = "pinned"

    fun load(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences("haider_tv", Context.MODE_PRIVATE).getString(KEY, null)
        return s?.split("|")?.filter { it.isNotBlank() } ?: defaults(ctx)
    }

    fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences("haider_tv", Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("|")).apply()
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun defaults(ctx: Context): List<String> {
        val pm = ctx.packageManager
        fun first(vararg c: String): String? = c.firstOrNull { installed(pm, it) }
        return listOfNotNull(
            first("com.google.android.youtube.tv", "com.google.android.youtube"),
            first("com.netflix.ninja", "com.netflix.mediaclient"),
            first("com.android.vending")
        )
    }
}

fun loadApp(pm: PackageManager, pkg: String): AppItem? = try {
    val ai = pm.getApplicationInfo(pkg, 0)
    AppItem(
        pkg,
        pm.getApplicationLabel(ai).toString(),
        pm.getApplicationIcon(ai).toBitmap(128, 128).asImageBitmap()
    )
} catch (e: Exception) {
    null
}

fun loadAll(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val seen = HashSet<String>()
    val out = ArrayList<AppItem>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val p = ri.activityInfo.packageName
            if (p == ctx.packageName || !seen.add(p)) continue
            val icon = try {
                ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                continue
            }
            out += AppItem(p, ri.loadLabel(pm).toString(), icon)
        }
    }
    return out.sortedBy { it.label.lowercase() }
}

fun launchApp(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    if (i != null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } else {
        Toast.makeText(ctx, "Cannot open app", Toast.LENGTH_SHORT).show()
    }
}

/* ----------------------------- weather ---------------------------- */

fun describe(code: Int): String = when (code) {
    0 -> "صافي"
    1, 2 -> "غائم جزئياً"
    3 -> "غائم"
    45, 48 -> "ضباب"
    in 51..67 -> "أمطار"
    in 71..77 -> "ثلج"
    in 80..82 -> "زخات مطر"
    in 95..99 -> "عاصفة رعدية"
    else -> ""
}

suspend fun fetchWeather(): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${Config.LAT}" +
                "&longitude=${Config.LON}&current=temperature_2m,weather_code"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val cur = JSONObject(body).getJSONObject("current")
        val t = cur.getDouble("temperature_2m").roundToInt()
        val code = cur.getInt("weather_code")
        "${Config.CITY}   $t°C   ${describe(code)}"
    } catch (e: Exception) {
        null
    }
}

/* ------------------------------ UI -------------------------------- */

@Composable
fun LauncherApp() {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(Prefs.load(ctx)) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF06080F), Color.Black)))
        ) {
            // 30% black gradient overlay for readability
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f))
                        )
                    )
            )
            if (editing) {
                Picker(pinned) { newList ->
                    pinned = newList
                    Prefs.save(ctx, newList)
                    editing = false
                }
            } else {
                BackHandler { /* launcher: ignore Back */ }
                Home(pinned) { editing = true }
            }
        }
    }
}

@Composable
fun Home(pinned: List<String>, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember(pinned) { pinned.mapNotNull { loadApp(ctx.packageManager, it) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) {} }

    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
        TopBar()
        Spacer(Modifier.weight(1f))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp)
        ) {
            itemsIndexed(apps) { i, app ->
                Pill(
                    app.label,
                    app.icon,
                    if (i == 0) Modifier.focusRequester(first) else Modifier
                ) { launchApp(ctx, app.pkg) }
            }
            item {
                Pill(
                    "تعديل التطبيقات",
                    null,
                    if (apps.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    onEdit
                )
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun TopBar() {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val weather by produceState("") {
        while (true) {
            fetchWeather()?.let { value = it }
            delay(30 * 60_000L)
        }
    }
    val time = remember(now) { SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(now) }
    val date = remember(now) { SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).format(now) }
    val day = remember(now) { SimpleDateFormat("EEEE", Locale("ar")).format(now) }

    Column {
        Text(time, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text("$day  •  $date", color = Color(0xCCFFFFFF), fontSize = 26.sp)
        if (weather.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(weather, color = Config.CYAN, fontSize = 24.sp)
        }
    }
}

@Composable
fun Pill(label: String, icon: ImageBitmap?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(150), label = "scale")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                if (focused) 28.dp else 0.dp, shape,
                ambientColor = Config.CYAN, spotColor = Config.CYAN
            )
            .background(if (focused) Color(0xFF0B2A33) else Color(0x33FFFFFF), shape)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Config.CYAN else Color(0x33FFFFFF),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                icon, null,
                Modifier.size(56.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = Color.White, fontSize = 26.sp)
    }
}

@Composable
fun Picker(initial: List<String>, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val all by produceState<List<AppItem>>(emptyList()) {
        value = withContext(Dispatchers.Default) { loadAll(ctx) }
    }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    BackHandler { onDone(selected.toList()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 40.dp)) {
        Text("اختر التطبيقات الظاهرة", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
        Text("OK للإضافة أو الإزالة   •   Back للحفظ والرجوع", color = Color(0xAAFFFFFF), fontSize = 20.sp)
        Spacer(Modifier.height(24.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(all, key = { it.pkg }) { app ->
                val on = app.pkg in selected
                PickerRow(app, on) {
                    if (on) selected.remove(app.pkg) else selected.add(app.pkg)
                }
            }
        }
    }
}

@Composable
fun PickerRow(app: AppItem, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(
                if (focused) 20.dp else 0.dp, shape,
                ambientColor = Config.CYAN, spotColor = Config.CYAN
            )
            .background(if (focused) Color(0xFF0B2A33) else Color(0x22FFFFFF), shape)
            .border(if (focused) 3.dp else 0.dp, Config.CYAN, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(app.icon, null, Modifier.size(48.dp).clip(CircleShape))
        Spacer(Modifier.width(20.dp))
        Text(app.label, color = Color.White, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (on) Config.CYAN else Color.Transparent)
                .border(2.dp, if (on) Config.CYAN else Color(0x88FFFFFF), CircleShape)
        )
    }
}

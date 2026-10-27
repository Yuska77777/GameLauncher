@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.gamelauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val Light = FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
val Medium = FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))
val Soft = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 14f)

val resumeTick = mutableIntStateOf(0)
val animationSpeedScale = androidx.compose.runtime.mutableFloatStateOf(1f)

fun playNavigationSound(ctx: Context, enabled: Boolean, customUri: String? = null) {
    if (!enabled) return
    if (!customUri.isNullOrBlank()) {
        try {
            val player = android.media.MediaPlayer.create(ctx, Uri.parse(customUri))
            if (player != null) {
                player.setVolume(0.55f, 0.55f)
                player.setOnCompletionListener { it.release() }
                player.setOnErrorListener { mp, _, _ -> mp.release(); true }
                player.start()
                return
            }
        } catch (_: Exception) {
            // Fall back to the built-in sound if the selected file is unavailable.
        }
    }
    try {
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 28)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 75)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 120)
    } catch (_: Exception) {
        // Audio feedback is optional.
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val base = Typography()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8DB8FF),
                    background = Color.Black,
                    surface = Color(0xFF14171F)
                ),
                typography = base.copy(
                    bodyLarge = base.bodyLarge.copy(fontFamily = Medium),
                    bodyMedium = base.bodyMedium.copy(fontFamily = Medium),
                    labelLarge = base.labelLarge.copy(fontFamily = Medium),
                    labelMedium = base.labelMedium.copy(fontFamily = Medium),
                    titleLarge = base.titleLarge.copy(fontFamily = Light)
                )
            ) { LauncherScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue = resumeTick.intValue + 1
    }
}

fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.30f else 0.18f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))),
        shape
    )

data class AppItem(
    val label: String,
    val pkg: String,
    val icon: ImageBitmap,
    val isGame: Boolean,
    val tint: Int
)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            val bmp = it.loadIcon(pm).toBitmap(128, 128)
            val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
            AppItem(
                label = it.loadLabel(pm).toString(),
                pkg = it.activityInfo.packageName,
                icon = bmp.asImageBitmap(),
                isGame = it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                tint = px
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = ops.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        ctx.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun syncPlaytime(ctx: Context, prefs: SharedPreferences, pkgs: Set<String>) {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val last = prefs.getLong("sync", now - 7L * 24L * 3600L * 1000L)
    val events = usm.queryEvents(last, now)
    val ev = UsageEvents.Event()
    val depth = HashMap<String, Int>()
    val startAt = HashMap<String, Long>()
    val total = HashMap<String, Long>()
    while (events.hasNextEvent()) {
        events.getNextEvent(ev)
        val p: String = ev.packageName ?: ""
        if (!pkgs.contains(p)) continue
        val d: Int = depth[p] ?: 0
        if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            if (d == 0) startAt[p] = ev.timeStamp
            depth[p] = d + 1
        } else if (ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
            if (d > 0) {
                depth[p] = d - 1
                val s: Long? = startAt[p]
                if (d == 1 && s != null && ev.timeStamp > s) {
                    total[p] = (total[p] ?: 0L) + (ev.timeStamp - s)
                }
            }
        }
    }
    val ed = prefs.edit()
    for (entry in total.entries) {
        ed.putLong("ms_" + entry.key, prefs.getLong("ms_" + entry.key, 0L) + entry.value)
    }
    ed.putLong("sync", now)
    ed.apply()
}

fun fmtTime(ms: Long): String {
    val m = ms / 60000L
    val h = m / 60L
    return if (h > 0L) "$h ч ${m % 60L} мин" else "$m мин"
}

class ImageStore(private val ctx: Context) {
    var version by mutableIntStateOf(0)
        private set
    private val dir = File(ctx.filesDir, "img").apply { mkdirs() }
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    private fun file(key: String) = File(dir, "$key.img")

    fun cached(key: String): ImageBitmap? = cache.get(key)

    fun save(key: String, uri: Uri) {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file(key).outputStream().use { input.copyTo(it) }
        }
        cache.remove(key)
        version++
    }

    fun delete(key: String) {
        file(key).delete()
        cache.remove(key)
        version++
    }

    fun load(key: String, maxSize: Int, opaque: Boolean): ImageBitmap? {
        val hit = cache.get(key)
        if (hit != null) return hit
        val f = file(key)
        if (!f.exists()) return null
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxSize || o.outHeight / (s * 2) >= maxSize) s *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = s
        if (opaque) opts.inPreferredConfig = Bitmap.Config.RGB_565
        val bmp = BitmapFactory.decodeFile(f.path, opts) ?: return null
        val img = bmp.asImageBitmap()
        cache.put(key, img)
        return img
    }
}

@Composable
fun rememberImage(store: ImageStore, key: String, maxSize: Int, opaque: Boolean = false): ImageBitmap? {
    val v = store.version
    var img by remember(key, v) { mutableStateOf(store.cached(key)) }
    LaunchedEffect(key, v) {
        if (img == null) img = withContext(Dispatchers.IO) { store.load(key, maxSize, opaque) }
    }
    return img
}

@Composable
fun FadeImage(img: ImageBitmap?, desc: String?, modifier: Modifier, scale: ContentScale) {
    val a by animateFloatAsState(if (img != null) 1f else 0f, tween(600), label = "fade")
    if (img != null) {
        Image(img, desc, modifier.graphicsLayer { alpha = a }, contentScale = scale)
    }
}

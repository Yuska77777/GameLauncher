@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.gamelauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Process
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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.blur
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

var resumeTick by mutableIntStateOf(0)

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
        resumeTick++
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
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

fun syncPlaytime(ctx: Context, prefs: android.content.SharedPreferences, pkgs: Set<String>) {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val last = prefs.getLong("sync", now - 7L * 24 * 3600 * 1000)
    val events = usm.queryEvents(last, now)
    val e = UsageEvents.Event()
    val depth = HashMap<String, Int>()
    val start = HashMap<String, Long>()
    val add = HashMap<String, Long>()
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        val p = e.packageName
        if (p !in pkgs) continue
        if (e.eventType == 1) {
            val d = depth[p] ?: 0
            if (d == 0) start[p] = e.timeStamp
            depth[p] = d + 1
        } else if (e.eventType == 2) {
            val d = depth[p] ?: 0
            if (d > 0) {
                depth[p] = d - 1
                if (d - 1 == 0) {
                    val s = start[p] ?: continue
                    if (e.timeStamp > s) add[p] = (add[p] ?: 0L) + (e.timeStamp - s)
                }
            }
        }
    }
    val ed = prefs.edit()
    for ((p, v) in add) ed.putLong("ms_$p", prefs.getLong("ms_$p", 0L) + v)
    ed.putLong("sync", now).apply()
}

fun fmtTime(ms: Long): String {
    val m = ms / 60000
    val h = m / 60
    return if (h > 0) "$h ч ${m % 60} мин" else "$m мин"
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
        cache.get(key)?.let { return it }
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
        val img = BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
        if (img != null) cache.put(key, img)
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

@Composable
fun GamePage(
    app: AppItem,
    store: ImageStore,
    playMs: Long,
    hasUsage: Boolean,
    active: Boolean,
    offset: () -> Float,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onUsage: () -> Unit
) {
    val bg = rememberImage(store, "bg_${app.pkg}", 1600, true)
    val logo = rememberImage(store, "logo_${app.pkg}", 800)
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                zoom.animateTo(1.07f, tween(16000, easing = LinearEasing))
                zoom.animateTo(1f, tween(16000, easing = LinearEasing))
            }
        } else {
            zoom.snapTo(1f)
        }
    }
    val base = lerp(Color(app.tint), Color.Black, 0.6f)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, Color(0xFF05070F)))))
            if (bg == null) {
                Image(
                    app.icon, null,
                    Modifier.align(Alignment.Center).size(132.dp)
                        .clip(RoundedCornerShape(30.dp)).graphicsLayer { alpha = 0.9f }
                )
            }
            FadeImage(bg, null, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x80000000),
                    0.3f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color(0xD9000000)
                )
            )
        )

        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset() * size.width * 0.25f }) {
            Column(
                Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(start = 28.dp, bottom = 84.dp, end = 160.dp)
            ) {
                if (logo != null) {
                    FadeImage(
                        logo, app.label,
                        Modifier.widthIn(max = 300.dp).heightIn(max = 140.dp),
                        ContentScale.Fit
                    )
                } else {
                    Text(
                        app.label, color = Color.White, maxLines = 2,
                        style = TextStyle(fontFamily = Light, fontSize = 38.sp, letterSpacing = 0.5.sp, shadow = Soft)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.glass(CircleShape, true).clickable { onPlay() }
                            .padding(horizontal = 34.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Играть", color = Color.White, fontFamily = Medium, fontSize = 16.sp, letterSpacing = 0.4.sp)
                    }
                    Box(
                        Modifier.size(48.dp).glass(CircleShape).clickable { onEdit() },
                        contentAlignment = Alignment.Center
                    ) { Text("✎", color = Color.White, fontSize = 18.sp) }
                }
            }

            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 90.dp)
                    .glass(RoundedCornerShape(22.dp))
                    .clickable(enabled = !hasUsage) { onUsage() }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (hasUsage) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "ВРЕМЯ В ИГРЕ", color = Color.White.copy(alpha = 0.7f),
                            fontFamily = Medium, fontSize = 10.sp, letterSpacing = 1.2.sp
                        )
                        Text(
                            fmtTime(playMs), color = Color.White,
                            style = TextStyle(fontFamily = Light, fontSize = 22.sp)
                        )
                    }
                } else {
                    Text(
                        "Включить время в игре", color = Color.White,
                        fontFamily = Medium, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    apps: List<AppItem>,
    carousel: Set<String>,
    store: ImageStore,
    onLaunch: (AppItem) -> Unit,
    onEdit: (AppItem) -> Unit,
    onPickBg: () -> Unit,
    onResetBg: () -> Unit
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val bg = rememberImage(store, "libbg", 1600, true)
    val list = if (showAll) apps else apps.filter { it.pkg in carousel }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF05070F)))))
        FadeImage(bg, null, Modifier.fillMaxSize().blur(18.dp), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0xA6000000)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 18.dp).padding(top = 120.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Библиотека", color = Color.White, modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Light, fontSize = 32.sp, letterSpacing = 0.5.sp)
                )
                TextButton(onClick = onPickBg) { Text("Фон") }
                TextButton(onClick = onResetBg) { Text("Сброс") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Игры") })
                FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("Все приложения") })
            }
            Text(
                "Зажми иконку, чтобы изменить картинки или добавить в карусель",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Medium,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(92.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                items(list, key = { it.pkg }, contentType = { 0 }) { app ->
                    val custom = rememberImage(store, "icon_${app.pkg}", 256)
                    val src = remember { MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    val sc by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "press")
                    Column(
                        Modifier
                            .graphicsLayer { scaleX = sc; scaleY = sc }
                            .glass(RoundedCornerShape(22.dp))
                            .combinedClickable(
                                interactionSource = src,
                                indication = null,
                                onClick = { onLaunch(app) },
                                onLongClick = { onEdit(app) }
                            )
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            custom ?: app.icon, app.label,
                            Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            app.label, color = Color.White, fontSize = 12.sp, fontFamily = Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TileRow(
    games: List<AppItem>,
    current: Int,
    store: ImageStore,
    onSelect: (Int) -> Unit,
    onEdit: (AppItem) -> Unit,
    modifier: Modifier
) {
    val state = rememberLazyListState()
    LaunchedEffect(current) { state.animateScrollToItem(max(0, current - 2)) }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().height(96.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(count = games.size + 1, key = { if (it < games.size) games[it].pkg else "library" }) { i ->
            val selected = i == current
            val size by animateDpAsState(if (selected) 80.dp else 62.dp, tween(250), label = "tile")
            val shape = RoundedCornerShape(22.dp)
            if (i < games.size) {
                val app = games[i]
                val custom = rememberImage(store, "icon_${app.pkg}", 256)
                Box(
                    Modifier.size(size)
                        .clip(shape)
                        .border(if (selected) 2.dp else 1.dp, Color.White.copy(alpha = if (selected) 0.95f else 0.3f), shape)
                        .combinedClickable(onClick = { onSelect(i) }, onLongClick = { onEdit(app) })
                ) {
                    Image(
                        custom ?: app.icon, app.label,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                

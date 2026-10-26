package com.example.gamelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

val Light = FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
val Medium = FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))
val Soft = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 14f)

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
}

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

    fun load(key: String, max: Int, opaque: Boolean): ImageBitmap? {
        cache.get(key)?.let { return it }
        val f = file(key)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = s
        if (opaque) opts.inPreferredConfig = Bitmap.Config.RGB_565
        val img = BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
        if (img != null) cache.put(key, img)
        return img
    }
}

@Composable
fun rememberImage(store: ImageStore, key: String, max: Int, opaque: Boolean = false): ImageBitmap? {
    val v = store.version
    var img by remember(key, v) { mutableStateOf(store.cached(key)) }
    LaunchedEffect(key, v) {
        if (img == null) img = withContext(Dispatchers.IO) { store.load(key, max, opaque) }
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
fun PageDots(count: Int, current: Int, modifier: Modifier) {
    if (count > 10) {
        Text(
            "${current + 1} / $count", color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp, fontFamily = Medium, modifier = modifier
        )
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                val w by animateDpAsState(if (i == current) 22.dp else 6.dp, label = "dot")
                Box(
                    Modifier.width(w).height(6.dp).clip(CircleShape)
                        .background(if (i == current) Color.White else Color.White.copy(alpha = 0.35f))
                )
            }
        }
    }
}

@Composable
fun Clock(modifier: Modifier) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var t by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            t = fmt.format(Date())
            delay(15000)
        }
    }
    Text(t, color = Color.White, style = TextStyle(fontFamily = Light, fontSize = 20.sp, shadow = Soft), modifier = modifier)
}

@Composable
fun GamePage(
    app: AppItem,
    store: ImageStore,
    launches: Int,
    active: Boolean,
    offset: () -> Float,
    onPlay: () -> Unit,
    onEdit: () -> Unit
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
                    0f to Color(0x66000000),
                    0.25f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color(0xD9000000)
                )
            )
        )

        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset() * size.width * 0.25f }) {
            Clock(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 24.dp, top = 22.dp))
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 20.dp, top = 14.dp)
                    .size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center
            ) { Text("✎", color = Color.White, fontSize = 18.sp) }

            Column(
                Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(start = 28.dp, bottom = 96.dp, end = 130.dp)
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
                Button(
                    onClick = onPlay,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Text("Играть", fontFamily = Medium, fontSize = 16.sp, letterSpacing = 0.4.sp)
                }
            }

            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .padding(end = 28.dp, bottom = 100.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    "$launches", color = Color.White,
                    style = TextStyle(fontFamily = Light, fontSize = 34.sp, shadow = Soft)
                )
                Text(
                    "запусков", color = Color.White.copy(alpha = 0.7f),
                    fontFamily = Medium, fontSize = 12.sp, letterSpacing = 1.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
        FadeImage(bg, null, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0xA6000000)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 18.dp).padding(top = 34.dp)
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
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.07f))
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
                            Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    val store = remember { ImageStore(ctx) }
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var carousel by remember { mutableStateOf(emptySet<String>()) }
    var rank by remember { mutableStateOf(emptyMap<String, Int>()) }
    val launches = remember { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<AppItem?>(null) }
    var pickKey by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val k = pickKey
        if (uri != null && k != null) store.save(k, uri)
        pickKey = null
    }
    fun pick(key: String) {
        pickKey = key
        picker.launch("image/*")
    }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.Default) { loadApps(ctx) }
        apps = list
        if (!prefs.contains("carousel")) {
            prefs.edit().putStringSet("carousel", list.filter { it.isGame }.map { it.pkg }.toSet()).apply()
        }
        carousel = prefs.getStringSet("carousel", emptySet())!!.toSet()
        list.forEach { launches[it.pkg] = prefs.getInt("n_${it.pkg}", 0) }
        rank = list.sortedByDescending { prefs.getLong("t_${it.pkg}", 0L) }
            .mapIndexed { i, a -> a.pkg to i }.toMap()
    }

    fun play(app: AppItem) {
        ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
            val n = (launches[app.pkg] ?: 0) + 1
            launches[app.pkg] = n
            prefs.edit().putInt("n_${app.pkg}", n).putLong("t_${app.pkg}", System.currentTimeMillis()).apply()
            ctx.startActivity(it)
        }
    }

    val games = remember(apps, carousel, rank) {
        apps.filter { it.pkg in carousel }
            .sortedWith(compareBy<AppItem> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }
    val pager = rememberPagerState { games.size + 1 }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                pagerSnapDistance = PagerSnapDistance.atMost(3)
            ),
            key = { if (it < games.size) games[it].pkg else "library" }
        ) { page ->
            val off = { (page - pager.currentPage) - pager.currentPageOffsetFraction }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val d = min(abs(off()), 1f)
                    alpha = 1f - d * 0.7f
                    val s = 1f - d * 0.06f
                    scaleX = s
                    scaleY = s
                }
            ) {
                if (page < games.size) {
                    val app = games[page]
                    GamePage(
                        app, store, launches[app.pkg] ?: 0,
                        pager.currentPage == page, off,
                        { play(app) }, { editing = app }
                    )
                } else {
                    LibraryScreen(
                        apps, carousel, store,
                        { play(it) }, { editing = it },
                        { pick("libbg") }, { store.delete("libbg") }
                    )
                }
            }
        }
        PageDots(
            pager.pageCount, pager.currentPage,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 30.dp)
        )
    }

    editing?.let { app ->
        val inCarousel = app.pkg in carousel
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(app.label, fontFamily = Light) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pick("bg_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Фон игры") }
                    OutlinedButton({ pick("logo_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton({ pick("icon_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Иконка в библиотеке") }
                    OutlinedButton(
                        { listOf("bg", "logo", "icon").forEach { store.delete("${it}_${app.pkg}") } },
                        Modifier.fillMaxWidth()
                    ) { Text("Сбросить картинки") }
                    OutlinedButton(
                        {
                            carousel = if (inCarousel) carousel - app.pkg else carousel + app.pkg
                            prefs.edit().putStringSet("carousel", carousel).apply()
                            editing = null
                        },
                        Modifier.fillMaxWidth()
                    ) { Text(if (inCarousel) "Убрать из карусели" else "Добавить в карусель") }
                }
            },
            confirmButton = { TextButton({ editing = null }) { Text("Готово") } }
        )
    }
}

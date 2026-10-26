package com.example.gamelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { LauncherScreen() } }
    }
}

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap, val isGame: Boolean)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            AppItem(
                label = it.loadLabel(pm).toString(),
                pkg = it.activityInfo.packageName,
                icon = it.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
                isGame = it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

class ImageStore(private val ctx: Context) {
    var version by mutableIntStateOf(0)
        private set
    private val dir = File(ctx.filesDir, "img").apply { mkdirs() }
    private fun file(key: String) = File(dir, "$key.img")

    fun save(key: String, uri: Uri) {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file(key).outputStream().use { input.copyTo(it) }
        }
        version++
    }

    fun delete(key: String) {
        file(key).delete()
        version++
    }

    fun load(key: String, max: Int): ImageBitmap? {
        val f = file(key)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
    }
}

@Composable
fun rememberImage(store: ImageStore, key: String, max: Int): ImageBitmap? {
    val v = store.version
    var img by remember(key, v) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key, v) { img = withContext(Dispatchers.IO) { store.load(key, max) } }
    return img
}

@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier) {
    if (count > 10) {
        Text("${current + 1} / $count", color = Color.White, fontSize = 12.sp, modifier = modifier)
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .size(if (i == current) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (i == current) Color.White else Color.White.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
fun GamePage(app: AppItem, store: ImageStore, launches: Int, onPlay: () -> Unit, onEdit: () -> Unit) {
    val bg = rememberImage(store, "bg_${app.pkg}", 1600)
    val logo = rememberImage(store, "logo_${app.pkg}", 800)
    Box(Modifier.fillMaxSize()) {
        if (bg != null) {
            Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1E3A8A), Color(0xFF05070F))))
            )
            Image(
                app.icon, null,
                Modifier.align(Alignment.Center).size(128.dp).clip(RoundedCornerShape(28.dp))
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    1f to Color(0xCC000000)
                )
            )
        )
        TextButton(
            onClick = onEdit,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 24.dp, end = 8.dp)
        ) { Text("✎ Изменить", color = Color.White) }

        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 28.dp, bottom = 72.dp, end = 120.dp)
        ) {
            if (logo != null) {
                Image(
                    logo, app.label,
                    Modifier.widthIn(max = 300.dp).heightIn(max = 140.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(app.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPlay) { Text("▶  Играть") }
        }
        Text(
            "Запусков: $launches",
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(end = 28.dp, bottom = 84.dp)
        )
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
    val bg = rememberImage(store, "libbg", 1600)
    val list = if (showAll) apps else apps.filter { it.pkg in carousel }
    Box(Modifier.fillMaxSize()) {
        if (bg != null) {
            Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF05070F))))
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 16.dp).padding(top = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Библиотека", color = Color.White, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickBg) { Text("Фон") }
                TextButton(onClick = onResetBg) { Text("Сброс") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Игры") })
                FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("Все приложения") })
            }
            Text(
                "Зажми иконку, чтобы изменить картинки или добавить в карусель",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                items(list, key = { it.pkg }) { app ->
                    val custom = rememberImage(store, "icon_${app.pkg}", 256)
                    Column(
                        Modifier.combinedClickable(
                            onClick = { onLaunch(app) },
                            onLongClick = { onEdit(app) }
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            custom ?: app.icon, app.label,
                            Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            app.label, color = Color.White, fontSize = 12.sp,
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

    val games = apps.filter { it.pkg in carousel }
        .sortedWith(compareBy<AppItem> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    val pager = rememberPagerState { games.size + 1 }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { if (it < games.size) games[it].pkg else "library" }
        ) { page ->
            if (page < games.size) {
                val app = games[page]
                GamePage(app, store, launches[app.pkg] ?: 0, { play(app) }, { editing = app })
            } else {
                LibraryScreen(
                    apps, carousel, store,
                    { play(it) }, { editing = it },
                    { pick("libbg") }, { store.delete("libbg") }
                )
            }
        }
        PageDots(
            pager.pageCount, pager.currentPage,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        )
    }

    editing?.let { app ->
        val inCarousel = app.pkg in carousel
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pick("bg_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Фон игры") }
                    OutlinedButton({ pick("logo_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton({ pick("icon_${app.pkg}") }, Modifier.fillMaxWidth()) { Text("Иконка в библиотеке") }
                    OutlinedButton(
                        {
                            listOf("bg", "logo", "icon").forEach { store.delete("${it}_${app.pkg}") }
                        },
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

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
        FadeImage(bg, null, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
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
                items(list, key = { it.pkg }) { app ->
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
        items(count = games.size + 1) { i ->
            val selected = i == current
            val tileSize by animateDpAsState(if (selected) 80.dp else 62.dp, tween(250), label = "tile")
            val shape = RoundedCornerShape(22.dp)
            if (i < games.size) {
                val app = games[i]
                val custom = rememberImage(store, "icon_${app.pkg}", 256)
                Box(
                    Modifier.size(tileSize)
                        .clip(shape)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            Color.White.copy(alpha = if (selected) 0.95f else 0.3f),
                            shape
                        )
                        .combinedClickable(onClick = { onSelect(i) }, onLongClick = { onEdit(app) })
                ) {
                    Image(
                        custom ?: app.icon, app.label,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                }
            } else {
                Box(
                    Modifier.size(tileSize).glass(shape, selected).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { Text("▦", color = Color.White, fontSize = 22.sp) }
            }
        }
    }
}

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    val store = remember { ImageStore(ctx) }
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var carousel by remember { mutableStateOf(emptySet<String>()) }
    var rank by remember { mutableStateOf(emptyMap<String, Int>()) }
    val playtime = remember { mutableStateMapOf<String, Long>() }
    var hasUsage by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppItem?>(null) }
    var pickKey by rememberSaveable { mutableStateOf<String?>(null) }
    val tick = resumeTick.intValue

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val k = pickKey
        if (uri != null && k != null) store.save(k, uri)
        pickKey = null
    }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.Default) { loadApps(ctx) }
        apps = list
        if (!prefs.contains("carousel")) {
            prefs.edit().putStringSet("carousel", list.filter { it.isGame }.map { it.pkg }.toSet()).apply()
        }
        carousel = (prefs.getStringSet("carousel", emptySet()) ?: emptySet()).toSet()
        rank = list.sortedByDescending { prefs.getLong("t_${it.pkg}", 0L) }
            .mapIndexed { i, a -> a.pkg to i }.toMap()
    }

    LaunchedEffect(apps, tick) {
        if (apps.isNotEmpty()) {
            val allowed = hasUsageAccess(ctx)
            hasUsage = allowed
            if (allowed) {
                withContext(Dispatchers.IO) { syncPlaytime(ctx, prefs, apps.map { it.pkg }.toSet()) }
            }
            for (a in apps) playtime[a.pkg] = prefs.getLong("ms_${a.pkg}", 0L)
        }
    }

    val games = remember(apps, carousel, rank) {
        apps.filter { it.pkg in carousel }
            .sortedWith(compareBy<AppItem> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }
    val pager = rememberPagerState(pageCount = { games.size + 1 })

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
                        app = app,
                        store = store,
                        playMs = playtime[app.pkg] ?: 0L,
                        hasUsage = hasUsage,
                        active = pager.currentPage == page && !pager.isScrollInProgress,
                        offset = off,
                        onPlay = {
                            ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                                prefs.edit().putLong("t_${app.pkg}", System.currentTimeMillis()).apply()
                                ctx.startActivity(it)
                            }
                        },
                        onEdit = { editing = app },
                        onUsage = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                } else {
                    LibraryScreen(
                        apps = apps,
                        carousel = carousel,
                        store = store,
                        onLaunch = { a ->
                            ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let {
                                prefs.edit().putLong("t_${a.pkg}", System.currentTimeMillis()).apply()
                                ctx.startActivity(it)
                            }
                        },
                        onEdit = { a -> editing = a },
                        onPickBg = {
                            pickKey = "libbg"
                            picker.launch("image/*")
                        },
                        onResetBg = { store.delete("libbg") }
                    )
                }
            }
        }
        TileRow(
            games = games,
            current = pager.currentPage,
            store = store,
            onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } },
            onEdit = { a -> editing = a },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        )
    }

    val target = editing
    if (target != null) {
        val inCarousel = target.pkg in carousel
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = Color(0xE61B1F2A),
            title = { Text(target.label, fontFamily = Light) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickKey = "bg_${target.pkg}"; picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Фон игры") }
                    OutlinedButton(
                        onClick = { pickKey = "logo_${target.pkg}"; picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Картинка-название") }
                    OutlinedButton(
                        onClick = { pickKey = "icon_${target.pkg}"; picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Картинка плитки") }
                    OutlinedButton(
                        onClick = {
                            store.delete("bg_${target.pkg}")
                            store.delete("logo_${target.pkg}")
                            store.delete("icon_${target.pkg}")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сбросить картинки") }
                    OutlinedButton(
                        onClick = {
                            carousel = if (inCarousel) carousel - target.pkg else carousel + target.pkg
                            prefs.edit().putStringSet("carousel", carousel).apply()
                            editing = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (inCarousel) "Убрать из карусели" else "Добавить в карусель") }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Готово") } }
        )
    }
}

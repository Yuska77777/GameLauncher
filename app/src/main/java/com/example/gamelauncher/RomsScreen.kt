@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.gamelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale
import java.util.UUID

private val romPlatforms = listOf(
    "PlayStation 2", "PlayStation", "PSP", "PS Vita", "Nintendo Switch",
    "Nintendo 64", "Game Boy / GBA", "SNES", "NES", "Sega", "Dreamcast", "Other"
)

private data class RomEntry(val id: String, val title: String, val platform: String, val uri: String)
private data class RomFolder(val key: String, val title: String)
private data class OpenApp(val packageName: String, val activityName: String, val label: String, val icon: Drawable)

private fun romTitleFromUri(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
    val fallback = "Новая игра"
    return (name ?: fallback).substringBeforeLast('.', name ?: fallback)
}

private fun folderImageKey(kind: String, folder: RomFolder) = "rom_folder_${kind}_${folder.key}"
private fun romImageKey(kind: String, id: String) = "rom_${kind}_${id}"

private fun fileIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    data = uri
    type = context.contentResolver.getType(uri) ?: "*/*"
    addCategory(Intent.CATEGORY_DEFAULT)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun launchableApps(context: Context): List<OpenApp> {
    val pm = context.packageManager
    return try {
        pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .mapNotNull { info: ApplicationInfo ->
                val launch = pm.getLaunchIntentForPackage(info.packageName) ?: return@mapNotNull null
                val component = launch.component ?: return@mapNotNull null
                OpenApp(
                    packageName = info.packageName,
                    activityName = component.className,
                    label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { info.packageName },
                    icon = info.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    } catch (_: Exception) { emptyList() }
}

private fun compatibleApps(context: Context, uri: Uri): List<OpenApp> {
    val pm = context.packageManager
    return try {
        pm.queryIntentActivities(fileIntent(context, uri), PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                OpenApp(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { activity.packageName },
                    icon = info.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    } catch (_: Exception) { emptyList() }
}

private fun startRomWithApp(context: Context, uri: Uri, app: OpenApp): Boolean {
    val component = ComponentName(app.packageName, app.activityName)
    return try {
        context.startActivity(fileIntent(context, uri).setComponent(component))
        true
    } catch (_: Exception) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) {
                launch.data = uri
                launch.type = context.contentResolver.getType(uri) ?: "*/*"
                launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(launch)
                true
            } else false
        } catch (_: Exception) { false }
    }
}

private fun saveHandler(prefs: SharedPreferences, romId: String, app: OpenApp) {
    prefs.edit().putString(
        "rom_handler_$romId",
        ComponentName(app.packageName, app.activityName).flattenToString()
    ).apply()
}

private fun savedHandler(context: Context, prefs: SharedPreferences, rom: RomEntry): OpenApp? {
    val saved = prefs.getString("rom_handler_${rom.id}", null) ?: return null
    val component = ComponentName.unflattenFromString(saved) ?: return null
    return try {
        val info = context.packageManager.getActivityInfo(component, 0)
        OpenApp(
            component.packageName,
            component.className,
            info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { component.packageName },
            info.loadIcon(context.packageManager)
        )
    } catch (_: Exception) { null }
}

@Composable
private fun DrawableIcon(drawable: Drawable, modifier: Modifier) {
    val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
    Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun RomsScreen(store: ImageStore, prefs: SharedPreferences, apps: List<AppItem>) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val rootPagerSidePadding = ((configuration.screenWidthDp.dp - 220.dp) / 2).coerceAtLeast(0.dp)
    val gamePagerSidePadding = ((configuration.screenWidthDp.dp - 210.dp) / 2).coerceAtLeast(0.dp)
    var refresh by remember { mutableIntStateOf(0) }
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingRomId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooserRomId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlatform by rememberSaveable { mutableStateOf(romPlatforms.first()) }
    var pendingImageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var chooserFilter by rememberSaveable { mutableStateOf("") }
    var rootPage by rememberSaveable { mutableIntStateOf(0) }

    val folders = remember { romPlatforms.map { RomFolder(it, it) } }
    val ids = remember(refresh) { (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()).toList() }
    val roms = remember(ids, refresh) {
        ids.mapNotNull { id ->
            val uri = prefs.getString("rom_uri_$id", null) ?: return@mapNotNull null
            RomEntry(
                id,
                prefs.getString("rom_title_$id", null) ?: romTitleFromUri(context, Uri.parse(uri)),
                prefs.getString("rom_platform_$id", "Other") ?: "Other",
                uri
            )
        }.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    val rootPager = rememberPagerState(initialPage = rootPage.coerceIn(0, folders.lastIndex)) { folders.size }
    LaunchedEffect(rootPager.currentPage) { rootPage = rootPager.currentPage }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            val id = UUID.randomUUID().toString()
            val title = romTitleFromUri(context, uri)
            prefs.edit()
                .putStringSet("rom_ids", (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()) + id)
                .putString("rom_uri_$id", uri.toString())
                .putString("rom_title_$id", title)
                .putString("rom_platform_$id", pendingPlatform)
                .apply()
            refresh++
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val key = pendingImageKey
        if (uri != null && !key.isNullOrBlank()) store.save(key, uri)
        pendingImageKey = null
        refresh++
    }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            when {
                editingFolderKey != null -> prefs.edit().putString("music_rom_folder_${editingFolderKey}", uri.toString()).apply()
                editingRomId != null -> prefs.edit().putString("music_rom_${editingRomId}", uri.toString()).apply()
            }
            refresh++
        }
    }

    val currentFolder = folders.firstOrNull { it.key == folderKey }
    val currentRoms = roms.filter { it.platform == folderKey }

    val rootBackground = rememberImage(store, "rom_root_bg", 1600, true)
    val carouselBackground = rememberImage(store, folderImageKey("bg", folders[rootPager.currentPage]), 1600, true)
    val currentBackground = currentFolder?.let { rememberImage(store, folderImageKey("bg", it), 1600, true) }
    val gamePager = rememberPagerState(initialPage = 0) { currentRoms.size }
    LaunchedEffect(folderKey, currentRoms.size) {
        if (currentRoms.isNotEmpty()) gamePager.scrollToPage(0)
    }
    val activeRom = currentRoms.getOrNull(gamePager.currentPage)
    val activeRomBackground = activeRom?.let { rememberImage(store, romImageKey("cover", it.id), 1200, true) }
    val activeRomMusic = activeRom?.let { prefs.getString("music_rom_${it.id}", null) }

    val rootMusicUri = if (currentFolder == null) {
        prefs.getString("music_rom_folder_${folders[rootPager.currentPage].key}", null)
    } else null

    DisposableEffect(rootMusicUri) {
        var player: android.media.MediaPlayer? = null
        if (!rootMusicUri.isNullOrBlank()) {
            try {
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    setDataSource(context, Uri.parse(rootMusicUri))
                    isLooping = true
                    setVolume(0.22f, 0.22f)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> true }
                    prepareAsync()
                }
            } catch (_: Exception) { try { player?.release() } catch (_: Exception) { } }
        }
        onDispose {
            try { player?.stop() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
        }
    }

    DisposableEffect(activeRomMusic, folderKey) {
        var player: android.media.MediaPlayer? = null
        if (currentFolder != null && !activeRomMusic.isNullOrBlank()) {
            try {
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    setDataSource(context, Uri.parse(activeRomMusic))
                    isLooping = true
                    setVolume(0.22f, 0.22f)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> true }
                    prepareAsync()
                }
            } catch (_: Exception) { try { player?.release() } catch (_: Exception) { } }
        }
        onDispose {
            try { player?.stop() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
        }
    }

    val screenBackground = when {
        currentFolder == null -> carouselBackground ?: rootBackground
        activeRomBackground != null -> activeRomBackground
        else -> currentBackground
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10264D), Color(0xFF05070F))))) {
        FadeImage(screenBackground, null, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0x8A000000)))

        if (currentFolder == null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 30.dp, end = 30.dp, top = 66.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ROM-библиотека", color = Color.White, fontSize = 34.sp, fontFamily = Light)
                        Text("Перелистывай системы вправо и влево", color = Color.White.copy(alpha = .68f), fontSize = 12.sp)
                    }
                    Button(onClick = { pendingPlatform = folders[rootPager.currentPage].key; picker.launch(arrayOf("*/*")) }) { Text("+ Добавить ROM") }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalPager(
                    state = rootPager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(220.dp),
                    contentPadding = PaddingValues(horizontal = rootPagerSidePadding),
                    pageSpacing = 18.dp
                ) { page ->
                    val folder = folders[page]
                    val title = prefs.getString("rom_folder_title_${folder.key}", folder.title) ?: folder.title
                    val icon = rememberImage(store, folderImageKey("icon", folder), 700, true)
                    val logo = rememberImage(store, folderImageKey("logo", folder), 1000)
                    val count = roms.count { it.platform == folder.key }
                    val shape = RoundedCornerShape(28.dp)
                    Column(
                        Modifier.fillMaxSize().combinedClickable(
                            onClick = { folderKey = folder.key },
                            onLongClick = { editingFolderKey = folder.key }
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(300.dp).clip(shape).glass(shape)) {
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF263F6C), Color(0xFF111827)))))
                            if (icon != null) Image(icon, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))
                            Column(Modifier.align(Alignment.BottomCenter).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (logo != null) Image(logo, title, Modifier.fillMaxWidth().height(58.dp), contentScale = ContentScale.Fit)
                                else Text(title, color = Color.White, fontSize = 27.sp, fontFamily = Medium)
                                Spacer(Modifier.height(5.dp))
                                Text("$count игр · удержание — настройки", color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            folders.indices.forEach { i ->
                                Box(Modifier.padding(horizontal = 3.dp).size(if (i == rootPager.currentPage) 9.dp else 6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (i == rootPager.currentPage) 1f else .35f)))
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 58.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(prefs.getString("rom_folder_title_${currentFolder.key}", currentFolder.title) ?: currentFolder.title, color = Color.White, fontSize = 32.sp, fontFamily = Light)
                        Text("${currentRoms.size} игр · свайп вправо/влево · музыка выбранной игры", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { folderKey = null }) { Text("‹ Системы") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { pendingPlatform = currentFolder.key; picker.launch(arrayOf("*/*")) }) { Text("+ Добавить") }
                }
                Spacer(Modifier.height(12.dp))
                if (currentRoms.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Папка пока пустая", color = Color.White, fontSize = 25.sp, fontFamily = Light)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { pendingPlatform = currentFolder.key; picker.launch(arrayOf("*/*")) }) { Text("Выбрать файл ROM") }
                        }
                    }
                } else {
                    HorizontalPager(
                        state = gamePager,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        pageSize = androidx.compose.foundation.pager.PageSize.Fixed(210.dp),
                        contentPadding = PaddingValues(horizontal = gamePagerSidePadding),
                        pageSpacing = 18.dp
                    ) { page ->
                        val rom = currentRoms[page]
                        val cover = rememberImage(store, romImageKey("cover", rom.id), 900, true)
                        val icon = rememberImage(store, romImageKey("icon", rom.id), 300)
                        val logo = rememberImage(store, romImageKey("logo", rom.id), 900)
                        val handler = savedHandler(context, prefs, rom)
                        val shape = RoundedCornerShape(26.dp)
                        Column(
                            Modifier.fillMaxSize().combinedClickable(
                                onClick = {
                                    if (handler != null && startRomWithApp(context, Uri.parse(rom.uri), handler)) {
                                    } else {
                                        chooserRomId = rom.id
                                        chooserFilter = ""
                                    }
                                },
                                onLongClick = { editingRomId = rom.id }
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(290.dp).clip(shape).glass(shape)) {
                                if (cover != null) Image(cover, rom.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else if (icon != null) Image(icon, rom.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF31486E), Color(0xFF121722)))))
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000)))))
                                Column(Modifier.align(Alignment.BottomCenter).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (logo != null) Image(logo, rom.title, Modifier.fillMaxWidth().height(50.dp), contentScale = ContentScale.Fit)
                                    else Text(rom.title, color = Color.White, fontSize = 26.sp, fontFamily = Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(5.dp))
                                    Text(if (handler != null) "Открывать через: ${handler.label}" else "Нажми для выбора приложения", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                currentRoms.indices.forEach { i ->
                                    Box(Modifier.padding(horizontal = 3.dp).size(if (i == gamePager.currentPage) 9.dp else 6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (i == gamePager.currentPage) 1f else .35f)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val folderEdit = editingFolderKey?.let { key -> folders.firstOrNull { it.key == key } }
    if (folderEdit != null) {
        var title by remember(folderEdit.key) { mutableStateOf(prefs.getString("rom_folder_title_${folderEdit.key}", folderEdit.title) ?: folderEdit.title) }
        AlertDialog(
            onDismissRequest = { editingFolderKey = null },
            containerColor = Color(0xE61B1F2A),
            title = { Text("Настройки: ${folderEdit.title}", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("bg", folderEdit); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Фон системы") }
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("icon", folderEdit); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка плитки") }
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("logo", folderEdit); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Музыка системы") }
                    Text("Эта музыка играет только на экране перелистывания систем. Внутри папки она автоматически выключается.", color = Color.White.copy(alpha = .62f), fontSize = 11.sp)
                    OutlinedButton(onClick = {
                        store.delete(folderImageKey("bg", folderEdit)); store.delete(folderImageKey("icon", folderEdit)); store.delete(folderImageKey("logo", folderEdit));
                        prefs.edit().remove("music_rom_folder_${folderEdit.key}").apply(); refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить оформление") }
                }
            },
            confirmButton = { TextButton(onClick = { prefs.edit().putString("rom_folder_title_${folderEdit.key}", title).apply(); editingFolderKey = null; refresh++ }) { Text("Готово") } }
        )
    }

    val romEdit = editingRomId?.let { id -> roms.firstOrNull { it.id == id } }
    if (romEdit != null) {
        var title by remember(romEdit.id) { mutableStateOf(romEdit.title) }
        AlertDialog(
            onDismissRequest = { editingRomId = null },
            containerColor = Color(0xE61B1F2A),
            title = { Text("Настройки игры", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("cover", romEdit.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Фон / обложка") }
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("icon", romEdit.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка плитки") }
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("logo", romEdit.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton(onClick = { chooserRomId = romEdit.id; editingRomId = null; chooserFilter = "" }, modifier = Modifier.fillMaxWidth()) { Text("Изменить приложение для открытия") }
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Музыка игры") }
                    OutlinedButton(onClick = {
                        store.delete(romImageKey("cover", romEdit.id)); store.delete(romImageKey("icon", romEdit.id)); store.delete(romImageKey("logo", romEdit.id));
                        prefs.edit().remove("rom_handler_${romEdit.id}").remove("music_rom_${romEdit.id}").apply(); refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить оформление и приложение") }
                    OutlinedButton(onClick = {
                        val remaining = (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()) - romEdit.id
                        prefs.edit().putStringSet("rom_ids", remaining).remove("rom_uri_${romEdit.id}").remove("rom_title_${romEdit.id}").remove("rom_platform_${romEdit.id}").apply()
                        store.delete(romImageKey("cover", romEdit.id)); store.delete(romImageKey("icon", romEdit.id)); store.delete(romImageKey("logo", romEdit.id));
                        editingRomId = null; refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Удалить игру") }
                }
            },
            confirmButton = { TextButton(onClick = { prefs.edit().putString("rom_title_${romEdit.id}", title).apply(); editingRomId = null; refresh++ }) { Text("Готово") } }
        )
    }

    val chooserRom = chooserRomId?.let { id -> roms.firstOrNull { it.id == id } }
    if (chooserRom != null) {
        val allApps = remember(chooserRom.id, refresh) { launchableApps(context) }
        val compatible = remember(chooserRom.id, refresh) { compatibleApps(context, Uri.parse(chooserRom.uri)).map { it.packageName }.toSet() }
        val query = chooserFilter.trim().lowercase(Locale.getDefault())
        val visibleApps = allApps.filter { query.isBlank() || it.label.lowercase(Locale.getDefault()).contains(query) || it.packageName.lowercase(Locale.getDefault()).contains(query) }
        AlertDialog(
            onDismissRequest = { chooserRomId = null },
            containerColor = Color(0xE61B1F2A),
            title = { Text("Открыть «${chooserRom.title}»", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 520.dp)) {
                    Text("Все приложения с иконкой запускаются из списка. Совместимые с этим ROM отмечены первыми; можно выбрать и другое приложение.", color = Color.White.copy(alpha = .68f), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = chooserFilter, onValueChange = { chooserFilter = it }, label = { Text("Поиск приложения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        visibleApps.forEach { app ->
                            val isCompatible = app.packageName in compatible
                            Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                saveHandler(prefs, chooserRom.id, app)
                                if (!startRomWithApp(context, Uri.parse(chooserRom.uri), app)) {
                                    android.widget.Toast.makeText(context, "Приложение не приняло ROM-файл. Попробуй другое.", android.widget.Toast.LENGTH_LONG).show()
                                }
                                chooserRomId = null
                                refresh++
                            }).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                DrawableIcon(app.icon, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.packageName, color = Color.White.copy(alpha = .45f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (isCompatible) Text("Подходит", color = Color(0xFF9FC2FF), fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooserRomId = null }) { Text("Отмена") } }
        )
    }
}

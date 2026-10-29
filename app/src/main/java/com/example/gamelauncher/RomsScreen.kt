@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.gamelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.util.UUID

private val romPlatforms = listOf(
    "PlayStation 2", "PlayStation", "PSP", "PS Vita", "Nintendo Switch",
    "Nintendo 64", "Game Boy / GBA", "SNES", "NES", "Sega", "Dreamcast", "Other"
)

private data class RomEntry(
    val id: String,
    val title: String,
    val platform: String,
    val uri: String
)

private data class RomFolder(
    val key: String,
    val title: String
)

private data class OpenApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable
)

private fun romTitleFromUri(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }
    val fallback = "Новая игра"
    return (name ?: fallback).substringBeforeLast('.', name ?: fallback)
}

private fun defaultFolderTitle(key: String): String = key

private fun folderImageKey(kind: String, folder: RomFolder): String = "rom_folder_${kind}_${folder.key}"

private fun romImageKey(kind: String, id: String): String = "rom_${kind}_${id}"

private fun fileIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    data = uri
    type = "*/*"
    addCategory(Intent.CATEGORY_DEFAULT)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun findOpenApps(context: Context, uri: Uri): List<OpenApp> {
    val intent = fileIntent(uri)
    return try {
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info: ResolveInfo ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val packageName = activity.packageName ?: return@mapNotNull null
                val activityName = activity.name ?: return@mapNotNull null
                OpenApp(
                    packageName = packageName,
                    activityName = activityName,
                    label = info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { packageName },
                    icon = info.loadIcon(context.packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun launchRom(
    context: Context,
    prefs: SharedPreferences,
    rom: RomEntry,
    onNeedChooser: () -> Unit
) {
    val uri = Uri.parse(rom.uri)
    val saved = prefs.getString("rom_handler_${rom.id}", null)
    if (!saved.isNullOrBlank()) {
        val component = ComponentName.unflattenFromString(saved)
        if (component != null) {
            try {
                val intent = fileIntent(uri).setComponent(component)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                prefs.edit().remove("rom_handler_${rom.id}").apply()
            }
        }
    }
    val apps = findOpenApps(context, uri)
    when (apps.size) {
        0 -> android.widget.Toast.makeText(
            context,
            "Android не нашёл приложение, которое принимает этот файл.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        1 -> {
            val app = apps.first()
            prefs.edit().putString(
                "rom_handler_${rom.id}",
                ComponentName(app.packageName, app.activityName).flattenToString()
            ).apply()
            try {
                context.startActivity(fileIntent(uri).setComponent(ComponentName(app.packageName, app.activityName)))
            } catch (_: Exception) {
                onNeedChooser()
            }
        }
        else -> onNeedChooser()
    }
}

@Composable
private fun DrawableIcon(drawable: Drawable, contentDescription: String, modifier: Modifier) {
    val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
    Image(bitmap, contentDescription, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun RomsScreen(store: ImageStore, prefs: SharedPreferences, apps: List<AppItem>) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingRomId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooserRomId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlatform by rememberSaveable { mutableStateOf(romPlatforms.first()) }
    var pendingImageKey by rememberSaveable { mutableStateOf<String?>(null) }

    val folders = remember(refresh) {
        romPlatforms.map { RomFolder(it, defaultFolderTitle(it)) }
    }
    val ids = remember(refresh) {
        (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()).toList()
    }
    val roms = remember(ids, refresh) {
        ids.mapNotNull { id ->
            val uri = prefs.getString("rom_uri_$id", null) ?: return@mapNotNull null
            RomEntry(
                id = id,
                title = prefs.getString("rom_title_$id", null) ?: romTitleFromUri(context, Uri.parse(uri)),
                platform = prefs.getString("rom_platform_$id", "Other") ?: "Other",
                uri = uri
            )
        }.sortedBy { it.title.lowercase() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
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
        if (uri != null && !key.isNullOrBlank()) {
            store.save(key, uri)
        }
        pendingImageKey = null
    }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val folder = editingFolderKey
        val rom = editingRomId
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            when {
                folder != null -> prefs.edit().putString("music_rom_folder_$folder", uri.toString()).apply()
                rom != null -> prefs.edit().putString("music_rom_$rom", uri.toString()).apply()
            }
            refresh++
        }
    }

    val currentFolder = folders.firstOrNull { it.key == folderKey }
    val currentRoms = roms.filter { it.platform == folderKey }
    val activeMusicUri = when {
        currentFolder != null -> prefs.getString("music_rom_folder_${currentFolder.key}", null)
        else -> prefs.getString("music_rom_root", null)
    }

    DisposableEffect(activeMusicUri) {
        var player: android.media.MediaPlayer? = null
        if (!activeMusicUri.isNullOrBlank()) {
            try {
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_GAME)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(context, Uri.parse(activeMusicUri))
                    isLooping = true
                    setVolume(0.22f, 0.22f)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> true }
                    prepareAsync()
                }
            } catch (_: Exception) {
                try { player?.release() } catch (_: Exception) { }
                player = null
            }
        }
        onDispose {
            try { player?.stop() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
        }
    }

    val rootBackground = rememberImage(store, "rom_root_bg", 1600, true)
    val folderBackground = currentFolder?.let { rememberImage(store, folderImageKey("bg", it), 1600, true) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10264D), Color(0xFF05070F))))) {
        if (currentFolder == null) {
            FadeImage(rootBackground, null, Modifier.fillMaxSize(), ContentScale.Crop)
        } else {
            FadeImage(folderBackground, null, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color(0x8F000000)))

        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 68.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        currentFolder?.let { prefs.getString("rom_folder_title_${it.key}", it.title) } ?: "ROM-библиотека",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontFamily = Light
                    )
                    Text(
                        if (currentFolder == null) "Папки-системы — нажми, чтобы открыть библиотеку внутри"
                        else "${currentRoms.size} игр · такая же плиточная библиотека внутри",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp
                    )
                }
                if (currentFolder == null) {
                    Button(onClick = { pendingPlatform = romPlatforms.first(); picker.launch(arrayOf("*/*")) }) {
                        Text("+ Добавить ROM")
                    }
                } else {
                    OutlinedButton(onClick = { folderKey = null }) { Text("‹ Папки") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { pendingPlatform = currentFolder.key; picker.launch(arrayOf("*/*")) }) {
                        Text("+ Добавить")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (currentFolder == null) {
                Text(
                    "Системы",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontFamily = Medium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(170.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(folders, key = { it.key }) { folder ->
                        val title = prefs.getString("rom_folder_title_${folder.key}", folder.title) ?: folder.title
                        val icon = rememberImage(store, folderImageKey("icon", folder), 256)
                        val logo = rememberImage(store, folderImageKey("logo", folder), 600)
                        val shape = RoundedCornerShape(24.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(shape)
                                .combinedClickable(
                                    onClick = { folderKey = folder.key },
                                    onLongClick = { editingFolderKey = folder.key }
                                )
                                .padding(12.dp)
                        ) {
                            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(17.dp))) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF253B62), Color(0xFF121826)))))
                                if (icon != null) {
                                    Image(icon, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text("▦", color = Color.White.copy(alpha = 0.85f), fontSize = 46.sp, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            if (logo != null) {
                                Image(logo, title, Modifier.fillMaxWidth().height(34.dp), contentScale = ContentScale.Fit)
                            } else {
                                Text(title, color = Color.White, fontFamily = Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "${roms.count { it.platform == folder.key }} игр · удерживай для настройки",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                if (currentRoms.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Папка пока пустая", color = Color.White, fontSize = 25.sp, fontFamily = Light)
                            Spacer(Modifier.height(8.dp))
                            Text("Добавь ROM-файл в ${currentFolder.key}", color = Color.White.copy(alpha = 0.65f))
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(onClick = { pendingPlatform = currentFolder.key; picker.launch(arrayOf("*/*")) }) {
                                Text("Выбрать файл ROM")
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(170.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(currentRoms, key = { it.id }) { rom ->
                            val cover = rememberImage(store, romImageKey("cover", rom.id), 700, true)
                            val icon = rememberImage(store, romImageKey("icon", rom.id), 256)
                            val logo = rememberImage(store, romImageKey("logo", rom.id), 600)
                            val handler = prefs.getString("rom_handler_${rom.id}", null)
                            val shape = RoundedCornerShape(24.dp)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .glass(shape)
                                    .combinedClickable(
                                        onClick = {
                                            launchRom(context, prefs, rom) { chooserRomId = rom.id }
                                        },
                                        onLongClick = { editingRomId = rom.id }
                                    )
                                    .padding(12.dp)
                            ) {
                                Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(17.dp))) {
                                    if (cover != null) {
                                        Image(cover, rom.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else if (icon != null) {
                                        Image(icon, rom.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Box(Modifier.fillMaxSize().background(Color(0xFF202A3B)), contentAlignment = Alignment.Center) {
                                            Text("ROM", color = Color.White.copy(alpha = 0.8f), fontSize = 28.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(9.dp))
                                if (logo != null) {
                                    Image(logo, rom.title, Modifier.fillMaxWidth().height(36.dp), contentScale = ContentScale.Fit)
                                } else {
                                    Text(rom.title, color = Color.White, fontFamily = Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    if (handler.isNullOrBlank()) "Нажми — выбрать приложение для открытия" else "Открывается сохранённым приложением",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val folder = folders.firstOrNull { it.key == editingFolderKey }
    if (folder != null) {
        var title by remember(folder.key, refresh) { mutableStateOf(prefs.getString("rom_folder_title_${folder.key}", folder.title) ?: folder.title) }
        AlertDialog(
            onDismissRequest = { editingFolderKey = null },
            containerColor = Color(0xFF1B1F2A),
            title = { Text("Настройки папки", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название папки") }, singleLine = true)
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("bg", folder); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Фон папки") }
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("icon", folder); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка плитки") }
                    OutlinedButton(onClick = { pendingImageKey = folderImageKey("logo", folder); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/mpeg", "audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Музыка папки") }
                    if (!prefs.getString("music_rom_folder_${folder.key}", null).isNullOrBlank()) {
                        TextButton(onClick = { prefs.edit().remove("music_rom_folder_${folder.key}").apply(); refresh++ }) { Text("Убрать музыку папки") }
                    }
                    OutlinedButton(
                        onClick = {
                            store.delete(folderImageKey("bg", folder))
                            store.delete(folderImageKey("icon", folder))
                            store.delete(folderImageKey("logo", folder))
                            refresh++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сбросить картинки") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("rom_folder_title_${folder.key}", title.trim().ifBlank { folder.title }).apply()
                    editingFolderKey = null
                    refresh++
                }) { Text("Сохранить") }
            }
        )
    }

    val rom = roms.firstOrNull { it.id == editingRomId }
    if (rom != null) {
        val savedHandler = prefs.getString("rom_handler_${rom.id}", null)
        AlertDialog(
            onDismissRequest = { editingRomId = null },
            containerColor = Color(0xFF1B1F2A),
            title = { Text("Настройки ROM", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("cover", rom.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Фон / обложка игры") }
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("icon", rom.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка плитки") }
                    OutlinedButton(onClick = { pendingImageKey = romImageKey("logo", rom.id); imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Картинка-название") }
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/mpeg", "audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Фоновая музыка игры") }
                    Text(
                        if (prefs.getString("music_rom_${rom.id}", null).isNullOrBlank()) "Музыка не выбрана" else "Музыка выбрана",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp
                    )
                    if (!prefs.getString("music_rom_${rom.id}", null).isNullOrBlank()) {
                        TextButton(onClick = { prefs.edit().remove("music_rom_${rom.id}").apply(); refresh++ }) { Text("Убрать музыку игры") }
                    }
                    Divider(color = Color.White.copy(alpha = 0.15f))
                    Text(
                        if (savedHandler.isNullOrBlank()) "Приложение для открытия ещё не выбрано" else "Приложение для открытия сохранено",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = { chooserRomId = rom.id; editingRomId = null }, modifier = Modifier.fillMaxWidth()) { Text("Изменить приложение для открытия") }
                    OutlinedButton(
                        onClick = {
                            store.delete(romImageKey("cover", rom.id))
                            store.delete(romImageKey("icon", rom.id))
                            store.delete(romImageKey("logo", rom.id))
                            refresh++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сбросить картинки") }
                    OutlinedButton(
                        onClick = {
                            prefs.edit()
                                .putStringSet("rom_ids", (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()) - rom.id)
                                .remove("rom_uri_${rom.id}")
                                .remove("rom_title_${rom.id}")
                                .remove("rom_platform_${rom.id}")
                                .remove("rom_handler_${rom.id}")
                                .remove("music_rom_${rom.id}")
                                .apply()
                            store.delete(romImageKey("cover", rom.id))
                            store.delete(romImageKey("icon", rom.id))
                            store.delete(romImageKey("logo", rom.id))
                            editingRomId = null
                            refresh++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Удалить ROM из библиотеки") }
                }
            },
            confirmButton = { TextButton(onClick = { editingRomId = null }) { Text("Готово") } }
        )
    }

    val chooserRom = roms.firstOrNull { it.id == chooserRomId }
    if (chooserRom != null) {
        val openApps = remember(chooserRom.id, refresh) { findOpenApps(context, Uri.parse(chooserRom.uri)) }
        AlertDialog(
            onDismissRequest = { chooserRomId = null },
            containerColor = Color(0xFF1B1F2A),
            title = { Text("Открыть ${chooserRom.title}", color = Color.White) },
            text = {
                if (openApps.isEmpty()) {
                    Text("Android не нашёл приложения, которое заявило поддержку этого файла.", color = Color.White)
                } else {
                    Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Выбери приложение. После выбора оно будет запомнено для этой игры.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        openApps.forEach { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val component = ComponentName(app.packageName, app.activityName)
                                    prefs.edit().putString("rom_handler_${chooserRom.id}", component.flattenToString()).apply()
                                    chooserRomId = null
                                    try {
                                        context.startActivity(fileIntent(Uri.parse(chooserRom.uri)).setComponent(component))
                                    } catch (_: Exception) {
                                        android.widget.Toast.makeText(context, "Не удалось открыть файл этим приложением.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    refresh++
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DrawableIcon(app.icon, app.label, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                                Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
                                Text("›", color = Color(0xFF8DB8FF), fontSize = 24.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooserRomId = null }) { Text("Отмена") } }
        )
    }
}

package com.example.gamelauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private fun romTitleFromUri(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
    return (name ?: "Новая игра").substringBeforeLast('.', name ?: "Новая игра")
}

@Composable
fun RomsScreen(store: ImageStore, prefs: SharedPreferences, apps: List<AppItem>) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selectedPlatform by rememberSaveable { mutableStateOf(romPlatforms.first()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlatform by rememberSaveable { mutableStateOf(romPlatforms.first()) }
    val fallbackIcon = apps.firstOrNull()?.icon
    val ids = remember(refresh) { (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()).toList() }
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
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = editingId
        if (uri != null && id != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            prefs.edit().putString("music_rom_$id", uri.toString()).apply()
            refresh++
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = editingId
        if (uri != null && id != null) {
            val key = pendingPlatform
            store.save("rom_${key}_$id", uri)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10264D), Color(0xFF05070F))))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 66.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ROM-библиотека", color = Color.White, fontSize = 30.sp, fontFamily = Light)
                    Text("Добавляй свои файлы и распределяй их по системам", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                }
                Button(onClick = { pendingPlatform = selectedPlatform; picker.launch(arrayOf("*/*")) }) {
                    Text("+ Добавить ROM")
                }
            }
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(romPlatforms.size) { index ->
                    val platform = romPlatforms[index]
                    FilterChip(
                        selected = selectedPlatform == platform,
                        onClick = { selectedPlatform = platform },
                        label = { Text(platform) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val shown = roms.filter { it.platform == selectedPlatform }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedPlatform, color = Color.White, fontSize = 24.sp, fontFamily = Light)
                        Spacer(Modifier.height(8.dp))
                        Text("Пока здесь нет игр", color = Color.White.copy(alpha = 0.65f))
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { pendingPlatform = selectedPlatform; picker.launch(arrayOf("*/*")) }) {
                            Text("Выбрать файл ROM")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.id }) { rom ->
                        val cover = rememberImage(store, "rom_cover_${rom.id}", 600, true)
                        val customIcon = rememberImage(store, "rom_icon_${rom.id}", 256)
                        Column(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))
                                .combinedClickable(
                                    onClick = {
                                        try {
                                            val uri = Uri.parse(rom.uri)
                                            val mime = when (rom.platform) {
                                                "PlayStation 2" -> "application/octet-stream"
                                                else -> "*/*"
                                            }
                                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            context.startActivity(Intent.createChooser(intent, "Открыть ROM в эмуляторе"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(context, "Не найдено приложение, которое может открыть этот файл. Установи совместимый эмулятор.", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    onLongClick = { editingId = rom.id }
                                ).padding(12.dp)
                        ) {
                            if (cover != null) {
                                Image(cover, rom.title, Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                            } else if (customIcon != null || fallbackIcon != null) {
                                Image(customIcon ?: fallbackIcon!!, rom.title, Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF202A3B)), contentAlignment = Alignment.Center) {
                                    Text("ROM", color = Color.White.copy(alpha = 0.8f), fontSize = 26.sp)
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(rom.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Удерживай для настройки", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    val rom = roms.firstOrNull { it.id == editingId }
    if (rom != null) {
        var title by remember(rom.id) { mutableStateOf(rom.title) }
        var platform by remember(rom.id) { mutableStateOf(rom.platform) }
        AlertDialog(
            onDismissRequest = { editingId = null },
            containerColor = Color(0xFF1B1F2A),
            title = { Text("Настройки ROM", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true)
                    Text("Система", color = Color.White)
                    romPlatforms.forEach { item ->
                        Row(Modifier.fillMaxWidth().clickable { platform = item }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = platform == item, onClick = { platform = item })
                            Text(item, color = Color.White)
                        }
                    }
                    OutlinedButton(onClick = { pendingPlatform = "rom_cover"; imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Изменить фон / обложку") }
                    OutlinedButton(onClick = { pendingPlatform = "rom_icon"; imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Изменить иконку плитки") }
                    OutlinedButton(onClick = { pendingPlatform = "rom_logo"; imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Изменить картинку-название") }
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать фоновую музыку") }
                    if (!prefs.getString("music_rom_${rom.id}", null).isNullOrBlank()) {
                        TextButton(onClick = { prefs.edit().remove("music_rom_${rom.id}").apply(); refresh++ }) { Text("Убрать музыку") }
                    }
                    OutlinedButton(onClick = {
                        prefs.edit().putStringSet("rom_ids", (prefs.getStringSet("rom_ids", emptySet()) ?: emptySet()) - rom.id)
                            .remove("rom_uri_${rom.id}").remove("rom_title_${rom.id}").remove("rom_platform_${rom.id}")
                            .remove("music_rom_${rom.id}").apply()
                        listOf("rom_cover", "rom_icon", "rom_logo").forEach { store.delete("${it}_${rom.id}") }
                        editingId = null
                        refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Удалить ROM из библиотеки") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("rom_title_${rom.id}", title.trim().ifBlank { rom.title })
                        .putString("rom_platform_${rom.id}", platform).apply()
                    editingId = null
                    refresh++
                }) { Text("Сохранить") }
            }
        )
    }
}

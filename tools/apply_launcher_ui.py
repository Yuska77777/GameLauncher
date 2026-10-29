from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

screens_path = ROOT / "app/src/main/java/com/example/gamelauncher/Screens.kt"
roms_path = ROOT / "app/src/main/java/com/example/gamelauncher/RomsScreen.kt"
workflow_path = ROOT / ".github/workflows/build.yml"

screens = screens_path.read_text()
old_switch = '''        Row(\n            Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart).statusBarsPadding()\n                .padding(start = 12.dp, top = 10.dp).glass(RoundedCornerShape(24.dp)).padding(4.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {\n            FilterChip(selected = !showRomMode, onClick = { showRomMode = false }, label = { Text("Android") })\n            Spacer(Modifier.width(4.dp))\n            FilterChip(selected = showRomMode, onClick = { showRomMode = true }, label = { Text("ROMы") })\n        }'''
new_switch = '''        Row(\n            Modifier.align(Alignment.TopEnd).statusBarsPadding()\n                .padding(top = 10.dp, end = 62.dp)\n                .glass(RoundedCornerShape(24.dp)).padding(4.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {\n            FilterChip(selected = !showRomMode, onClick = { showRomMode = false }, label = { Text("Android") })\n            Spacer(Modifier.width(4.dp))\n            FilterChip(selected = showRomMode, onClick = { showRomMode = true }, label = { Text("ROMы") })\n        }'''
if old_switch not in screens:
    raise SystemExit("Expected ROM/Android switch block was not found in Screens.kt")
screens = screens.replace(old_switch, new_switch, 1)
old_gear = '''        Box(\n            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 12.dp)\n                .size(42.dp).glass(CircleShape).clickable { showSettings = true },\n            contentAlignment = Alignment.Center\n        ) {\n            Text("⚙", color = Color.White, fontSize = 20.sp)\n        }'''
# The gear remains on the right; the ROM/Android switch now sits immediately to its left.
if old_gear not in screens:
    raise SystemExit("Expected launcher settings gear block was not found in Screens.kt")
screens_path.write_text(screens)

roms = roms_path.read_text()
start = roms.index('    val rootBackground = rememberImage(store, "rom_root_bg", 1600, true)')
end = roms.index('    val folderEdit = editingFolderKey?.let { key ->', start)

new_ui = r'''    val rootBackground = rememberImage(store, "rom_root_bg", 1600, true)
    val carouselBackground = rememberImage(store, folderImageKey("bg", folders[rootPager.currentPage]), 1600, true)
    val currentBackground = currentFolder?.let { rememberImage(store, folderImageKey("bg", it), 1600, true) }
    val gamePager = rememberPagerState(initialPage = 0) { currentRoms.size }
    LaunchedEffect(folderKey, currentRoms.size) {
        if (currentRoms.isNotEmpty()) gamePager.scrollToPage(0)
    }
    val activeRom = currentRoms.getOrNull(gamePager.currentPage)
    val activeRomBackground = activeRom?.let { rememberImage(store, romImageKey("cover", it.id), 1200, true) }
    val activeRomMusic = activeRom?.let { prefs.getString("music_rom_${it.id}", null) }

    // System music plays only on the horizontal system carousel.
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

    // Game music follows the selected game inside a system folder.
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
                    contentPadding = PaddingValues(horizontal = 230.dp),
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
                        Box(
                            Modifier.fillMaxWidth().height(410.dp).clip(shape).glass(shape),
                            contentAlignment = Alignment.Center
                        ) {
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
                        contentPadding = PaddingValues(horizontal = 230.dp),
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
                                        // Saved handler worked.
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
                            Box(Modifier.fillMaxWidth().height(330.dp).clip(shape).glass(shape)) {
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

'''
roms = roms[:start] + new_ui + roms[end:]
roms_path.write_text(roms)

# Restore the build workflow after this one-time migration so future pushes only build the APK.
original_workflow = '''name: Build APK
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: GameLauncher-apk
          path: app/build/outputs/apk/debug/*.apk
'''
workflow_path.write_text(original_workflow)

# Remove this temporary helper from the final tree.
Path(__file__).unlink()

subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
subprocess.run(["git", "commit", "-m", "Improve ROM launcher carousel UI and game media"], cwd=ROOT, check=True)
subprocess.run(["git", "push"], cwd=ROOT, check=True)
print("ROM launcher UI migration committed and pushed")

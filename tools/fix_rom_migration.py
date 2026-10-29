from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
roms_path = ROOT / "app/src/main/java/com/example/gamelauncher/RomsScreen.kt"
workflow_path = ROOT / ".github/workflows/build.yml"

roms = roms_path.read_text()
start_marker = '    // Music is only for the system carousel. It is intentionally disabled inside a folder.\n'
start = roms.find(start_marker)
end_marker = '    val rootBackground = rememberImage(store, "rom_root_bg", 1600, true)\n'
end = roms.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("Old duplicated system-music block was not found")
roms = roms[:start] + roms[end:]
roms_path.write_text(roms)

workflow_path.write_text('''name: Build APK\non:\n  push:\n  workflow_dispatch:\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: 17\n      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: 8.7\n      - run: gradle assembleDebug\n      - uses: actions/upload-artifact@v4\n        with:\n          name: GameLauncher-apk\n          path: app/build/outputs/apk/debug/*.apk\n''')
Path(__file__).unlink()
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=ROOT, check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=ROOT, check=True)
subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
subprocess.run(["git", "commit", "-m", "Remove duplicated ROM music setup"], cwd=ROOT, check=True)
subprocess.run(["git", "push"], cwd=ROOT, check=True)

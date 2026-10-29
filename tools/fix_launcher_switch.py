from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
screens_path = ROOT / "app/src/main/java/com/example/gamelauncher/Screens.kt"
workflow_path = ROOT / ".github/workflows/build.yml"

screens = screens_path.read_text()
old = '''        Row(\n            Modifier.align(Alignment.TopEnd).statusBarsPadding()\n                .padding(top = 10.dp, end = 62.dp)\n                .glass(RoundedCornerShape(24.dp)).padding(4.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {'''
new = '''        Row(\n            Modifier.fillMaxSize().wrapContentSize(Alignment.TopEnd).statusBarsPadding()\n                .padding(top = 10.dp, end = 62.dp)\n                .glass(RoundedCornerShape(24.dp)).padding(4.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {'''
if old not in screens:
    raise SystemExit("ROM/Android switch block not found")
screens_path.write_text(screens.replace(old, new, 1))

workflow_path.write_text('''name: Build APK\non:\n  push:\n  workflow_dispatch:\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: 17\n      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: 8.7\n      - run: gradle assembleDebug\n      - uses: actions/upload-artifact@v4\n        with:\n          name: GameLauncher-apk\n          path: app/build/outputs/apk/debug/*.apk\n''')
Path(__file__).unlink()
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=ROOT, check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=ROOT, check=True)
subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
subprocess.run(["git", "commit", "-m", "Position ROM mode switch beside settings"], cwd=ROOT, check=True)
subprocess.run(["git", "push"], cwd=ROOT, check=True)

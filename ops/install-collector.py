"""Install the read-only tutor snapshot collector for the signed-in macOS user."""
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys

ops = Path(__file__).resolve().parent
if not (ops / "collector-local.json").is_file():
    raise SystemExit("Configure the expected tutor ID and private GCS bucket first.")
label = "com.ramsbaby.preply-calendar-read"
domain = "gui/" + str(os.getuid())
home = Path.home()
log_dir = home / "Library/Logs/Preply"
log_dir.mkdir(parents=True, exist_ok=True)
plist = home / "Library/LaunchAgents" / (label + ".plist")
tools = [str(Path(shutil.which(cmd)).parent) for cmd in ("ego-browser", "gcloud")]
config = {
    "Label": label, "ProgramArguments": [sys.executable, str(ops / "collect-calendar.py")],
    "EnvironmentVariables": {"PATH": ":".join(tools + ["/opt/homebrew/bin", "/usr/bin", "/bin"]),
                             "TZ": "Asia/Seoul"},
    "StartCalendarInterval": [{"Hour": h, "Minute": m} for h, m in [(0, 5), (21, 55), (22, 0), (22, 2)]],
    "StandardOutPath": str(log_dir / "calendar.log"), "StandardErrorPath": str(log_dir / "calendar-error.log"),
}
plist.write_bytes(plistlib.dumps(config))
subprocess.run(["launchctl", "bootout", domain + "/" + label], capture_output=True)
subprocess.run(["launchctl", "bootstrap", domain, str(plist)], check=True)

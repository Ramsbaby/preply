"""Read the logged-in tutor calendar and publish private, date-keyed snapshots."""
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import tempfile
from zoneinfo import ZoneInfo

ops = Path(__file__).resolve().parent
today = dt.datetime.now(ZoneInfo("Asia/Seoul")).date()
settings = json.loads((ops / "collector-local.json").read_text())
with tempfile.TemporaryDirectory() as tmp:
    options = dict(start=(today - dt.timedelta(days=7)).isoformat(), end=today.isoformat(),
                   expectedTutor=settings["tutor"], directory=tmp)
    script = "const collectorOptions=" + json.dumps(options) + ";\n" + (ops / "read-calendar.mjs").read_text()
    subprocess.run(["ego-browser", "nodejs"], input=script, text=True, check=True, timeout=75)
    files = sorted(Path(tmp).glob("*.json"), reverse=True)
    if len(files) != 8:
        raise RuntimeError("Incomplete daily snapshots")
    # Publish today first, including on days with zero lessons.
    for file in files:
        subprocess.run(["gcloud", "storage", "cp", "--quiet", str(file),
                        f"gs://{settings['bucket']}/{file.name}"], check=True, timeout=45)

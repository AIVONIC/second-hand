"""End to end against a running server (costs cents; not run in CI): a typed job with photos, then "job done",
then the report and its PDF.

    uv run python tests/live_report.py tests/photos/heatpump.jpg tests/photos/valve.jpg
"""

import asyncio
import base64
import json
import sys
import time
from pathlib import Path

import httpx
import websockets
from PIL import Image

from secondhand.config import required, setting

URL = setting("SECONDHAND_URL", "ws://127.0.0.1:5291")
QUESTIONS = [
    "I'm at the heat pump. What does this display say?",
    "The pressure gauge reads 0.6 bar. What should I do?",
    "Done, it reads 1.4 bar now and I closed the valve.",
    "Job done.",
]


async def main(photos: list[str]) -> None:
    token = required("SECONDHAND_DEVICE_TOKEN")
    t0 = time.perf_counter()
    async with websockets.connect(f"{URL}/v1/session?token={token}&audio=pcm16", max_size=None) as ws:
        for i, q in enumerate(QUESTIONS):
            path = photos[min(i, len(photos) - 1)]
            w, h = Image.open(path).size
            await ws.send(json.dumps({"type": "photo", "jpeg": base64.b64encode(Path(path).read_bytes()).decode(),
                                      "w": w, "h": h}))
            await ws.send(json.dumps({"type": "text", "text": q}))
            print(f"> {q}")
            while True:
                msg = await asyncio.wait_for(ws.recv(), 120)
                if isinstance(msg, bytes):
                    continue
                m = json.loads(msg)
                if m["type"] == "answer":
                    print(f"  {m['say']}  [step={m['step']!r} stop={m['stop']!r} done={m['job_done']}]")
                elif m["type"] == "audio_end" and q != QUESTIONS[-1]:
                    break
                elif m["type"] in ("report", "report_failed"):
                    report = m
                    break
        print(f"\n{report['type']} after {time.perf_counter() - t0:.1f}s")
        print(json.dumps({k: v for k, v in report.items() if k != "invoice"}, indent=1))
        print("invoice total:", report.get("invoice", {}).get("total"))
    if report["type"] == "report":
        base = URL.replace("ws://", "http://").replace("wss://", "https://")
        r = httpx.get(f"{base}/v1/reports/{report['id']}.pdf", headers={"Authorization": f"Bearer {token}"})
        out = Path(f"/tmp/second-hand-{report['id']}.pdf")
        out.write_bytes(r.content)
        print(f"PDF {r.status_code}, {len(r.content)} bytes -> {out}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))

"""End to end against a running server (costs cents; not run in CI): speak a question into /v1/session as 16 kHz
PCM, paced in real time like the glasses' microphone, send the photo when asked, and time every step.

    uv run uvicorn secondhand.app:app --port 5291 &
    uv run python tests/live_session.py "How do I test this safety valve?" tests/photos/valve.jpg
"""

import asyncio
import base64
import json
import subprocess
import sys
import time
from pathlib import Path

import httpx
import websockets
from PIL import Image

from secondhand.config import required, setting

URL = setting("SECONDHAND_URL", "ws://127.0.0.1:5291")


async def question_pcm(text: str) -> bytes:
    """The question, spoken by a different voice than the agent's, as 16 kHz mono PCM."""
    async with httpx.AsyncClient(timeout=60) as http:
        r = await http.post("https://api.elevenlabs.io/v1/text-to-speech/pNInz6obpgDQGcFmaJgB?output_format=mp3_44100_128",
                            headers={"xi-api-key": required("ELEVENLABS_API_KEY")},
                            json={"text": text, "model_id": "eleven_turbo_v2_5"})
        r.raise_for_status()
    return subprocess.run(["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "s16le", "-ac", "1", "-ar", "16000", "-"],
                          input=r.content, capture_output=True, check=True).stdout


async def main(text: str, photo_path: str) -> None:
    pcm = await question_pcm(text) + b"\x00" * 32000 * 2  # two seconds of silence after: the model hears the end
    jpeg = Path(photo_path).read_bytes()
    w, h = Image.open(photo_path).size
    t0 = time.perf_counter()
    stamp = lambda: f"{time.perf_counter() - t0:6.2f}s"  # noqa: E731
    mp3 = b""
    async with websockets.connect(f"{URL}/v1/session?token={required('SECONDHAND_DEVICE_TOKEN')}", max_size=None) as ws:
        async def mic() -> None:
            for i in range(0, len(pcm), 2560):  # 80 ms frames, real time
                await ws.send(pcm[i:i + 2560])
                await asyncio.sleep(0.08)
        mic_task = asyncio.create_task(mic())
        speech_end = None
        async for msg in ws:
            if isinstance(msg, bytes):
                mp3 += msg
                continue
            m = json.loads(msg)
            if m["type"] == "need_photo":
                print(stamp(), "need_photo -> sending the photo")
                await ws.send(json.dumps({"type": "photo", "jpeg": base64.b64encode(jpeg).decode(), "w": w, "h": h}))
            elif m["type"] == "heard" and m["final"]:
                speech_end = time.perf_counter()
                print(stamp(), "heard:", m["text"])
            elif m["type"] == "answer":
                print(stamp(), f"answer ({m['think_s']:.1f}s thinking): {m['say']}  stop={m['stop']!r}")
            elif m["type"] == "audio_start":
                first_audio = None
            elif m["type"] == "outline":
                print(stamp(), f"outline {m['part']!r} {m['box']} ({m['secs']}s)")
            elif m["type"] == "audio_end":
                print(stamp(), f"spoken reply: {len(mp3)} bytes of MP3")
                if speech_end:
                    print(f"from the end of the question to the end of the reply's audio: {time.perf_counter() - speech_end:.2f}s")
                break
        mic_task.cancel()
    Path("/tmp/claude-1000/-home-sitemind-Aivonic/0a9e8d5d-89a6-446c-8fc4-b5ea19e76fae/scratchpad/reply.mp3").write_bytes(mp3)


asyncio.run(main(sys.argv[1], sys.argv[2]))

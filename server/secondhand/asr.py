"""Hearing: Muse Voice Transcribe in realtime, ENDPOINTING mode. The model finds where each utterance starts and
ends, so the technician just talks: no button, no wake word."""

from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import AsyncIterator

import websockets

from secondhand.config import required, setting

ASR_URL = setting("META_ASR_URL", "wss://api.meta.ai/v1/asr/realtime")
# words a technician says that a general model mishears; biasing does not guarantee spelling
KEYWORDS = ["heat pump", "water heater", "safety valve", "filling valve", "manometer", "brine", "breaker",
            "fuse box", "particle filter", "radiator", "bleed", "bar", "Second Hand"]


class Ear:
    """One realtime transcription session. feed() raw 16 kHz PCM; iterate events()."""

    def __init__(self) -> None:
        self.ws = None
        self.queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=200)

    async def open(self) -> None:
        self.ws = await websockets.connect(f"{ASR_URL}?sessionId=sh-{uuid.uuid4()}", open_timeout=20, max_size=None)
        await self.ws.send(json.dumps({
            "authorization": {"accessToken": f"Bearer {required('MODEL_API_KEY')}"},
            "audioEncoding": "PCM_16KHZ",
            "model": "muse-voice-transcribe-1.0",
            "mode": "ENDPOINTING",
            "emitAudioProgress": False,
            "keywords": KEYWORDS,
            "languageBias": ["English"],
        }))
        ack = json.loads(await self.ws.recv())
        if "sessionId" not in ack:
            raise RuntimeError(f"ASR handshake refused: {ack}")
        asyncio.create_task(self._pump())

    def feed(self, pcm: bytes) -> None:
        try:
            self.queue.put_nowait(pcm)
        except asyncio.QueueFull:
            pass  # never let a stalled upstream block the phone; a dropped 80 ms frame is better than a backlog

    async def _pump(self) -> None:
        try:
            while (chunk := await self.queue.get()) is not None:
                await self.ws.send(chunk)
            await self.ws.send(json.dumps({"type": "endStream"}))
        except websockets.ConnectionClosed:
            pass

    async def events(self) -> AsyncIterator[dict]:
        async for message in self.ws:
            if isinstance(message, bytes):
                continue
            event = json.loads(message)
            if event.get("type") == "error":
                raise RuntimeError(f"ASR error: {event.get('message')}")
            yield event

    async def close(self) -> None:
        await self.queue.put(None)
        if self.ws:
            await self.ws.close()

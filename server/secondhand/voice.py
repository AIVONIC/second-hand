"""Speaking: Meta Model API has no text-to-speech, so the agent's words are voiced by ElevenLabs and streamed back
to the phone, which plays them on the glasses' speakers. The phone picks the format: MP3, or raw 16 kHz PCM that it
can play while it is still arriving."""

from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from secondhand.config import required, setting

VOICE = setting("ELEVENLABS_VOICE", "21m00Tcm4TlvDq8ikWAM")
MODEL = setting("ELEVENLABS_MODEL", "eleven_turbo_v2_5")


FORMATS = {"mp3": "mp3_44100_128", "pcm16": "pcm_16000"}  # pcm16: signed 16-bit little-endian, mono, 16 kHz


async def speak(http: httpx.AsyncClient, text: str, fmt: str = "mp3") -> AsyncIterator[bytes]:
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{VOICE}/stream?output_format={FORMATS[fmt]}"
    body = {"text": text, "model_id": MODEL, "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}
    async with http.stream("POST", url, headers={"xi-api-key": required("ELEVENLABS_API_KEY")}, json=body) as r:
        r.raise_for_status()
        async for chunk in r.aiter_bytes():
            if chunk:
                yield chunk

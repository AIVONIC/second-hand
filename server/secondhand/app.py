"""The Second Hand server: one WebSocket per job, between the phone (which holds the glasses) and Meta's models.

Phone -> server
  binary frames       16 kHz mono PCM from the glasses' microphone (the phone stops sending while it plays a reply)
  {"type":"photo","jpeg":<base64>,"w":..,"h":..}   a photo from the glasses (sent when asked, or on its own)
  {"type":"text","text":".."}                      a typed question (for testing without a voice)
Server -> phone
  {"type":"heard","text":"..","final":bool}       what the agent is hearing, live
  {"type":"need_photo"}                           take a photo now: the technician started to speak
  {"type":"answer", say, step, stop, display_original, display_english, job_done, think_s}
  {"type":"audio_start"} binary audio frames {"type":"audio_end"}   the reply, spoken: MP3, or with ?audio=pcm16
                                                    16 kHz mono 16-bit PCM (little-endian) the phone plays as it comes
  {"type":"outline","part":"..","box":[x1,y1,x2,y2],"w":..,"h":..}   where the part is in the last photo
  {"type":"report_writing"} then {"type":"report", ..}   the job report and invoice draft, when the job is done
                                                    (or {"type":"report_failed","reason":..}); the PDF is
                                                    GET /v1/reports/<id>.pdf with "Authorization: Bearer <token>"
Phone -> server, also
  {"type":"finish"}                               write the report now (the same as saying "job done")

The key to Meta's API stays here. The phone proves itself with a device token from the environment
(SECONDHAND_DEVICE_TOKEN); with no token configured the server admits nobody."""

from __future__ import annotations

import asyncio
import base64
import hmac
import json
import logging
import time
import uuid

import httpx
from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse

from secondhand import report
from secondhand.agent import Job, answer, locate
from secondhand.asr import Ear
from secondhand.config import setting
from secondhand.meta import Meta
from secondhand.voice import FORMATS, speak

log = logging.getLogger("secondhand")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
app = FastAPI(title="Second Hand")
PHOTO_WAIT_S = 2.0  # at the end of a question, wait this long for the photo asked for when it began


def admitted(token: str | None) -> bool:
    expected = setting("SECONDHAND_DEVICE_TOKEN")
    return bool(expected) and token is not None and hmac.compare_digest(token, expected)


@app.get("/health")
async def health() -> dict:
    return {"ok": True}


@app.get("/v1/reports/{job_id}.pdf")
async def report_pdf(job_id: str, authorization: str | None = Header(default=None)) -> FileResponse:
    token = authorization.removeprefix("Bearer ").strip() if authorization else None
    if not admitted(token):
        raise HTTPException(401)
    path = report.REPORTS_DIR / f"{job_id}.pdf"
    if not (len(job_id) == 8 and all(c in "0123456789abcdef" for c in job_id)) or not path.exists():
        raise HTTPException(404)
    return FileResponse(path, media_type="application/pdf", filename=f"second-hand-job-{job_id}.pdf")


class Session:
    def __init__(self, ws: WebSocket, audio: str) -> None:
        self.ws = ws
        self.audio = audio
        self.meta = Meta()
        self.http = httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=10.0))
        self.job = Job(id=uuid.uuid4().hex[:8])
        self.photo: tuple[bytes, int, int, float] | None = None  # jpeg, w, h, when
        self.asked_at = 0.0
        self.busy = asyncio.Lock()
        self.started = time.time()  # wall clock: the report's labour time
        self.shots: list[report.Shot] = []
        self.last_shot: tuple[bytes, float] | None = None  # the latest photo a turn used: the "after" shot
        self.reporting = False

    async def send(self, msg: dict) -> None:
        await self.ws.send_text(json.dumps(msg))

    async def turn(self, heard: str) -> None:
        """One question, answered: think, then speak and outline at the same time."""
        async with self.busy:
            deadline = time.monotonic() + PHOTO_WAIT_S  # in-process timing within one turn only
            while (self.photo is None or self.photo[3] < self.asked_at) and time.monotonic() < deadline:
                await asyncio.sleep(0.05)
            photo = self.photo
            t = await answer(self.meta, self.job, heard, photo[0] if photo else None)
            log.info("job %s heard=%r say=%r stop=%r point=%r think=%.1fs", self.job.id, heard, t.say, t.stop,
                     t.point_at, t.think_s)
            await self.send({"type": "answer", **{k: v for k, v in t.__dict__.items()}})
            if photo:
                self.last_shot = (photo[0], time.time())
                if not self.shots or t.step or t.stop:
                    self.shots.append(report.Shot(photo[0], time.time(), t.stop or t.step or "Start of the job"))
            tasks = [self.voice(t.say)]
            if t.point_at and photo:
                tasks.append(self.outline(t.point_at, photo))
            await asyncio.gather(*tasks)
        if t.job_done:
            await self.finish()

    async def finish(self) -> None:
        """Write the job report and the invoice draft, once per job."""
        if self.reporting:
            return
        self.reporting = True
        await self.send({"type": "report_writing"})
        shots = list(self.shots)
        if self.last_shot and (not shots or shots[-1].jpeg is not self.last_shot[0]):
            shots.append(report.Shot(self.last_shot[0], self.last_shot[1], "End of the job"))
        try:
            r = await report.write(self.http, self.meta, self.job, shots, self.started)
            pdf = await asyncio.to_thread(report.render, r)
            await asyncio.to_thread(report.save, r, pdf)
        except Exception as e:  # the phone must hear about it: a silent failure looks like a slow model
            log.exception("job %s: report failed", self.job.id)
            self.reporting = False
            await self.send({"type": "report_failed", "reason": f"{type(e).__name__}: {e}"[:300]})
            return
        log.info("job %s report by %s in %.1fs: %d bytes, %d photos", self.job.id, r.model, r.seconds, len(pdf),
                 len(r.shots))
        await self.send({"type": "report", **r.summary()})

    async def voice(self, text: str) -> None:
        await self.send({"type": "audio_start"})
        try:
            async for chunk in speak(self.http, text, self.audio):
                await self.ws.send_bytes(chunk)
        finally:
            await self.send({"type": "audio_end"})

    async def outline(self, part: str, photo: tuple[bytes, int, int, float]) -> None:
        box, secs = await locate(self.meta, photo[0], photo[1], photo[2], part)
        if box:
            await self.send({"type": "outline", "part": part, "box": box.as_list(), "w": photo[1], "h": photo[2],
                             "secs": round(secs, 2)})

    async def listen(self, ear: Ear) -> None:
        async for e in ear.events():
            kind = e.get("type")
            if kind == "speechStart":
                self.asked_at = time.monotonic()
                await self.send({"type": "need_photo"})
            elif kind == "transcript":
                await self.send({"type": "heard", "text": e.get("transcript", ""), "final": False})
            elif kind == "speechComplete" and (text := (e.get("transcript") or "").strip()):
                await self.send({"type": "heard", "text": text, "final": True})
                asyncio.create_task(self.turn(text))

    async def close(self) -> None:
        await self.meta.aclose()
        await self.http.aclose()


@app.websocket("/v1/session")
async def session(ws: WebSocket) -> None:
    if not admitted(ws.query_params.get("token")):
        await ws.close(code=1008)
        return
    audio = ws.query_params.get("audio", "mp3")
    if audio not in FORMATS:
        await ws.close(code=1003)
        return
    await ws.accept()
    s = Session(ws, audio)
    ear = Ear()
    await ear.open()
    listener = asyncio.create_task(s.listen(ear))
    log.info("job %s open", s.job.id)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("bytes") is not None:
                ear.feed(msg["bytes"])
            elif msg.get("text"):
                m = json.loads(msg["text"])
                if m.get("type") == "photo":
                    s.photo = (base64.b64decode(m["jpeg"]), int(m["w"]), int(m["h"]), time.monotonic())
                elif m.get("type") == "finish":
                    asyncio.create_task(s.finish())
                elif m.get("type") == "text" and m.get("text", "").strip():
                    s.asked_at = time.monotonic()
                    asyncio.create_task(s.turn(m["text"].strip()))
            elif msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    finally:
        listener.cancel()
        await ear.close()
        await s.close()
        log.info("job %s closed: %d steps, %d stops", s.job.id, len(s.job.log), len(s.job.stops))

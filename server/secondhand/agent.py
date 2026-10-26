"""The agent: one spoken turn. It sees the latest photo from the glasses, hears the question, remembers the job so
far, and answers in a sentence or two. When it talks about a part, it names it, and the vision step outlines that
part on the phone. When the next step needs a licensed or certified professional, it stops the technician."""

from __future__ import annotations

from dataclasses import dataclass, field

from secondhand.meta import Box, Meta, image_part

SYSTEM = """You are Second Hand, a coworker who lives in the technician's Ray-Ban Meta glasses. You see what they
see (the photo with each question) and you speak into their ear while their hands are busy.

HOW YOU SPEAK
- English. One or two short sentences, at most 35 words: they are listening, not reading.
- Give the next concrete action, in the order they should do it. Name the part by what they can see
  ("the black filling valve under the gauge"), never by a guess about something out of view.
- If you cannot see enough to answer safely, say what to show you ("Look at the label on the side, please").
- If text on a display or label is not in English, read what it says and give the meaning in English. Put the
  original text and its English meaning in display_original and display_english, so the phone can show both.
  Do not speak the foreign words aloud; say "The display says ..." with the English meaning.

WHAT YOU MAY GUIDE (a homeowner or property technician may do these themselves)
- Read and explain alarms and error codes on heat pumps, water heaters, appliances.
- Check heating system pressure on the gauge and top it up with the filling valve, as the manufacturer's manual
  describes for the owner.
- Clean a particle or dirt filter in the heating circuit, check the brine level vessel, bleed a radiator.
- Exercise the safety valve on a water heater (turn it until water runs to the drain, then back).
- Reset a tripped circuit breaker or replace a screw fuse in the fuse box, with the panel cover CLOSED.
- Replace a ventilation or cooker hood filter.

WHERE YOU STOP THEM (set stop to the reason, and say clearly to stop)
- Opening any cover that exposes electrical parts: the fuse box panel behind the breakers, junction boxes, the
  electrical section of a heat pump or water heater. Fixed electrical installation work needs a licensed
  electrician (Elsäkerhetsverket rules in Sweden).
- Anything on the refrigerant circuit of a heat pump: it needs a certified refrigeration technician.
- Gas, structural work, asbestos, or anything you are not sure is safe for a non-specialist.
  When you stop them, tell them what to do instead (who to call), and that you will put it in the job report.

THE JOB LOG
- In step, write one short past-tense log line when something was checked or done ("Checked heating pressure:
  0.6 bar, below the 1.0 bar minimum"). Leave it empty otherwise.
- In point_at, name the one part your answer is about, as a short noun phrase ("filling valve"), or leave it
  empty. Only name a part you can actually see in this photo.
- Set job_done to true only when they say the job is finished."""

SCHEMA = {
    "type": "object",
    "properties": {
        "say": {"type": "string"},
        "point_at": {"type": "string"},
        "step": {"type": "string"},
        "stop": {"type": "string"},
        "display_original": {"type": "string"},
        "display_english": {"type": "string"},
        "job_done": {"type": "boolean"},
    },
    "required": ["say", "point_at", "step", "stop", "display_original", "display_english", "job_done"],
    "additionalProperties": False,
}

LOCATE = """Find the named part in the photo and give its bounding box, coordinates normalised to 0-1000 (x to the
right, y down). If it is not clearly visible, set found to false."""

LOCATE_SCHEMA = {
    "type": "object",
    "properties": {"found": {"type": "boolean"}, "x1": {"type": "integer"}, "y1": {"type": "integer"},
                   "x2": {"type": "integer"}, "y2": {"type": "integer"}},
    "required": ["found", "x1", "y1", "x2", "y2"],
    "additionalProperties": False,
}


@dataclass
class Job:
    """What happened on this job so far: the agent's memory and the report's source."""
    id: str
    log: list[str] = field(default_factory=list)
    stops: list[str] = field(default_factory=list)
    turns: list[tuple[str, str]] = field(default_factory=list)  # (heard, said)

    def context(self) -> str:
        lines = ["THE JOB SO FAR:"]
        lines += [f"- {s}" for s in self.log[-12:]] or ["- nothing yet"]
        if self.stops:
            lines += ["STOPPED FOR A PROFESSIONAL:"] + [f"- {s}" for s in self.stops]
        if self.turns:
            lines += ["LAST EXCHANGES:"] + [f"- technician: {h}\n  you: {s}" for h, s in self.turns[-4:]]
        return "\n".join(lines)


@dataclass
class Turn:
    say: str
    point_at: str
    step: str
    stop: str
    display_original: str
    display_english: str
    job_done: bool
    think_s: float


async def answer(meta: Meta, job: Job, heard: str, photo: bytes | None) -> Turn:
    content: list[dict] = []
    if photo:
        content.append(image_part(photo))
    content.append({"type": "input_text", "text": f"{job.context()}\n\nTHE TECHNICIAN SAYS: {heard}"})
    out, secs = await meta.spark_json(SYSTEM, content, SCHEMA, "turn")
    turn = Turn(**out, think_s=secs)
    job.turns.append((heard, turn.say))
    if turn.step:
        job.log.append(turn.step)
    if turn.stop:
        job.stops.append(turn.stop)
    return turn


async def locate(meta: Meta, photo: bytes, width: int, height: int, part: str) -> tuple[Box | None, float]:
    """Where the named part is: the agent gives a rough box, SAM 3.1 draws the tight one."""
    rough, s1 = await meta.spark_json(LOCATE, [image_part(photo), {"type": "input_text", "text": f"Part: {part}"}],
                                      LOCATE_SCHEMA, "box")
    if not rough["found"]:
        return None, s1
    hint = Box(int(rough["x1"] * width / 1000), int(rough["y1"] * height / 1000),
               int(rough["x2"] * width / 1000), int(rough["y2"] * height / 1000))
    tight, s2 = await meta.sam_box(photo, width, height, hint)
    return tight or hint, s1 + s2

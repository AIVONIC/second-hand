"""The job report and the invoice draft, written when the technician says the job is done.

Muse Spark Open (Meta's open-weight model, served by Baseten) writes the report from the job log: what was found,
what was done, what was handed to a professional and why, what to follow up, and which parts were used. The
numbers on the invoice are not the model's: labour time comes from the job's own clock and the rate from the
configuration, and part prices are left for the office to fill in, because nothing on the job told us what they
cost. If Baseten cannot be reached, Muse Spark on the Meta Model API writes the same report, and the PDF says
which model wrote it."""

from __future__ import annotations

import json
import logging
import math
import time
from dataclasses import dataclass, field
from datetime import datetime
from io import BytesIO
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from fpdf import FPDF

from secondhand.config import setting

log = logging.getLogger("secondhand.report")

OPEN_API = setting("OPEN_WEIGHTS_API_BASE", "https://inference.baseten.co/v1")
OPEN_MODEL = setting("OPEN_WEIGHTS_MODEL", "meta-models/muse-spark-open-1026")
REPORTS_DIR = Path(setting("SECONDHAND_REPORTS_DIR", str(Path(__file__).resolve().parents[1] / "reports")))
FONTS = Path(__file__).resolve().parent / "fonts"
TZ = ZoneInfo(setting("SECONDHAND_TZ", "Europe/Stockholm"))
COMPANY = setting("SECONDHAND_COMPANY", "Second Hand demo")
CURRENCY = setting("SECONDHAND_CURRENCY", "SEK")
HOURLY_RATE = float(setting("SECONDHAND_HOURLY_RATE", "650"))
VAT = float(setting("SECONDHAND_VAT", "0.25"))
MAX_PHOTOS = 6

WRITE = """You write the job report a property technician hands to the office after a job. Write it from the job
log and conversation below, in plain English, for an office manager who was not there.

- Report only what the log and conversation show. Never invent a reading, a part or a step.
- title: what the job was and how it ended, under 12 words ("Heat pump low-pressure alarm: topped up, running").
- site: where the job was, only if the technician said it; otherwise an empty string.
- summary: two or three sentences: the problem, what was done, the state it was left in.
- findings: readings and observations, with their values ("Heating pressure 0.6 bar, below the 1.0 bar minimum").
- work_done: the actions, in order, past tense.
- referrals: each thing stopped for a licensed or certified professional: the issue, and who should be called.
- follow_up: what someone should check or do next. Empty if nothing.
- parts: parts or materials actually used, with quantities. Empty if none were mentioned.
- photo_captions: one short caption for each photo listed, in the same order, describing the moment it shows."""

SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "site": {"type": "string"},
        "summary": {"type": "string"},
        "findings": {"type": "array", "items": {"type": "string"}},
        "work_done": {"type": "array", "items": {"type": "string"}},
        "referrals": {"type": "array", "items": {
            "type": "object",
            "properties": {"issue": {"type": "string"}, "who": {"type": "string"}},
            "required": ["issue", "who"], "additionalProperties": False}},
        "follow_up": {"type": "array", "items": {"type": "string"}},
        "parts": {"type": "array", "items": {
            "type": "object",
            "properties": {"name": {"type": "string"}, "quantity": {"type": "string"}},
            "required": ["name", "quantity"], "additionalProperties": False}},
        "photo_captions": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["title", "site", "summary", "findings", "work_done", "referrals", "follow_up", "parts",
                 "photo_captions"],
    "additionalProperties": False,
}


@dataclass
class Shot:
    """A photo worth keeping for the report: the first one, one at each logged step or stop, and the last."""
    jpeg: bytes
    at: float  # wall clock
    note: str


@dataclass
class Report:
    id: str
    written: dict
    started: float
    finished: float
    model: str
    seconds: float
    shots: list[Shot] = field(default_factory=list)

    @property
    def minutes(self) -> int:
        return max(1, math.ceil((self.finished - self.started) / 60))

    def invoice(self) -> dict:
        """The draft's numbers, all from the job's clock and the configuration: labour billed in started quarter
        hours (at least one), parts listed without a price."""
        quarters = max(1, math.ceil(self.minutes / 15))
        hours = quarters / 4
        labour = round(hours * HOURLY_RATE, 2)
        vat = round(labour * VAT, 2)
        return {
            "currency": CURRENCY,
            "lines": [{"text": f"Labour, {self.minutes} min on site (billed {hours:g} h)", "quantity": f"{hours:g} h",
                       "unit_price": HOURLY_RATE, "amount": labour}]
            + [{"text": p["name"], "quantity": p["quantity"], "unit_price": None, "amount": None}
               for p in self.written.get("parts", [])],
            "subtotal": labour,
            "vat_rate": VAT,
            "vat": vat,
            "total": round(labour + vat, 2),
            "parts_unpriced": bool(self.written.get("parts")),
        }

    def summary(self) -> dict:
        """What the phone shows (the PDF has the same, plus the photos)."""
        return {"id": self.id, **self.written, "minutes": self.minutes, "model": self.model,
                "seconds": round(self.seconds, 1), "invoice": self.invoice(),
                "date": datetime.fromtimestamp(self.started, TZ).strftime("%Y-%m-%d %H:%M")}


def pick_shots(shots: list[Shot]) -> list[Shot]:
    """At most six: the first (before), the last (after), and the latest step and stop photos between them."""
    if len(shots) <= MAX_PHOTOS:
        return shots
    return [shots[0], *shots[1:-1][-(MAX_PHOTOS - 2):], shots[-1]]


def job_text(job, shots: list[Shot]) -> str:
    lines = ["JOB LOG:"] + ([f"- {s}" for s in job.log] or ["- nothing logged"])
    if job.stops:
        lines += ["STOPPED FOR A PROFESSIONAL:"] + [f"- {s}" for s in job.stops]
    lines += ["CONVERSATION:"] + [f"- technician: {h}\n  agent: {s}" for h, s in job.turns]
    lines += ["PHOTOS, IN ORDER:"] + [f"{i + 1}. taken at the moment: {s.note}" for i, s in enumerate(shots)]
    return "\n".join(lines)


async def _chat(http: httpx.AsyncClient, base: str, key_header: dict, model: str, text: str, extra: dict) -> dict:
    body = {
        "model": model,
        "max_tokens": 8000,  # reasoning counts against it; a smaller budget can return no content at all
        "messages": [{"role": "system", "content": WRITE}, {"role": "user", "content": text}],
        "response_format": {"type": "json_schema", "json_schema": {"name": "report", "strict": True, "schema": SCHEMA}},
        **extra,
    }
    r = await http.post(f"{base}/chat/completions", headers={**key_header, "Content-Type": "application/json"},
                        json=body)
    r.raise_for_status()
    content = r.json()["choices"][0]["message"]["content"]
    if not content:
        raise ValueError("the model returned no content")
    return json.loads(content)


async def write(http: httpx.AsyncClient, meta, job, shots: list[Shot], started: float) -> Report:
    """Write the report: Muse Spark Open first, Muse Spark if that fails."""
    shots = pick_shots(shots)
    text = job_text(job, shots)
    t0 = time.perf_counter()
    key = setting("BASETEN_API_KEY")
    written, model = None, ""
    if key:
        try:
            written = await _chat(http, OPEN_API, {"Authorization": f"Api-Key {key}"}, OPEN_MODEL, text,
                                  {"reasoning_effort": "low"})
            model = "Muse Spark Open (open weights, on Baseten)"
        except (httpx.HTTPError, ValueError, KeyError) as e:
            log.error("job %s: Muse Spark Open failed (%s); writing the report with Muse Spark", job.id, e)
    else:
        log.error("job %s: BASETEN_API_KEY is not set; writing the report with Muse Spark", job.id)
    if written is None:
        written, _ = await meta.spark_json(WRITE, [{"type": "input_text", "text": text}], SCHEMA, "report", "low")
        model = "Muse Spark (Meta Model API)"
    return Report(id=job.id, written=written, started=started, finished=time.time(), model=model,
                  seconds=time.perf_counter() - t0, shots=shots)


# ---- the PDF ------------------------------------------------------------------------------------------------

AMBER = (242, 169, 0)
INK = (26, 26, 26)
GREY = (110, 110, 110)
RED = (198, 40, 40)


class _Pdf(FPDF):
    def __init__(self, footer: str) -> None:
        super().__init__(format="A4")
        self.add_font("Sans", "", str(FONTS / "DejaVuSans.ttf"))
        self.add_font("Sans", "B", str(FONTS / "DejaVuSans-Bold.ttf"))
        self.set_auto_page_break(True, margin=18)
        self.set_margins(16, 16, 16)
        self._footer = footer

    def footer(self) -> None:
        self.set_y(-12)
        self.set_font("Sans", "", 7.5)
        self.set_text_color(*GREY)
        self.cell(0, 5, f"{self._footer}    Page {self.page_no()}/{{nb}}", align="C")

    def band(self, title: str, right: str) -> None:
        self.set_fill_color(*AMBER)
        self.rect(0, 0, self.w, 24, style="F")
        self.set_xy(16, 7)
        self.set_text_color(*INK)
        self.set_font("Sans", "B", 15)
        self.cell(110, 10, title)
        self.set_font("Sans", "", 9)
        self.set_xy(self.w - 16 - 80, 7)
        self.multi_cell(80, 4.6, right, align="R")
        self.set_y(32)

    def heading(self, text: str, color=INK) -> None:
        self.ln(3)
        self.set_font("Sans", "B", 11)
        self.set_text_color(*color)
        self.cell(0, 7, text, new_x="LMARGIN", new_y="NEXT")

    def para(self, text: str, size: float = 10) -> None:
        self.set_font("Sans", "", size)
        self.set_text_color(*INK)
        self.multi_cell(0, 5.2, text, new_x="LMARGIN", new_y="NEXT")

    def bullets(self, items: list[str]) -> None:
        self.set_font("Sans", "", 10)
        self.set_text_color(*INK)
        for item in items:
            self.set_x(self.l_margin)
            self.cell(5, 5.2, "•")
            self.multi_cell(0, 5.2, item, new_x="LMARGIN", new_y="NEXT")


def _money(v: float) -> str:
    return f"{v:,.2f}".replace(",", " ")


def render(report: Report) -> bytes:
    w = report.written
    s = report.summary()
    started = datetime.fromtimestamp(report.started, TZ)
    pdf = _Pdf(f"Written by {report.model} from the Second Hand job log. Job {report.id}.")
    pdf.alias_nb_pages()

    pdf.add_page()
    pdf.band("Job report", f"{started:%Y-%m-%d %H:%M}\nJob {report.id}\n{report.minutes} min on site")
    pdf.set_font("Sans", "B", 16)
    pdf.set_text_color(*INK)
    pdf.multi_cell(0, 8, w["title"], new_x="LMARGIN", new_y="NEXT")
    if w["site"]:
        pdf.set_font("Sans", "", 10)
        pdf.set_text_color(*GREY)
        pdf.cell(0, 6, w["site"], new_x="LMARGIN", new_y="NEXT")
    pdf.ln(2)
    pdf.para(w["summary"], 10.5)

    if w["referrals"]:
        pdf.ln(3)
        pdf.set_fill_color(253, 236, 234)
        pdf.set_draw_color(*RED)
        pdf.heading("Stopped for a professional", RED)
        for r in w["referrals"]:
            pdf.set_font("Sans", "B", 10)
            pdf.set_text_color(*INK)
            pdf.multi_cell(0, 5.2, r["issue"], new_x="LMARGIN", new_y="NEXT")
            pdf.set_font("Sans", "", 10)
            pdf.multi_cell(0, 5.2, f"Call: {r['who']}", new_x="LMARGIN", new_y="NEXT")
            pdf.ln(1)
    for title, items in (("Findings", w["findings"]), ("Work done", w["work_done"]), ("Follow-up", w["follow_up"])):
        if items:
            pdf.heading(title)
            pdf.bullets(items)
    if w["parts"]:
        pdf.heading("Parts and materials used")
        pdf.bullets([f"{p['name']} ({p['quantity']})" for p in w["parts"]])

    if report.shots:
        pdf.heading("Photos from the glasses")
        captions = w.get("photo_captions") or []
        col_w = (pdf.w - pdf.l_margin - pdf.r_margin - 6) / 2
        img_h = col_w * 3 / 4
        row_h = img_h + 13
        pdf.set_auto_page_break(False)  # the grid places each row itself
        for i, shot in enumerate(report.shots):
            if i % 2 == 0:
                if pdf.get_y() + row_h > pdf.h - 18:
                    pdf.add_page()
                row_y = pdf.get_y()
            x = pdf.l_margin + (i % 2) * (col_w + 6)
            pdf.image(BytesIO(shot.jpeg), x=x, y=row_y, w=col_w, h=img_h, keep_aspect_ratio=True)
            pdf.set_xy(x, row_y + img_h + 1.5)
            pdf.set_font("Sans", "", 8)
            pdf.set_text_color(*GREY)
            caption = captions[i] if i < len(captions) and captions[i] else shot.note
            pdf.multi_cell(col_w, 4, f"{datetime.fromtimestamp(shot.at, TZ):%H:%M}  {caption}", max_line_height=4)
            if i % 2 == 1 or i == len(report.shots) - 1:
                pdf.set_xy(pdf.l_margin, row_y + row_h)
        pdf.set_auto_page_break(True, margin=18)

    inv = s["invoice"]
    pdf.add_page()
    pdf.band("Invoice draft", f"{started:%Y-%m-%d}\nJob {report.id}\nNot a tax invoice")
    pdf.set_font("Sans", "B", 10)
    pdf.set_text_color(*INK)
    pdf.cell(90, 6, "From")
    pdf.cell(0, 6, "To", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Sans", "", 10)
    pdf.cell(90, 6, COMPANY)
    pdf.cell(0, 6, w["site"] or "Customer: to be completed by the office", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(4)
    pdf.para(f"Work: {w['title']}")
    pdf.ln(3)

    widths = (92, 26, 30, 30)
    pdf.set_fill_color(*AMBER)
    pdf.set_font("Sans", "B", 9.5)
    for text, width, align in zip(("Description", "Quantity", f"Unit ({inv['currency']})",
                                   f"Amount ({inv['currency']})"), widths, "LRRR"):
        pdf.cell(width, 8, text, fill=True, align=align)
    pdf.ln()
    pdf.set_font("Sans", "", 9.5)
    for line in inv["lines"]:
        unit = _money(line["unit_price"]) if line["unit_price"] is not None else "to add"
        amount = _money(line["amount"]) if line["amount"] is not None else "to add"
        for text, width, align in zip((line["text"], line["quantity"], unit, amount), widths, "LRRR"):
            pdf.cell(width, 7, text, border="B", align=align)
        pdf.ln()
    pdf.ln(2)
    for label, value, bold in (("Subtotal (labour)", inv["subtotal"], False),
                               (f"VAT {inv['vat_rate'] * 100:g}%", inv["vat"], False),
                               ("Total", inv["total"], True)):
        pdf.set_font("Sans", "B" if bold else "", 10.5 if bold else 9.5)
        pdf.cell(sum(widths[:3]), 7, label, align="R")
        pdf.cell(widths[3], 7, f"{_money(value)} {inv['currency']}", align="R", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(6)
    note = ("Labour is the time on site, from the start of the job to \"job done\", billed in started quarter hours "
            f"at {_money(HOURLY_RATE)} {inv['currency']} an hour.")
    if inv["parts_unpriced"]:
        note += " Part prices were not known on site; add them before sending, and the totals will change."
    pdf.set_text_color(*GREY)
    pdf.para(note, 8.5)
    return bytes(pdf.output())


def save(report: Report, pdf: bytes) -> Path:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    path = REPORTS_DIR / f"{report.id}.pdf"
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(pdf)
    tmp.replace(path)
    (REPORTS_DIR / f"{report.id}.json").write_text(json.dumps(report.summary(), indent=1))
    return path

"""Meta Model API: one client for Muse Spark (Responses API, structured output) and SAM 3.1 (streamed geometry)."""

from __future__ import annotations

import base64
import json
import re
import time
from dataclasses import dataclass

import httpx

from secondhand.config import META_API, SAM_MODEL, SPARK_MODEL, required


def image_part(jpeg: bytes) -> dict:
    return {"type": "input_image", "image_url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}


@dataclass
class Box:
    x1: int
    y1: int
    x2: int
    y2: int

    def as_list(self) -> list[int]:
        return [self.x1, self.y1, self.x2, self.y2]


BOX_RE = re.compile(r"<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+)")


class Meta:
    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        self.http = client or httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0))
        self.headers = {"Authorization": f"Bearer {required('MODEL_API_KEY')}", "Content-Type": "application/json"}

    async def aclose(self) -> None:
        await self.http.aclose()

    async def spark_json(self, system: str, content: list[dict], schema: dict, name: str,
                         effort: str = "minimal") -> tuple[dict, float]:
        """One Muse Spark call constrained to a JSON schema. Returns (object, seconds)."""
        body = {
            "model": SPARK_MODEL,
            "max_output_tokens": 4000,  # reasoning tokens count against it; below ~4000 replies can come back empty
            "reasoning": {"effort": effort},
            "instructions": system,
            "text": {"format": {"type": "json_schema", "name": name, "schema": schema, "strict": True}},
            "input": [{"role": "user", "content": content}],
        }
        t0 = time.perf_counter()
        r = await self.http.post(f"{META_API}/responses", headers=self.headers, json=body)
        r.raise_for_status()
        data = r.json()
        text = "".join(c.get("text", "") for o in data.get("output", []) for c in (o.get("content") or [])
                       if isinstance(c, dict))
        return json.loads(text), time.perf_counter() - t0

    async def sam_box(self, jpeg: bytes, width: int, height: int, hint: Box) -> tuple[Box | None, float]:
        """Tighten a rough box around one object with SAM 3.1 (a box prompt). SAM finds small metal fittings
        poorly from a noun phrase alone (0 matches for "radiator valve" in our spike), so the agent locates the
        part and SAM draws its exact outline."""
        prompt = f"<0f>1<|box;x1={hint.x1};y1={hint.y1};x2={hint.x2};y2={hint.y2};w={width};h={height}|>"
        body = {"model": SAM_MODEL, "stream": True, "input": [{"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": prompt}, image_part(jpeg)]}]}
        t0 = time.perf_counter()
        text = ""
        async with self.http.stream("POST", f"{META_API}/responses", headers=self.headers, json=body) as r:
            r.raise_for_status()
            async for line in r.aiter_lines():
                if not line.startswith("data:") or line.strip() == "data: [DONE]":
                    continue
                try:
                    event = json.loads(line[5:])
                except json.JSONDecodeError:
                    continue
                if event.get("type") == "response.output_text.delta":
                    text += event.get("delta", "")
        m = BOX_RE.search(text)
        return (Box(*(int(v) for v in m.groups())) if m else None), time.perf_counter() - t0

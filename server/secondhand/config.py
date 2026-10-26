"""Settings from the environment. Nothing secret is ever written in this repository: keys come from the
environment or a local .env file that git ignores."""

from __future__ import annotations

import os
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parents[1] / ".env"


def setting(name: str, default: str | None = None) -> str | None:
    if os.environ.get(name):
        return os.environ[name]
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            if line.startswith(f"{name}="):
                return line.split("=", 1)[1].strip()
    return default


def required(name: str) -> str:
    value = setting(name)
    if not value:
        raise RuntimeError(f"{name} is not set (environment or server/.env)")
    return value


META_API = setting("META_API_BASE", "https://api.meta.ai/v1")
SPARK_MODEL = setting("SPARK_MODEL", "muse-spark-1.3")  # standard mode: our customers' photos never train a model
SAM_MODEL = setting("SAM_MODEL", "sam-3.1")

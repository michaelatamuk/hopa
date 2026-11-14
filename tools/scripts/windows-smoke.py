#!/usr/bin/env python3
"""Smoke the Windows client: one turn, the list, settings, drafts, a screenshot.

Local-only: it needs a Windows desktop with the sidecar built. The CI jobs do
not run it.

    python tools/scripts/windows-smoke.py
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXE = ROOT / "apps" / "windows" / "Hopa.Windows" / "bin" / "Debug" / "net8.0-windows" / "Hopa.Windows.exe"
SHOT = ROOT / "docs" / "media" / "windows-w1.png"


def _run(*args: str, timeout: int = 180) -> None:
    subprocess.run([str(EXE), *args], check=True, timeout=timeout)


def run(*args: str, timeout: int = 180) -> str:
    out = Path(tempfile.gettempdir()) / f"hopa-windows-{uuid.uuid4().hex}.txt"
    _run(*args, "--out", str(out), timeout=timeout)
    text = out.read_text(encoding="utf-8")
    out.unlink(missing_ok=True)
    return text


def run_check(flag: str, timeout: int = 120) -> str:
    out = Path(tempfile.gettempdir()) / f"hopa-windows-{uuid.uuid4().hex}.txt"
    _run(flag, str(out), timeout=timeout)
    text = out.read_text(encoding="utf-8")
    out.unlink(missing_ok=True)
    return text


def main() -> int:
    if not EXE.exists():
        print(f"missing {EXE}; run: dotnet build -c Debug")
        return 2

    goal = f"windows smoke {uuid.uuid4().hex[:6]}"
    turn = run("--turn", goal, "--shot", str(SHOT))
    if "HOPA:" not in turn or "Sidecar answered" not in turn:
        print("turn failed:\n" + turn)
        return 1

    listed = run("--list")
    if "|" not in listed:
        print("the conversation list is empty:\n" + listed)
        return 1

    settings = run_check("--settings-check")
    if "settings round-trip: ok" not in settings or "credential round-trip: ok" not in settings:
        print("settings failed:\n" + settings)
        return 1

    history = run_check("--history-check")
    if "pin: True" not in history or "delete: True" not in history:
        print("history failed:\n" + history)
        return 1

    drafts = run_check("--draft-check")
    if "draft round-trip: ok" not in drafts:
        print("drafts failed:\n" + drafts)
        return 1

    print("turn: ok")
    print("list: ok")
    print("settings: ok")
    print("history: ok")
    print("drafts: ok")
    print(f"screenshot: {SHOT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())


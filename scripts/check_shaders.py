#!/usr/bin/env python3
"""Compile every shader in pipeline/src/main/assets/shaders with glslangValidator.

Shaders share code through `#include "file"` lines. The app inlines them when it loads a shader
(ShaderSource.load); this script does the same before validating, so CI
checks exactly what the phone compiles.

    python3 -I scripts/check_shaders.py
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

DIR = Path(__file__).resolve().parent.parent / "pipeline/src/main/assets/shaders"
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"\s*$')
EXTENSION = re.compile(r"^\s*#extension\s+GL_GOOGLE_include_directive\b.*$")


def expand(path: Path) -> str:
    out = []
    for line in path.read_text().splitlines():
        if EXTENSION.match(line):
            out.append("")
            continue
        m = INCLUDE.match(line)
        out.append(expand(DIR / m.group(1)) if m else line)
    return "\n".join(out) + "\n"


def main() -> int:
    status = 0
    stages = [p for p in sorted(DIR.iterdir()) if p.suffix in (".comp", ".vert", ".frag")]
    with tempfile.TemporaryDirectory() as tmp:
        for path in stages:
            target = Path(tmp) / path.name
            target.write_text(expand(path))
            res = subprocess.run(["glslangValidator", str(target)], capture_output=True, text=True)
            text = (res.stdout + res.stderr).strip()
            print(f"{path.name}: {'OK' if res.returncode == 0 and 'ERROR' not in text else 'FAILED'}")
            if res.returncode != 0 or "ERROR" in text:
                print(text)
                status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())

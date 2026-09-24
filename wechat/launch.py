#!/usr/bin/env python3
"""Compatibility launcher: start structured log apps; never build/run OCR."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parents[1]/'logbook/launch.py'),run_name='__main__')

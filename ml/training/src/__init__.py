# src/__init__.py
# Makes src/ a Python package so `python -m src.pipeline` works correctly.
import sys
from pathlib import Path

_SRC_DIR = Path(__file__).resolve().parent
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

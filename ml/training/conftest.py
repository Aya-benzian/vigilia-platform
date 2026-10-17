# conftest.py — pytest configuration for Member 1 training tests
#
# Adds both ml/training/ and ml/training/src/ to sys.path so that:
#   - test files can do: from src.select_model import ...
#   - src modules can import each other with bare names: from config import ...

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent       # ml/training/
SRC  = ROOT / "src"                          # ml/training/src/

# Insert both so both import styles work
sys.path.insert(0, str(SRC))
sys.path.insert(0, str(ROOT))

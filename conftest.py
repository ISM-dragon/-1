"""Repository-wide test bootstrap.

The pipeline is intentionally kept as a separate Python package below
`pipeline/`; this makes root-level Gateway and pipeline test commands resolve
both packages without requiring contributors to export PYTHONPATH manually.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PIPELINE = ROOT / "pipeline"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(PIPELINE) not in sys.path:
    sys.path.insert(0, str(PIPELINE))

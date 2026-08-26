from __future__ import annotations

import resource
import subprocess
import sys
import time

args = [sys.executable, "-m", "pytest", *sys.argv[1:]]
started = time.monotonic()
completed = subprocess.run(args)
usage = resource.getrusage(resource.RUSAGE_CHILDREN)
print(f"elapsed={time.monotonic() - started:.2f}s max_rss_kb={usage.ru_maxrss}")
raise SystemExit(completed.returncode)

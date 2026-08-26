from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> None:
    before = json.loads(Path(sys.argv[1]).read_text())
    after = json.loads(Path(sys.argv[2]).read_text())
    old = {(Path(r["media"]).name, r["case"]): r for r in before["results"]}
    new = {(Path(r["media"]).name, r["case"]): r for r in after["results"]}
    rows = []
    for key, left in old.items():
        right = new[key]
        rows.append({
            "video": key[0],
            "case": key[1],
            "before_wall_sec": left.get("wall_sec"),
            "after_wall_sec": right.get("wall_sec"),
            "wall_delta_pct": round((right["wall_sec"] / left["wall_sec"] - 1) * 100, 1),
            "before_cpu_sec": round(left.get("user_sec", 0) + left.get("sys_sec", 0), 4),
            "after_cpu_sec": round(right.get("user_sec", 0) + right.get("sys_sec", 0), 4),
            "cpu_delta_pct": round(((right.get("user_sec", 0) + right.get("sys_sec", 0)) / (left.get("user_sec", 0) + left.get("sys_sec", 0)) - 1) * 100, 1),
            "before_max_rss_mb": round(left.get("max_rss_kb", 0) / 1024, 2),
            "after_max_rss_mb": round(right.get("max_rss_kb", 0) / 1024, 2),
            "rss_delta_pct": round((right.get("max_rss_kb", 0) / left.get("max_rss_kb", 1) - 1) * 100, 1),
            "before_fs_outputs": left.get("fs_outputs"),
            "after_fs_outputs": right.get("fs_outputs"),
            "output_ok": right.get("output", {}).get("ok", True),
            "frame_count": right.get("frame_count"),
            "output_bytes": right.get("output_bytes"),
        })
    payload = {"before_label": before["label"], "after_label": after["label"], "rows": rows}
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()

from __future__ import annotations

import importlib
import os
import platform
import shutil
import subprocess


def main() -> None:
    print(f"platform={platform.platform()}")
    print(f"cpu_count={os.cpu_count()}")
    print(f"ffmpeg={shutil.which('ffmpeg')}")
    if shutil.which("ffmpeg"):
        print(subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True, check=False).stdout.splitlines()[0])
    print(f"nvidia_smi={shutil.which('nvidia-smi')}")
    for name in ("numpy", "psutil", "cv2", "onnxruntime", "torch", "librosa"):
        try:
            module = importlib.import_module(name)
            print(f"{name}={getattr(module, '__version__', 'ok')}")
        except Exception as exc:
            print(f"{name}=UNAVAILABLE:{type(exc).__name__}:{str(exc)[:120]}")


if __name__ == "__main__":
    main()

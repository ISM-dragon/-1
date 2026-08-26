"""Hardware discovery and conservative runtime profiles.

The module is intentionally dependency-light: it reads Linux procfs when
available and treats optional GPU tooling as best-effort diagnostics. It does
not import torch, CUDA, or any model package during discovery.
"""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class HardwareInfo:
    os: str
    arch: str
    cpu_count: int
    cpu_threads: int
    ram_bytes: int | None
    gpu_available: bool
    gpu_name: str | None
    vram_bytes: int | None
    disk_free_bytes: int | None
    disk_total_bytes: int | None
    profile: str

    def to_dict(self) -> dict:
        return asdict(self)


def _ram_bytes() -> int | None:
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def _nvidia_info() -> tuple[bool, str | None, int | None]:
    try:
        proc = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=name,memory.total",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False, None, None
    if proc.returncode != 0 or not proc.stdout.strip():
        return False, None, None
    first = proc.stdout.splitlines()[0].split(",", 1)
    name = first[0].strip() or "NVIDIA GPU"
    try:
        vram = int(float(first[1].strip())) * 1024 * 1024 if len(first) > 1 else None
    except ValueError:
        vram = None
    return True, name, vram


def _profile(cpu_threads: int, ram: int | None, gpu: bool, vram: int | None) -> str:
    ram_gb = (ram or 0) / (1024**3)
    vram_gb = (vram or 0) / (1024**3)
    if gpu and vram_gb >= 10 and ram_gb >= 16:
        return "gpu-large"
    if gpu and vram_gb >= 4:
        return "gpu-standard"
    if cpu_threads >= 8 and ram_gb >= 16:
        return "cpu-standard"
    return "cpu-small"


def inspect_resources(path: str | Path | None = None) -> HardwareInfo:
    """Return observable host resources without failing if probes are absent."""
    gpu, gpu_name, vram = _nvidia_info()
    disk_path = Path(path or os.environ.get("PUBLIKCLIP_HOME", str(Path.home())))
    try:
        usage = shutil.disk_usage(disk_path)
        disk_free, disk_total = usage.free, usage.total
    except OSError:
        disk_free = disk_total = None
    threads = os.cpu_count() or 1
    return HardwareInfo(
        os=platform.system().lower(),
        arch=platform.machine().lower(),
        cpu_count=max(1, threads),
        cpu_threads=max(1, threads),
        ram_bytes=_ram_bytes(),
        gpu_available=gpu,
        gpu_name=gpu_name,
        vram_bytes=vram,
        disk_free_bytes=disk_free,
        disk_total_bytes=disk_total,
        profile=_profile(threads, _ram_bytes(), gpu, vram),
    )


PROFILES: dict[str, dict] = {
    "cpu-small": {
        "description": "CPU-only fallback for development or constrained hosts.",
        "device": "cpu",
        "max_parallel_models": 1,
        "recommended_asr_compute": "int8",
    },
    "cpu-standard": {
        "description": "CPU host suitable for one active analysis job.",
        "device": "cpu",
        "max_parallel_models": 1,
        "recommended_asr_compute": "int8",
    },
    "gpu-standard": {
        "description": "CUDA host with enough VRAM for a faster ASR path.",
        "device": "cuda",
        "max_parallel_models": 1,
        "recommended_asr_compute": "float16",
    },
    "gpu-large": {
        "description": "CUDA host with large VRAM; still keep model loads staged.",
        "device": "cuda",
        "max_parallel_models": 2,
        "recommended_asr_compute": "float16",
    },
}

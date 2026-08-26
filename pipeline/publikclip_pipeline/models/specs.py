"""Canonical inventory of local and externally cached model artifacts.

A ``None`` checksum means the upstream artifact is not pinned in this
repository yet; ModelManager reports that honestly as ``checksum_pinned=false``
and never labels it cryptographically verified.
"""

from .registry import ModelSpec, register


WHISPERX_ASR = register(
    ModelSpec(
        name="whisperx-asr",
        filename="large-v3-turbo",
        url="",
        version="large-v3-turbo",
        approx_mb=1600,
        source="Hugging Face cache used by faster-whisper/WhisperX",
        hardware={"device": "cpu-or-cuda", "ram_gb_min": 8, "vram_gb_recommended": 6},
        relative_path="hf/whisper-large-v3-turbo",
        managed=False,
    )
)

SILERO_VAD = register(
    ModelSpec(
        name="silero-vad",
        filename="silero-vad",
        url="",
        version="WhisperX bundled Silero VAD",
        approx_mb=2,
        source="WhisperX/PyTorch hub cache",
        hardware={"device": "cpu-or-cuda", "ram_gb_min": 2},
        relative_path="hf/silero-vad",
        managed=False,
    )
)

ALIGNMENT = register(
    ModelSpec(
        name="whisperx-alignment",
        filename="alignment-model",
        url="",
        version="language-dependent WhisperX alignment",
        approx_mb=500,
        source="Hugging Face cache loaded by whisperx.load_align_model",
        hardware={"device": "cpu-or-cuda", "ram_gb_min": 4},
        relative_path="hf/alignment",
        managed=False,
    )
)

LAUGHTER = register(
    ModelSpec(
        name="laughter-jrgillick",
        filename="best.pth.tar",
        url=(
            "https://github.com/jrgillick/laughter-detection/raw/master/"
            "checkpoints/in_use/resnet_with_augmentation/best.pth.tar"
        ),
        version="jrgillick-resnet-with-augmentation",
        approx_mb=10,
        source="GitHub jrgillick/laughter-detection",
        hardware={"device": "cpu", "ram_gb_min": 2},
    )
)

PANNS_CNN14_MAX = register(
    ModelSpec(
        name="panns-cnn14-decisionlevelmax",
        filename="Cnn14_DecisionLevelMax.pth",
        url=(
            "https://zenodo.org/record/3987831/files/"
            "Cnn14_DecisionLevelMax_mAP%3D0.385.pth?download=1"
        ),
        version="Cnn14 DecisionLevelMax mAP=0.385",
        approx_mb=466,
        source="Zenodo AudioSet tagging checkpoint",
        hardware={"device": "cpu-or-cuda", "ram_gb_min": 4, "vram_gb_recommended": 2},
    )
)

CAMPPLUS = register(
    ModelSpec(
        name="campplus",
        filename="campplus_cn_common.bin",
        url="https://huggingface.co/funasr/campplus/resolve/main/campplus_cn_common.bin",
        version="campplus_cn_common",
        approx_mb=28,
        source="Hugging Face funasr/campplus",
        hardware={"device": "cpu", "ram_gb_min": 2},
    )
)

SER_IEMOCAP = register(
    ModelSpec(
        name="speechbrain-ser-iemocap",
        filename="speechbrain-emotion-recognition-wav2vec2-IEMOCAP",
        url="",
        version="speechbrain/emotion-recognition-wav2vec2-IEMOCAP",
        approx_mb=400,
        source="Hugging Face SpeechBrain cache",
        hardware={"device": "cpu", "ram_gb_min": 4},
        relative_path="ser",
        managed=False,
    )
)

ULTRAFACE = register(
    ModelSpec(
        name="ultraface",
        filename="ultraface-rfb-320.onnx",
        url=(
            "https://github.com/JeremySNR/clip-forge/raw/main/resources/models/"
            "ultraface-rfb-320.onnx"
        ),
        version="RFB-320 ONNX",
        approx_mb=2,
        source="GitHub JeremySNR/clip-forge resources",
        hardware={"device": "cpu", "ram_gb_min": 1},
    )
)

LR_ASD_FRONTEND = register(
    ModelSpec(
        name="lr-asd",
        filename="frontend.onnx",
        url="https://github.com/JeremySNR/clip-forge/raw/main/resources/models/lr-asd-frontend.onnx",
        version="LR-ASD frontend ONNX",
        approx_mb=3,
        source="GitHub JeremySNR/clip-forge resources",
        hardware={"device": "cpu", "ram_gb_min": 1},
    )
)

LR_ASD_BACKEND = register(
    ModelSpec(
        name="lr-asd",
        filename="backend.onnx",
        url="https://github.com/JeremySNR/clip-forge/raw/main/resources/models/lr-asd-backend.onnx",
        version="LR-ASD backend ONNX",
        approx_mb=1,
        source="GitHub JeremySNR/clip-forge resources",
        hardware={"device": "cpu", "ram_gb_min": 1},
    )
)

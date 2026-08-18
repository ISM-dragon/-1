# Vendored code provenance

publikclip is AGPL-3.0-or-later. It adapts code, models, and fonts from the
following projects. Vendored files carry an attribution header pointing back
here. "Adapted" = algorithm/constants faithfully ported into our structures;
"Vendored" = file taken near-verbatim.

## Code

| Upstream | License | Where | What |
|---|---|---|---|
| [JeremySNR/clip-forge](https://github.com/JeremySNR/clip-forge) | MIT | `pipeline/.../ingest/ytdlp.py` | Adapted: managed yt-dlp binary, self-update-retry, error classification, playlist resolution, format selection, progress parsing |
| JeremySNR/clip-forge | MIT | `pipeline/.../camera/detect.py`, `camera/tracks.py`, `camera/asd.py` | Adapted: UltraFace pre/post (0.65 conf, IOU-0.5 NMS), sampled cut detector, IoU face tracks w/ interpolation + median smoothing, the full LR-ASD preprocessing geometry (0.7 crop / 0.2 down-shift / pad 110 / 112² / 4 MFCC rows per frame) and multi-duration backend ensemble |
| [fralapo/clippyme](https://github.com/fralapo/clippyme) | MIT | `pipeline/.../vendor/clippyme/reframe_ops.py` | Vendored: per-scene Savitzky-Golay/Kalman-RTS/L2 crop-path smoothing, AutoFlip stationary lock, zoom lock, hysteresis pan smoother |
| [mutonby/openshorts](https://github.com/mutonby/openshorts) | MIT (cloud/ excluded — proprietary, not taken) | `pipeline/.../render/renderer.py`, `camera/director.py` | Adapted: sendcmd change-point command architecture, crop-box rounding, punch-in envelope (0.25/1.3/0.55 s, 1.12×), encoder tiering, metadata scrub |
| [m-bain/whisperX](https://github.com/m-bain/whisperX) | BSD-2-Clause | `pipeline/.../asr/stage.py`, `diarize/cluster.py` | Dependency (pinned 3.8.6) + adapted word-speaker assignment (largest-intersection, midpoint fallback) |
| [NaufalRizqullah/opensource-clipping](https://github.com/NaufalRizqullah/opensource-clipping) | MIT | `pipeline/.../camera/director.py` | Adapted: two-pass canonical-position diarization fusion |
| [FujiwaraChoki/supoclip](https://github.com/FujiwaraChoki/supoclip) | AGPL-3.0 | `pipeline/.../captions/ass.py` | Approach: per-word Dialogue redraw (never `\k`), style presets concept |
| [openclaw-easy/ViralMint](https://github.com/openclaw-easy/ViralMint) | AGPL-3.0 | `pipeline/.../captions/ass.py` | Adapted: punctuation/pause/budget chunking rule |
| [jrgillick/laughter-detection](https://github.com/jrgillick/laughter-detection) | MIT | `pipeline/.../vendor/laughter/` | Vendored: ResNetBigger + exact inference recipe (8 kHz mel hop 186, 44-frame windows, lowpass + threshold segmentation) |
| [qiuqiangkong/audioset_tagging_cnn](https://github.com/qiuqiangkong/audioset_tagging_cnn) | MIT | `pipeline/.../vendor/panns/` | Vendored: Cnn14_DecisionLevelMax inference subset + AudioSet labels |
| [modelscope/3D-Speaker](https://github.com/modelscope/3D-Speaker) | Apache-2.0 | `pipeline/.../vendor/campplus/` | Vendored: CAM++ model definition (DTDNN + layers) |
| [artbyjazi/autoclip](https://github.com/artbyjazi/autoclip) | MIT | `pipeline/.../candidates/windows.py`, `events/post.py` | Adapted: IOU span dedupe pattern |

## Model weights (downloaded at runtime, never redistributed by us)

| Weights | License | Source |
|---|---|---|
| Whisper large-v3-turbo (CT2) | MIT | HuggingFace via faster-whisper |
| wav2vec2 alignment (EN) | Apache-2.0 | torchaudio/HF via whisperX |
| Silero VAD | MIT | via whisperX `vad_method="silero"` |
| CAM++ (`campplus_cn_common.bin`) | Apache-2.0 | huggingface.co/funasr/campplus (ungated — verified 2026-08-10) |
| PANNs Cnn14_DecisionLevelMax | MIT (checkpoint per repo) | zenodo.org/record/3987831 |
| jrgillick laughter checkpoint | MIT (in-repo) | github.com/jrgillick/laughter-detection |
| LR-ASD frontend/backend ONNX | MIT | clip-forge's parity-proven exports of Junhua-Liao/LR-ASD |
| UltraFace RFB-320 ONNX | MIT | Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB via clip-forge |
| speechbrain SER (wav2vec2-IEMOCAP) | Apache-2.0 code/weights | huggingface.co/speechbrain |
| ffmpeg static (optional fetch) | GPL build | ffmpeg.martin-riedl.de |

## Fonts (bundled, SIL OFL 1.1)

Anton, Archivo Black, Inter (captions); Public Sans, Martian Mono,
Archivo Black (app UI). OFL text in `pipeline/.../captions/fonts/`.

## Deliberately NOT used (license or quality)

- supoclip THEBOLDFONT.ttf — unresolved licensing
- Anil-matcha/AI-Youtube-Shorts-Generator — no license file
- Remotion — company license terms
- NRC-VAD lexicon — research-only (swapped for a built-in word list)
- omine-me/LaughterSegmentation — research-only weights
- audeering wav2vec2-msp-dim — CC-BY-NC-SA
- emotion2vec — no license file
- openshorts `cloud/` — source-available commercial, and SaaS plumbing we don't want

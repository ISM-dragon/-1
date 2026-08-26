# On-device AI upstream notes

## whisper.cpp

Source: https://github.com/ggml-org/whisper.cpp

The upstream project supports Android and exposes a C-style API. Its README documents a CMake build and explains that word-level timestamps are experimental and can be obtained with `--max-len 1` (`-ml 1`). The project uses ggml-format Whisper models, downloadable from the `ggml-org/whisper.cpp` model collection.

## YAMNet

Source: https://www.tensorflow.org/hub/tutorials/yamnet

The official TensorFlow tutorial describes YAMNet as a 521-class AudioSet classifier. Input is expected to be a mono WAV waveform at 16 kHz, normalized to approximately [-1, 1]. The model returns per-frame class scores; downstream code can aggregate scores across frames and map selected AudioSet labels to application-level categories.

## Implementation decision

The Android engine will use a small JNI shim around a vendored whisper.cpp snapshot built only for `arm64-v8a`, and the TensorFlow Lite interpreter for YAMNet. Audio decoding/resampling will be handled in Kotlin using Android `MediaExtractor`/`MediaCodec` or WAV parsing where possible, while RMS calculation will be implemented as a tight Kotlin loop over PCM samples and exposed as a reusable engine function.

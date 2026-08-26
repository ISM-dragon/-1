# Offline Android AI engine

The `com.example.ondeviceai` package contains the offline inference layer for Android. It is intentionally independent of the existing UI, remote gateway, workers, persistence, and business-logic packages.

## Public interfaces

`LocalASR` accepts an audio filesystem path and returns a JSON array such as:

```json
[{"word":"hello","startMs":120,"endMs":420,"confidence":0.91}]
```

It decodes WAV files directly and uses Android `MediaExtractor`/`MediaCodec` for other audio containers, mixes to mono, resamples to 16 kHz, and invokes the bundled quantized `ggml-tiny.en-q5_1.bin` through the vendored whisper.cpp JNI bridge. Word intervals are produced from whisper.cpp token timestamps with `max_len = 1` and `split_on_word = true`.

`AudioEventDetector` runs the bundled YAMNet TensorFlow Lite model on 15,600-sample mono 16 kHz windows with a 7,680-sample hop and exposes application-level `laughter`, `music`, and `silence` scores. The model produces a 521-class vector; `yamnet_class_map.csv` maps those indices to names.

`ProsodyExtractor.rms` calculates RMS directly over a word’s sample interval. `annotateWords` marks a word as emphatic when its RMS meets both an absolute floor and a relative-to-utterance threshold.

## Native build

The native CMake target builds only `arm64-v8a`, with whisper.cpp examples, server, tests, GPU backends, and OpenMP disabled. Runtime inference performs no network access. The model assets are copied once to app-private storage before native inference.

The Android SDK/NDK and CMake are build-time prerequisites. The repository deliberately does not commit generated Gradle or native build directories.

## Model provenance

| Asset | Source | License | SHA-256 |
| --- | --- | --- | --- |
| `models/ggml-tiny.en-q5_1.bin` | [whisper.cpp model collection](https://huggingface.co/ggerganov/whisper.cpp) | MIT, see vendored `LICENSE` | `c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b` |
| `models/yamnet.tflite` | [Google YAMNet TFLite model](https://www.kaggle.com/models/google/yamnet/tfLite/classification-tflite/1) | Apache 2.0 | `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de` |
| `models/yamnet_class_map.csv` | [TensorFlow Models class map](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv) | Apache 2.0 | `cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2` |


## Test

`OnDeviceAiInstrumentedTest` copies `sample_10s.wav` from `androidTest/assets`, executes both models offline, feeds the ASR intervals into the RMS extractor, and prints ASR JSON, word prosody, and YAMNet events to logcat under the `OnDeviceAiTest` tag.

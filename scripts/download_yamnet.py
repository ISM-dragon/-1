from pathlib import Path
import shutil

import kagglehub

source = Path(kagglehub.model_download("google/yamnet/tfLite/classification-tflite/1")) / "1.tflite"
destination = Path("android/app/src/main/assets/models/yamnet.tflite")
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, destination)
print(f"downloaded={source}")
print(f"file={destination} size={destination.stat().st_size}")

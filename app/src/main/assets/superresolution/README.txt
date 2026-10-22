ComicLab page and entire-comic enhancement

Models: realesr-animevideov3-x2, x3 and x4, Real-ESRGAN official lightweight
anime models, selected by the shared 2x/3x/4x setting, not specifically trained
for monochrome manga text.
Source: https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0
Files extracted without modification from:
https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip
Archive SHA-256: abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d
x2 model .bin SHA-256: 548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d
x2 model .param SHA-256: b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04
x3 model .param SHA-256: d1a5755008791d09b57e3425fc9dd0bd26b00fdf79c606210bc0e693f8230881
x4 model .param SHA-256: 850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e
License: LICENSE-Real-ESRGAN.txt (BSD 3-Clause, Xintao Wang)

Runtime: Tencent ncnn 20261020, Vulkan GPU preferred, CPU fallback.
License: LICENSE-ncnn.txt

Implementation: up to 256px source tiles (128 on low-memory devices),
32px context on each side, one-tile decode prefetch, up to 4 CPU threads,
white alpha
background; cropped RGB output written to disk, then streaming PNG encoding.
Source dimension limit 16384 per side; output limit 32768 per side and
40 megapixels at the selected scale.
Full-resolution images are not assembled into a Java Bitmap.
Progress 0-90: inference; 90-99: PNG encoding; 100: result ready.
Cancellation takes effect between source extraction, model loading, tiles
and PNG rows; an already running native tile is allowed to finish safely.
Closing or rotating the reader cancels the task. Completed preview files are
removed after both processing and the image decoder have released them.
The preview can save an independent enhanced PNG using Android's system
document picker. Entire-comic enhancement writes a new CBZ with numbered PNG
pages in reading order and preserves EXIF orientation. The model and Vulkan
pipelines are reused across pages; PNGs are stored in ZIP without redundant
compression. A new document destination and filename are chosen before work.
Entire-comic progress is displayed in a dialog over the file browser.
Incomplete destinations are removed on cancellation or failure where the
document provider supports deletion. The source archive is not replaced.

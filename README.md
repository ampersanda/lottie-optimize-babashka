# lottie-optimize-babashka

Babashka (Clojure) CLI tool that optimizes Lottie JSON animation files by compressing embedded images, truncating float precision, stripping editor metadata, and minifying output.

## Requirements

- [Babashka](https://babashka.org/) (bb)
- [ImageMagick](https://imagemagick.org/) (magick)
- [libwebp](https://developers.google.com/speed/webp/) (cwebp)

```bash
brew install babashka imagemagick webp
```

## Usage

```bash
# Basic (images downscaled to canvas size, WebP q80)
bb optimize-lottie.bb -i animation.json

# Lossless (pixel-perfect, no quality loss)
bb optimize-lottie.bb -i animation.json -o output.json --lossless

# Custom quality and max image size
bb optimize-lottie.bb -i animation.json -q 95 -s 512

# Reduce framerate
bb optimize-lottie.bb -i animation.json --fps 30
```

## Options

| Flag | Description | Default |
|---|---|---|
| `-i, --input` | Input Lottie JSON file | required |
| `-o, --output` | Output file path | `<input>.optimized.json` |
| `-s, --max-image-size` | Max image dimension (px) | canvas size |
| `-q, --webp-quality` | WebP quality 0-100 | 80 |
| `-p, --precision` | Float decimal places | 3 |
| `-f, --fps` | Target framerate | keep original |
| `-l, --lossless` | Lossless WebP | false |

## How it works

1. **Image compression** - Downscales embedded PNG images to fit the animation canvas, converts to WebP (or keeps PNG if smaller), picks the smallest result
2. **Float truncation** - Reduces excessive decimal places in keyframe data
3. **Metadata removal** - Strips After Effects editor-only properties (`mn`)
4. **JSON minification** - Removes whitespace

Typical results: ~80-94% file size reduction depending on quality settings.

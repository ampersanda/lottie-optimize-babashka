# lottie-optimize-babashka

Babashka (Clojure) CLI tool that optimizes Lottie JSON animation files by compressing embedded images, truncating float precision, stripping editor metadata, and minifying output.

## Install

The install script checks that all requirements are present before downloading.

```bash
curl -fsSL https://raw.githubusercontent.com/ampersanda/lottie-optimize-babashka/main/install.sh | bash
```

### Requirements

The following must be installed **before** running the install script:

- [Babashka](https://github.com/babashka/babashka) (bb)
- [ImageMagick](https://imagemagick.org/) (magick)
- [libwebp](https://developers.google.com/speed/webp/) (cwebp) -- optional but recommended

```bash
brew install borkdude/brew/babashka imagemagick webp
```

The script installs `optimize-lottie` to `~/.local/bin`. Make sure it is in your `PATH`:

```bash
export PATH="${HOME}/.local/bin:${PATH}"
```

### Uninstall

```bash
rm ~/.local/bin/optimize-lottie
```

## Usage

If installed via the install script:

```bash
# Basic (images downscaled to canvas size, WebP q80)
optimize-lottie -i animation.json

# Lossless (pixel-perfect, no quality loss)
optimize-lottie -i animation.json -o output.json --lossless

# Custom quality and max image size
optimize-lottie -i animation.json -q 95 -s 512

# Reduce framerate
optimize-lottie -i animation.json --fps 30
```

Or run directly with Babashka:

```bash
bb optimize-lottie.bb -i animation.json
bb optimize-lottie.bb -i animation.json -o output.json --lossless
bb optimize-lottie.bb -i animation.json -q 95 -s 512
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

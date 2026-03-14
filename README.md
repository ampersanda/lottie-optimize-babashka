# lottie-optimize-babashka

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

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

## Examples

Full-screen looping confetti animation examples located in `example/`.

> [!WARNING]
> All examples symlink to `animations/confetti.json`. Changes to that file are reflected immediately across all example projects.

### iOS

Uses SwiftUI + [lottie-ios](https://github.com/airbnb/lottie-ios) (SPM).

```bash
cd example/ios/LottieExample
open LottieExample.xcodeproj
```

Xcode resolves the Lottie SPM dependency automatically. Select a simulator or device and run.

### Android

Uses Jetpack Compose + [lottie-compose](https://github.com/airbnb/lottie-android).

```bash
cd example/android
./gradlew installDebug
adb shell am start -n com.example.lottieexample/.MainActivity
```

Or open the `example/android` folder in Android Studio.

### Flutter

Uses [lottie](https://pub.dev/packages/lottie) with [Very Good Analysis](https://pub.dev/packages/very_good_analysis). Tested on iOS, Android, and macOS.

```bash
cd example/flutter
flutter pub get
flutter run
```

### ClojureDart

Uses [ClojureDart](https://github.com/Tensegritics/ClojureDart) with [lottie](https://pub.dev/packages/lottie).

```bash
cd example/clojuredart
clj -M:cljd flutter
```

## How it works

1. **Image compression** - Downscales embedded PNG images to fit the animation canvas, converts to WebP (or keeps PNG if smaller), picks the smallest result
2. **Layer transform adjustment** - Updates asset dimensions to match resized pixels, then compensates image layers by scaling anchor points and scale transforms so rendering stays correct on both iOS and Android
3. **Float truncation** - Reduces excessive decimal places in keyframe data
4. **Metadata removal** - Strips After Effects editor-only properties (`mn`)
5. **JSON minification** - Removes whitespace

Typical results: ~80-94% file size reduction depending on quality settings.

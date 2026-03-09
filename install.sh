#!/usr/bin/env bash
set -euo pipefail

REPO="ampersanda/lottie-optimize-babashka"
INSTALL_DIR="${HOME}/.local/bin"
BIN_NAME="optimize-lottie"

info()  { printf '\033[1;34m=>\033[0m %s\n' "$1"; }
ok()    { printf '\033[1;32m=>\033[0m %s\n' "$1"; }
warn()  { printf '\033[1;33m=>\033[0m %s\n' "$1"; }
fail()  { printf '\033[1;31m=>\033[0m %s\n' "$1"; exit 1; }

# -- Check requirements -------------------------------------------------------

info "Checking requirements..."

if ! command -v bb &>/dev/null; then
  fail "babashka (bb) is not installed.
  Install: brew install borkdude/brew/babashka
  See:     https://github.com/babashka/babashka#installation"
fi

if ! command -v magick &>/dev/null; then
  fail "ImageMagick (magick) is not installed.
  Install: brew install imagemagick"
fi

if ! command -v cwebp &>/dev/null; then
  warn "cwebp (libwebp) is not installed. WebP conversion will be disabled."
  warn "Install: brew install webp"
fi

ok "Requirements satisfied (bb $(bb --version), magick $(magick --version 2>/dev/null | head -1 | awk '{print $3}'))"

# -- Download binary -----------------------------------------------------------

info "Downloading latest release..."

DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${BIN_NAME}"

mkdir -p "${INSTALL_DIR}"

if command -v curl &>/dev/null; then
  curl -fsSL "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/${BIN_NAME}"
elif command -v wget &>/dev/null; then
  wget -qO "${INSTALL_DIR}/${BIN_NAME}" "${DOWNLOAD_URL}"
else
  fail "Neither curl nor wget found. Install one and retry."
fi

chmod +x "${INSTALL_DIR}/${BIN_NAME}"

ok "Installed ${BIN_NAME} to ${INSTALL_DIR}/${BIN_NAME}"

# -- Check PATH ----------------------------------------------------------------

if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  warn "${INSTALL_DIR} is not in your PATH."
  warn "Add this to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
  warn "  export PATH=\"\${HOME}/.local/bin:\${PATH}\""
fi

echo ""
ok "Done! Run 'optimize-lottie -h' to get started."

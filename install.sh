#!/usr/bin/env bash
set -euo pipefail

REPO="ampersanda/lottie-optimize-babashka"
INSTALL_DIR="${HOME}/.local/bin"
BIN_NAME="optimize-lottie"
API_URL="https://api.github.com/repos/${REPO}/releases/latest"

info()  { printf '\033[1;34m=>\033[0m %s\n' "$1"; }
ok()    { printf '\033[1;32m=>\033[0m %s\n' "$1"; }
warn()  { printf '\033[1;33m=>\033[0m %s\n' "$1"; }
fail()  { printf '\033[1;31m=>\033[0m %s\n' "$1"; exit 1; }

fetch() {
  if command -v curl &>/dev/null; then
    curl -fsSL "$@"
  elif command -v wget &>/dev/null; then
    wget -qO- "$@"
  else
    fail "Neither curl nor wget found. Install one and retry."
  fi
}

download() {
  local url="$1" dest="$2"
  if command -v curl &>/dev/null; then
    curl -fsSL "${url}" -o "${dest}"
  elif command -v wget &>/dev/null; then
    wget -qO "${dest}" "${url}"
  else
    fail "Neither curl nor wget found. Install one and retry."
  fi
}

get_latest_version() {
  fetch "${API_URL}" | grep '"tag_name"' | sed -E 's/.*"tag_name":\s*"([^"]+)".*/\1/'
}

get_installed_version() {
  if [[ -x "${INSTALL_DIR}/${BIN_NAME}" ]]; then
    "${INSTALL_DIR}/${BIN_NAME}" --version 2>/dev/null | awk '{print $NF}'
  fi
}

check_requirements() {
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
}

# -- Main ----------------------------------------------------------------------

check_requirements

installed="$(get_installed_version)"

if [[ -n "${installed}" ]]; then
  info "Found ${BIN_NAME} ${installed}, checking for updates..."

  latest="$(get_latest_version)"

  if [[ -z "${latest}" ]]; then
    fail "Could not fetch latest version from GitHub."
  fi

  # Strip leading 'v' for comparison
  latest_clean="${latest#v}"
  installed_clean="${installed#v}"

  if [[ "${latest_clean}" == "${installed_clean}" ]]; then
    ok "Already up to date (${installed})."
    exit 0
  fi

  info "Updating ${BIN_NAME}: ${installed} -> ${latest}..."

  download_url="https://github.com/${REPO}/releases/download/${latest}/${BIN_NAME}"
  download "${download_url}" "${INSTALL_DIR}/${BIN_NAME}"
  chmod +x "${INSTALL_DIR}/${BIN_NAME}"

  ok "Updated to ${latest}."
else
  info "Downloading latest release..."

  mkdir -p "${INSTALL_DIR}"

  download_url="https://github.com/${REPO}/releases/latest/download/${BIN_NAME}"
  download "${download_url}" "${INSTALL_DIR}/${BIN_NAME}"
  chmod +x "${INSTALL_DIR}/${BIN_NAME}"

  ok "Installed ${BIN_NAME} to ${INSTALL_DIR}/${BIN_NAME}"

  if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
    warn "${INSTALL_DIR} is not in your PATH."
    warn "Add this to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
    warn "  export PATH=\"\${HOME}/.local/bin:\${PATH}\""
  fi

  echo ""
  ok "Done! Run '${BIN_NAME} -h' to get started."
fi

#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FIXTURE="${SCRIPT_DIR}/fixture.json"
PASS=0
FAIL=0

green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
red()   { printf '\033[1;31m%s\033[0m\n' "$1"; }
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    green "  PASS: ${desc}"
    PASS=$((PASS + 1))
  else
    red "  FAIL: ${desc}"
    red "    expected: ${expected}"
    red "    actual:   ${actual}"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    green "  PASS: ${desc}"
    PASS=$((PASS + 1))
  else
    red "  FAIL: ${desc}"
    red "    expected to contain: ${needle}"
    FAIL=$((FAIL + 1))
  fi
}

assert_not_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    green "  PASS: ${desc}"
    PASS=$((PASS + 1))
  else
    red "  FAIL: ${desc}"
    red "    expected NOT to contain: ${needle}"
    FAIL=$((FAIL + 1))
  fi
}

# Run test suite against a given command
run_tests() {
  local cmd="$1"
  local label="$2"

  bold ""
  bold "=== ${label} ==="
  bold ""

  # -- Help flag ---------------------------------------------------------------
  bold "Help flag:"

  output=$(eval "$cmd -h" 2>&1 || true)
  assert_contains "-h shows usage" "Usage:" "$output"
  assert_contains "-h shows options" "Options:" "$output"
  assert_contains "-h shows examples" "Examples:" "$output"

  output=$(eval "$cmd --help" 2>&1 || true)
  assert_contains "--help shows usage" "Usage:" "$output"

  # -- Missing input -----------------------------------------------------------
  bold "Missing input:"

  output=$(eval "$cmd" 2>&1 || true)
  if [[ "$output" == *"Required option"* ]] || [[ "$output" == *"Error:"* ]]; then
    green "  PASS: no args shows error"
    PASS=$((PASS + 1))
  else
    red "  FAIL: no args shows error"
    FAIL=$((FAIL + 1))
  fi

  # -- File not found ----------------------------------------------------------
  bold "File not found:"

  output=$(eval "$cmd -i nonexistent_file.json" 2>&1 || true)
  assert_contains "nonexistent file shows error" "not found" "$output"

  # -- Basic optimization ------------------------------------------------------
  bold "Basic optimization:"

  local out_file="${SCRIPT_DIR}/output_test.json"
  rm -f "$out_file"

  eval "$cmd -i '$FIXTURE' -o '$out_file'" >/dev/null 2>&1
  assert_eq "creates output file" "true" "$( [[ -f "$out_file" ]] && echo true || echo false )"

  output=$(cat "$out_file")

  # Output should be valid JSON
  echo "$output" | bb -e '(cheshire.core/parse-string (slurp *in*))' >/dev/null 2>&1
  json_exit=$?
  assert_eq "output is valid JSON" "0" "$json_exit"

  # Output should be smaller than input
  input_size=$(wc -c < "$FIXTURE" | tr -d ' ')
  output_size=$(wc -c < "$out_file" | tr -d ' ')
  assert_eq "output is smaller" "true" "$( [[ "$output_size" -lt "$input_size" ]] && echo true || echo false )"

  # Editor metadata (mn keys) should be stripped
  assert_not_contains "mn keys stripped" '"mn"' "$output"

  # Float precision should be truncated (no long decimals)
  assert_not_contains "long floats truncated" "123456789" "$output"

  # Core structure preserved
  assert_contains "keeps version" '"v"' "$output"
  assert_contains "keeps framerate" '"fr"' "$output"
  assert_contains "keeps layers" '"layers"' "$output"

  rm -f "$out_file"

  # -- Custom precision --------------------------------------------------------
  bold "Custom precision:"

  out_file="${SCRIPT_DIR}/output_prec.json"
  rm -f "$out_file"

  eval "$cmd -i '$FIXTURE' -o '$out_file' -p 1" >/dev/null 2>&1
  output=$(cat "$out_file")

  # With precision 1, 100.987654321 should become 101.0 or 101
  assert_not_contains "precision 1 truncates" "100.98" "$output"

  rm -f "$out_file"

  # -- FPS reduction -----------------------------------------------------------
  bold "FPS reduction:"

  out_file="${SCRIPT_DIR}/output_fps.json"
  rm -f "$out_file"

  eval "$cmd -i '$FIXTURE' -o '$out_file' --fps 30" >/dev/null 2>&1
  output=$(cat "$out_file")

  assert_contains "framerate changed to 30" '"fr":30' "$output"
  # Original op=120 at 60fps should become 60 at 30fps
  assert_contains "op scaled" '"op":60' "$output"

  rm -f "$out_file"

  # -- Lossless flag -----------------------------------------------------------
  bold "Lossless flag:"

  out_file="${SCRIPT_DIR}/output_lossless.json"
  rm -f "$out_file"

  eval "$cmd -i '$FIXTURE' -o '$out_file' --lossless" >/dev/null 2>&1
  assert_eq "lossless creates output" "true" "$( [[ -f "$out_file" ]] && echo true || echo false )"

  rm -f "$out_file"
}

# ==============================================================================
# Run tests
# ==============================================================================

bold "Running optimize-lottie tests"
bold "Fixture: ${FIXTURE}"

# Test with babashka (always available in repo)
run_tests "bb ${PROJECT_DIR}/optimize-lottie.bb" "Babashka (bb optimize-lottie.bb)"

# Test with installed binary (if available)
if command -v optimize-lottie &>/dev/null; then
  run_tests "optimize-lottie" "Binary (optimize-lottie)"
else
  bold ""
  bold "=== Binary (optimize-lottie) ==="
  bold "  SKIP: optimize-lottie not found in PATH"
fi

# -- Summary -------------------------------------------------------------------
bold ""
bold "=== Summary ==="
green "  Passed: ${PASS}"
if [[ "$FAIL" -gt 0 ]]; then
  red "  Failed: ${FAIL}"
  exit 1
else
  bold "  Failed: 0"
  green "  All tests passed!"
fi

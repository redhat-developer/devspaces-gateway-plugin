#!/usr/bin/env bash
# Installs the Open Code Review (OCR) CLI at the version pinned by the committed .ocr/ assets.
# Skill files and config live in the repository; only the CLI binary is missing on a fresh clone.
set -euo pipefail

OCR_PACKAGE="@open-code-review/cli"
CLI_CONFIG=".ocr/cli-config.json"

normalize_version() {
  printf '%s' "$1" | tr -d 'v[:space:]'
}

cd "$(dirname "$0")/.."

if ! command -v npm >/dev/null 2>&1; then
  echo "npm not found. Install Node.js from https://nodejs.org and re-run." >&2
  exit 1
fi

if [ ! -f "$CLI_CONFIG" ]; then
  echo "$CLI_CONFIG not found. Run this script from a checkout of the repository." >&2
  exit 1
fi

OCR_VERSION="$(node -p "require('./$CLI_CONFIG').cliVersion ?? ''" 2>/dev/null || true)"
if [ -z "$OCR_VERSION" ]; then
  echo "No cliVersion recorded in $CLI_CONFIG. Run 'ocr update' and commit the result." >&2
  exit 1
fi

installed_version="$(normalize_version "$(ocr --version 2>/dev/null || true)")"
if [ "$installed_version" = "$OCR_VERSION" ]; then
  echo "ocr ${OCR_VERSION} already installed."
elif [ -n "$installed_version" ] && [ "${1:-}" != "--keep-installed" ]; then
  echo "ocr ${installed_version} is installed globally, but this repository pins ${OCR_VERSION}." >&2
  echo "Match the repository (replaces the global install):" >&2
  echo "  npm install -g ${OCR_PACKAGE}@${OCR_VERSION}" >&2
  echo "Keep the installed version and accept the drift warning: re-run with --keep-installed." >&2
  exit 1
else
  if [ -n "$installed_version" ]; then
    echo "Keeping ocr ${installed_version}; assets are pinned to ${OCR_VERSION}."
  else
    echo "Installing ${OCR_PACKAGE}@${OCR_VERSION} ..."
    if ! npm install -g "${OCR_PACKAGE}@${OCR_VERSION}"; then
      echo "Global install failed (permissions?). Install into a user prefix instead:" >&2
      echo "  npm install -g --prefix ~/.local ${OCR_PACKAGE}@${OCR_VERSION}" >&2
      echo "then add ~/.local/bin to PATH and re-run." >&2
      exit 1
    fi
    if ! command -v ocr >/dev/null 2>&1; then
      echo "ocr was installed to $(npm prefix -g)/bin, which is not on PATH." >&2
      echo "Add that directory to PATH and re-run." >&2
      exit 1
    fi
    installed_version="$(normalize_version "$(ocr --version)")"
    if [ "$installed_version" != "$OCR_VERSION" ]; then
      echo "Installed ocr ${installed_version}, expected ${OCR_VERSION}." >&2
      exit 1
    fi
  fi
fi

mkdir -p .ocr/sessions

echo
ocr doctor

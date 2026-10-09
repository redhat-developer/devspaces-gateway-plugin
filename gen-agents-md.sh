#!/usr/bin/env bash
# Regenerates AGENTS.md from rules/*.md. Do not edit AGENTS.md directly.
set -euo pipefail
cd "$(dirname "$0")"

{
  echo "<!-- GENERATED FILE: do not edit. Source: rules/*.md | Regenerate: ./gen-agents-md.sh -->"
  first=true
  for f in rules/*.md; do
    [ -e "$f" ] || continue
    if $first; then first=false; else echo; echo "---"; echo; fi
    cat "$f"
  done
} > AGENTS.md

echo "Generated AGENTS.md from: $(ls rules/*.md | tr '\n' ' ')"

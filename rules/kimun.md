# Kimün (`km`) Branch Code Health

Gate every topic branch on Kimün grades for the files the branch changed. Never score the whole repo.

---

## 1. Install

| OS | Install |
|---|---|
| macOS | `brew install lnds/kimun/kimun`; or `cargo install kimun`; Apple Silicon tarball from [releases](https://github.com/lnds/kimun/releases) |
| Linux | `brew install lnds/kimun/kimun` (Linuxbrew); or `cargo install kimun`; x86_64/arm64 tarball |
| Windows | `cargo install kimun`; or download `km-x86_64-pc-windows-msvc.zip` and extract `km.exe` into a `PATH` folder (e.g. `%LOCALAPPDATA%\kimun\bin`) |

- No official winget or scoop package - do not claim one.
- macOS Intel: releases only ship `aarch64-apple-darwin` - use brew or cargo.

**Verify:**
- `km --version` (expect 0.49 or newer)
- `km score --format json .`

---

## 2. Branch Scope

Score only the files the branch changed:
- Committed: `git diff --name-only --diff-filter=ACMRT <base>...HEAD` (three-dot = merge-base, branch-unique commits only)
- Staged + unstaged: `git diff --name-only --diff-filter=ACMRT HEAD`
- Untracked: `git ls-files --others --exclude-standard -- src` (new feature code only)

Filters: existing files only; km-recognized code only (`files_analyzed == 0` -> skip); agent-infra paths (`.agents/`, `.claude/`, `.cursor/`, `rules/`, `AGENTS.md`) excluded.

Base ref: `upstream/main`, then `origin/main`, then `main`. Override with `--base <ref>` or `KM_BASE_REF`.

---

## 3. Gate

- Per changed file: every dimension grade **and** the overall grade start with `A` (`A-` = 87 or better).
- Null dimension grades pass - km spreads the weight over the measured dimensions.
- Gate on grade prefix, not numeric thresholds.
- Duplication caveat: per-file duplication only sees repetition inside that one file; duplication across files is visible only in `km dups`. Compare `km dups` against a baseline snapshot and fail only on **new** participation by changed files.
- `.kimun.toml` `fail_below = "C+"` applies only to `--trend` runs. The branch gate is `A-`.

---

## 4. Procedure

Full workflow: `.agents/skills/kimun-branch/SKILL.md`.

**Run:**
- macOS/Linux: `python3 .agents/skills/kimun-branch/scripts/km_branch.py all`
- Windows: `py -3 .agents\skills\kimun-branch\scripts\km_branch.py all`

Never run `km init`. Never modify `.kimun.toml`.

**Policy Source:**
- Do not edit `AGENTS.md` directly - change `rules/` and run `./gen-agents-md.sh`.

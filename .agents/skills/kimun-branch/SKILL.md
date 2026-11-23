---
name: kimun-branch
description: Score only the files changed by the current topic branch with Kimün (km) and improve every dimension graded below A-. Triggers - km, kimun, run km, score branch, code health, /kimun-branch.
---

# Kimün branch code health

Kimün (`km`) grades files across cognitive complexity, duplication, indentation complexity,
Halstead effort and file size. Run it on **only** the files this branch changed, then raise every
dimension to `A-` or better without harming the design.

Policy (install, scope, gate) lives in `AGENTS.md` §Kimün (source: `rules/kimun.md`). Read that
section before installing anything.

Per-dimension refactoring playbook: `{SKILLBASE}/references/improving-scores.md`.

## 1. Preflight

- Run `km --version`. If it is missing, follow the install matrix in `rules/kimun.md` §1 and ask
  for consent before any system install (`brew`, `cargo`, or a downloaded binary).
- Confirm JSON output works: `km score --format json <one changed .kt file>`.
- Never run `km init`. Never modify `.kimun.toml`.

## 2. Baseline

```
python3 .agents/skills/kimun-branch/scripts/km_branch.py all
```

Windows: `py -3 .agents\skills\kimun-branch\scripts\km_branch.py all`.

- The header prints the base ref: `upstream/main`, then `origin/main`, then `main`.
  Override with `--base <ref>` or `KM_BASE_REF`.
- The run scores every changed file and writes the duplication snapshot to
  `build/km-dups-baseline.json` on first use. Keep that file for the whole session.
- A stale-base warning means the remote-tracking ref has not been refreshed recently. Ask for
  consent, then run `git fetch upstream main` (or the hint the script prints) and re-run.

## 3. Gate

- Every cell in the table must start with `A`. `✗` marks cells below `A-`.
- Exit codes: `0` all pass, `1` at least one dimension or file below `A-`, `2` tooling error
  (missing `km`, git failure, bad base ref).
- All files pass: skip to step 7.

## 4. Improve loop — 3 rounds per check-in

One round = diagnose one failing dimension, apply one refactor, re-score. Batch at most
**3 rounds per check-in**, then report and ask whether to continue.

| Dimension | Diagnostic |
|---|---|
| Cognitive Complexity | `km cogcom --format json --per-function <file>` |
| Indentation Complexity | `km indent --format json <file>` |
| Halstead Effort | `km hal --format json <file>` |
| File Size | `km loc --format json <file>` (or the File Size score dimension) |
| Duplication | `python3 .agents/skills/kimun-branch/scripts/km_branch.py dups` and `dups --baseline build/km-dups-baseline.json` |

- Pick the lowest grade first; a single refactor often lifts several dimensions.
- Read the tactics and traps for that dimension in `{SKILLBASE}/references/improving-scores.md`.
- Follow the repo standards in `AGENTS.md` (function length, nesting, naming, scope discipline).
- Re-score after each round with `km_branch.py score --base <ref>`; run the full `all` before
  reporting so duplication is re-checked against the baseline.
- New files created by legitimate splits are discovered automatically (untracked under `src/`).

### Anti-gaming rules

- No whitespace, reindent or line-joining tricks to move a metric.
- No variable renames to lower Halstead effort; rename only for clarity.
- No artificial file splits, wrapper classes or indirection added only to move a number.
- No deleting tests, assertions or comments to improve a grade.
- No unrelated refactors. Note out-of-scope findings under "Recommended structural improvements".

## 5. Verify (default, not opt-in)

- `./gradlew compileKotlin`
- `./gradlew detektBranch`
- `./gradlew test` (scope with `--tests` to the touched classes where possible)

A refactor that breaks compilation, lint or tests is reverted, or reported as a failure. Never
trade correctness for a grade.

## 6. Check-in or continue

After 3 rounds, if failures remain:

- List the remaining files and dimensions with current grades.
- Explain why each one resists `A-` (design constraint, test fixture repetition, generated file).
- Ask whether to continue. Never stop silently and never loop unbounded.

## 7. Final report

- Table of changed files with before and after grades per dimension.
- What changed per file (extracted functions, flattened nesting, shared helper, split module).
- Verification output summary: compile, detekt, tests.
- Anything that cannot reach `A-` without harming the design, with the reason.

## Fallback without Python

Run the raw commands from the repo root:

```
git diff --name-only --diff-filter=ACMRT upstream/main...HEAD
git diff --name-only --diff-filter=ACMRT HEAD
git ls-files --others --exclude-standard -- src
km score --format json <file>
km dups --format json
```

Score each existing file individually; treat a missing (`null`) dimension grade as a pass; compare
`km dups` groups against a saved copy of the baseline output.

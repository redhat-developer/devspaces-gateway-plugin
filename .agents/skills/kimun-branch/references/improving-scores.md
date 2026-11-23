# Improving Kimün grades

Playbook for the five dimensions in `km score` (`model = cogcom` in `.kimun.toml`):
Cognitive Complexity 32%, Duplication 21%, Halstead Effort 21%, Indentation Complexity 16%,
File Size 10%. Overall grade must reach `A-` (87) with every dimension at `A-` or better.

Work from the lowest grade up. One structural refactor usually lifts cognitive, indentation and
Halstead together; chasing numbers dimension by dimension invites gaming.

## Cognitive Complexity (32%)

**Measures:** SonarSource cognitive complexity - `+1` per structural keyword (`if`, `for`, `while`,
`when` branch, `catch`) plus the current nesting depth, so nested logic costs more than sequential
logic. Boolean operator type changes add `+1`.

**Diagnose:** `km cogcom --format json --per-function <file>`

**Tactics:**
- Extract each nesting level into a private method with a descriptive name.
- Replace nested `if` blocks with guard clauses and early returns.
- Flatten `when` inside `when`, and callback-in-callback blocks.
- Split long boolean chains into named predicates (`isConnectionUsable`).
- Move decision tables (branch on type + state) into `when` over a sealed type instead of nested `if`.

**Trap:** extracting a function does not lower the score if the nesting moves with it as a block.
The extracted body must be *flat*. Check the per-function output after every extraction.

## Indentation Complexity (16%)

**Measures:** how deep and how irregular the indentation is (standard deviation and maximum depth
per file) - a proxy for nested structure, not for layout.

**Diagnose:** `km indent --format json <file>`

**Tactics:**
- Same structural moves as cognitive: extract nested blocks, use guard clauses, flatten lambdas and
  callbacks into top-level private functions.
- Replace nested `try`/`when`/`if` pyramids with a small function per branch.
- Reduce deep builder chains by naming intermediate values.

**Trap:** reformatting, reindenting or joining lines is not an indentation fix - the metric follows
nesting depth, so a cosmetic pass changes nothing that matters. Never fix this dimension by
touching whitespace.

## Halstead Effort (21%)

**Measures:** difficulty × volume, computed from distinct and total operators and operands. Long
expressions with many distinct identifiers and repeated complex terms score worst.

**Diagnose:** `km hal --format json <file>`

**Tactics:**
- Break large functions into smaller ones so vocabulary is spread over scopes.
- Extract named locals and constants for repeated complex expressions
  (`val recoveryDeadline = now.plus(retryWindow)`), which shortens expressions.
- Simplify boolean and string-building expressions; replace long argument lists with a parameter
  object (`data class`) where the repo already uses that pattern.
- Remove dead parameters and unused imports - they still add vocabulary.

**Trap:** renaming variables, shortening identifiers or reordering operands only games the metric
and makes the code worse. Rename only when the new name is genuinely clearer.

## File Size (10%)

**Measures:** lines of code against a curve: full marks up to 500 LOC, degrading to 0 at 4000 LOC.
`A-` roughly corresponds to 955 LOC or fewer.

**Diagnose:** `km loc --format json <file>` and the File Size dimension in `km score`.

**Tactics:**
- Split only along a genuine responsibility: e.g. transport, retry policy, state machine.
- Move cohesive helper functions into a file named for their concept, keep the public API where it is.
- Reuse existing abstractions instead of parking new helpers in an already large file.

**Trap:** an artificial split (one class scattered over four files, or a file cut at an arbitrary
line) satisfies the metric and destroys the design. Before splitting, list the dependent files that
must change: `plugin.xml`, imports, `internal` visibility, test files. File size is rarely the
failing dimension - confirm it is actually below `A-` first.

## Duplication (21%)

**Measures:** copied blocks (minimum 6 lines per `.kimun.toml`). The per-file Duplication
dimension only sees repetition inside that one file - a file that repeats its own blocks scores
below 100 (e.g. `F-`). Duplication **across files** is visible only in `km dups`.

**Diagnose:**
- `python3 .agents/skills/kimun-branch/scripts/km_branch.py dups` - groups touching changed files.
- `dups --baseline build/km-dups-baseline.json` - fails only on **new** participation.
- `km dups --format json` directly for the full group list.

**Tactics:**
- Apply the Rule of Three: extract a shared helper on the third repetition, not the first.
- Parameterize near-identical strategies (shared function plus a value/lambda for the difference).
- Share test fixtures when assertions repeat; test duplication counts like production code.
- Prefer an existing helper in the codebase over a new near-duplicate.

**Trap:** adding indirection only to hide text from the detector (trivial wrappers, single-use base
classes, split lines) does not remove duplication - it buries it. If two blocks must stay separate
because they evolve independently, record that as an accepted exception in the final report instead
of faking a fix.

## Verification after every change

```
./gradlew compileKotlin
./gradlew detektBranch
./gradlew test
```

A refactor that improves a grade but breaks a test or violates `AGENTS.md` is reverted.

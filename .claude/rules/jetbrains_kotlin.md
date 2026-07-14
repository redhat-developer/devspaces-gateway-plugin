---
description: Kotlin and JetBrains plugin coding standards
globs: "**/*.kt"
alwaysApply: false
---
# Kotlin & JetBrains Plugin Development Standards

Write elegant, clean, performant, thread-safe code. No draft or incomplete implementations unless explicitly requested.

---

## 1. Kotlin Language & Code Style

**Readability over Cleverness:**
- Code explains itself - avoid complex one-liners or esoteric language features
- Nest scope functions (`run`, `let`, `apply`, `also`, `takeIf`) max 2 levels deep

**Function Design:**
- Keep under 30 lines
- Single responsibility - extract coordination steps into private helpers instead of comment-separated blocks

**Composition:**
- Prefer composition + interfaces over deep inheritance

**Design Pattern Integrity:**
- Adhere strictly to established GoF patterns when implemented
- Never mix instantiation paths - if using Builder, enforce it exclusively (no public constructors or generic `create()` helpers alongside)

**Class Cohesion & SRP:**
- Classes center on single domain concept or structural responsibility
- Decouple classes mixing layers (e.g., DB access + business logic) or accumulating massive scope

**Dependency Minimization:**
- Minimize class and method dependencies
- Pass result values or lambdas instead of instances just to call methods on them
- Example: `calculate(value: Int)` not `calculate(provider: Provider)` where you only call `provider.getValue()`

**Testability:**
- Structure for easy unit testing - inject dependencies instead of hardcoding them

**Naming:**
- Classes: singular concrete nouns reflecting domain concepts (`UserProfile`, `ConnectionWizard`)
  - Avoid generic suffixes like `Manager`, `Handler`, `Processor`, `Helper` unless truly warranted
  - Pattern-specific suffixes OK when following the pattern: `Builder`, `Factory`, `Repository`, `Service`
  - No abbreviations unless universally known (HTTP, URL, JSON)
- Variables/Properties: descriptive nouns (`userProfile`, not `data`)
- Functions/Methods: action verbs (`calculateTotalRevenue`, not `totals`)
- Booleans: `is`/`has`/`can`/`should` prefix (`isValid`, `hasPermission`)

**Class Structure:**
- Companion objects at top, before instance vars and constructors

**Global Functions:**
- Avoid - exception: utility helpers grouped together in same file with other global functions

**Access Control (Least Privilege):**
- Use least accessible modifier required
- `private` if only used in same class, `internal` if only used in module, `public` only if used outside module
- Default to `private` or `internal` - only `public` what's explicitly designed for external consumption

---

## 2. JetBrains Plugin Architecture

**Threading:**
- Never run long-running/blocking/I/O on EDT - use Progress Manager or backgroundable tasks
- Read ops in `runReadAction` (or `readAction` coroutine), writes in `writeAction`

**Memory Management:**
- Parent all temp UI, message bus connections, listeners to a `Disposable` to prevent leaks
- No static refs to heavy platform objects (`Project`, `Editor`, `PsiFile`)

**Platform APIs:**
- Use `@Service` over raw singletons
- Declarative `plugin.xml` registration over programmatic setup

---

## 3. Error Handling

**Fail Fast:**
- Validate inputs and preconditions at function start (`require`, `check`, `requireNotNull`)
- Exit early with guard clauses to avoid deeply nested `if` statements

**Exception Handling (No Swallowed Errors):**
- Never empty `catch` blocks
- Log error contexts with `com.intellij.openapi.diagnostic.thisLogger()` or propagate responsibly

**Type Safety:**
- Always define explicit types and interfaces
- Explicit return types on all public/internal APIs
- Explicit types on non-trivial variables - avoid loose or untyped variables

---

## 4. Documentation

**Comments:**
- Code documents what - KDoc explains WHY
- Use for complex business constraints, optimization edge cases, non-obvious architecture

**Constants:**
- Extract magic numbers, string literals, config keys to named constants or `enum`

---

## 5. UI Copy (UX Writing)

**Clarity:**
- Brief, direct - save reading time, reduce errors
- Active voice, simple present tense ("Maven uses" not "Maven has to use")
- One idea per sentence
- No passive ("Use secure connection" not "The use of a secure connection is required")

**Precision:**
- Cut filler words (*general*, *advanced*, *options*) unless needed for context
- No "you"/"your" - users know interface addresses them

**User Focus:**
- Write from workflow perspective, not implementation
- Translate internal concepts to user benefits and actions
- Instantly understandable to first-time users

---

## 6. Refactoring

**Architecture First:**
- Fix cohesion violations BEFORE adding features - don't build on debt

**Scope Discipline (No Ghost Changes):**
- Only rewrite code directly related to the task
- If structural issues found outside scope: list under "Recommended Structural Improvements" - do not silently refactor in code block

**Decomposition:**
- Step-by-step for complex extractions
- Show final state, explain extraction briefly

**Breaking Changes:**
- Warn when changing public signatures/instantiation
- List dependent files requiring updates

---

## Output Format

**Code Changes:**
- Specific edits/diffs over full-file rewrites unless requested

**Communication:**
- No fluff, filler, apologies
- Start with technical content

**Pattern Learning:**
- If user repeatedly corrects same pattern, suggest rule update to prevent recurrence

# Contributing Guidelines

Thank you for your interest in contributing to the **OpenShift Dev Spaces Gateway Plugin**!  
This is a Kotlin plugin for [JetBrains Gateway](https://www.jetbrains.com/remote-development/gateway/) / Remote Development that connects IntelliJ IDEs to OpenShift Dev Spaces (Eclipse Che) workspaces.

This document provides guidelines and instructions for contributing.

## Code of Conduct

By participating, you are expected to uphold a respectful and inclusive environment.  
Please report unacceptable behavior to the maintainers.

## Getting Started

### Prerequisites

- **JDK 21** (Zulu or any OpenJDK 21 distribution)
- **Git**
- A GitHub account

The project uses the Gradle Wrapper (`./gradlew`), so a local Gradle installation is not required.

### Development Setup

1. Fork the repository:  
   https://github.com/redhat-developer/devspaces-gateway-plugin

2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/devspaces-gateway-plugin.git
   cd devspaces-gateway-plugin
   ```

3. Open the project in IntelliJ IDEA (recommended) or any IDE with Kotlin/Gradle support.  
   IntelliJ will automatically import the Gradle project.

4. Verify the build works:
   ```bash
   ./gradlew buildPlugin
   ```

Optional: set up [Open Code Review](https://github.com/spencermarx/open-code-review) for AI-assisted code reviews — run `scripts/setup-ocr.sh` (requires Node.js). See [AI Code Review](README.md#ai-code-review-ocr) in the README for usage.

Optional: install the [pre-commit](https://pre-commit.com) hook from `.pre-commit-config.yaml` (`pre-commit install`) for a local secret scan before pushing. It is a local convenience only; GitHub runs the same scan on pushes, and CI enforces quality via the build workflow (`.github/workflows/build.yml`).

## Development Workflow

### Creating a Feature Branch

Always create a new branch for your work:

```bash
git checkout -b feature/your-feature-name
```

Use descriptive branch names:

- `feature/add-new-api`
- `fix/resolve-crash-on-startup`
- `docs/improve-installation-guide`

### Making Changes

1. Write clear, concise Kotlin code.
2. Follow the project’s coding standards (see [Code Style](#code-style)).
3. Add or update unit tests for new functionality.
4. Update documentation (README, CHANGELOG, KDoc) as needed.
5. Run the local checks listed below before committing.
6. Run `./gradlew detektBranch`, fix findings, then request an AI review from the agent prompt with `/ocr:review` (see [AI Code Review](README.md#ai-code-review-ocr)). Re-run the review only if fixes changed logic, and publish it to the PR with `/ocr:post`.
7. Reviews send the diff and reviewed files to the model configured in the contributor's AI CLI; `/ocr:post` publishes the result as a public PR comment.

### Running the Plugin Locally

- Launch a Gateway instance with the plugin:
  ```bash
  ./gradlew runIde
  ```

- Connect directly to an existing DevWorkspace (skips the Gateway main window):
  ```bash
  ./gradlew runIde --args="jetbrains-gateway://connect#type=devspaces&dwNamespace=YOUR_NAMESPACE&dwName=YOUR_WORKSPACE"
  ```

- Run against IntelliJ IDEA Ultimate instead of Gateway:
  ```bash
  ./gradlew runIdeIdea
  # or
  ./gradlew runIde -Pidea
  ```

### Building the Plugin

```bash
./gradlew clean buildPlugin
```

The packaged plugin (`.zip`) is written to `build/distributions/`.

### Running Tests

```bash
./gradlew check
# or explicitly:
./gradlew test
```

- Tests use **JUnit 5**, **MockK**, and **AssertJ**.
- To also show passed tests in the output:
  ```bash
  ./gradlew test -PshowPassed
  ```
- Coverage is collected with **Kover** (`build/reports/kover/`).

### Linting

```bash
# all Kotlin sources
./gradlew detekt

# only files changed on the current branch vs. main (committed + uncommitted, including untracked)
./gradlew detektBranch

# compare against another ref
./gradlew detektBranch -PdetektBaseRef=origin/main
```

- Reports are written to `build/reports/detekt/`.
- `check` does not run detekt.
- The full run reports pre-existing violations in unchanged code; `detektBranch` limits findings to the current changes.

### Plugin Verification

Check compatibility against the Gateway versions defined in `build.gradle.kts`:

```bash
./gradlew runPluginVerifier
# or the full verification task used in CI:
./gradlew verifyPlugin
```

### Code Style

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Prefer readability over cleverness; keep functions focused and under ~30 lines.
- Avoid `!!` where possible; prefer safe calls, Elvis, `requireNotNull` / `checkNotNull`.
- Use explicit return types on public/internal APIs.
- Respect JetBrains Platform threading rules (never block the EDT; use `readAction` / `writeAction` or coroutines).
- See `AGENTS.md` for the full set of project conventions.

CI also runs **Qodana** inspections on every pull request.

### Commit Messages

Write clear, concise commit messages that explain *why* the change was made.

Preferred style (Conventional Commits inspired):

```
feat: add support for reconnecting port-forward sessions
fix: stop Gateway process when IDE window is closed
docs: clarify runIde usage with existing workspaces
build(deps): bump jackson-databind to 2.22.2
```

Reference related issues where applicable (e.g. `CRW-12345` or Eclipse Che issues).

## Submitting a Pull Request

1. Push your branch to your fork.
2. Open a pull request against the `main` branch of  
   https://github.com/redhat-developer/devspaces-gateway-plugin
3. Fill in the PR description: what the change does, why it is needed, and how it was tested.
4. Ensure CI is green (Build, Test, Qodana, Plugin Verifier).
5. Request review from a maintainer.

The CI workflow (`.github/workflows/build.yml`) will:

- Build the plugin
- Run unit tests + Kover coverage
- Run Qodana inspections
- Run the IntelliJ Plugin Verifier
- Comment on the PR with a download link for the built plugin (for non-fork PRs)

## Reporting Issues / Feature Requests

Please file bugs, questions, and feature requests in the **Eclipse Che** issue tracker:

https://github.com/eclipse/che/issues

(mention the Dev Spaces Gateway Plugin / JetBrains Gateway in the description).

## License

By contributing, you agree that your contributions will be licensed under the same terms as the project:  
[Eclipse Public License 2.0](LICENSE).
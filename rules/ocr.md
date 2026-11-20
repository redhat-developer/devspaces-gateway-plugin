<!-- OCR:START -->
## Open Code Review Instructions

These instructions are for AI assistants handling code review in this project.

Always open `.ocr/skills/SKILL.md` when the request:
- Asks for code review, PR review, or feedback on changes
- Mentions "review my code" or similar phrases
- Wants multi-perspective analysis of code quality
- Asks to map, organize, or navigate a large changeset

Use `.ocr/skills/SKILL.md` to learn:
- How to run the 8-phase review workflow
- How to generate a Code Review Map for large changesets
- Available reviewer personas and their focus areas
- Session management and output format

Keep this managed block so `ocr init` can refresh the instructions.
<!-- OCR:END -->

## Review Step in the Change Workflow

After generating or changing code, run the OCR review before handing the change back:

1. Run `./gradlew detektBranch` and fix any findings.
2. Invoke `/ocr:review` from the agent prompt (OpenCode, Claude Code, Cursor), or follow `.ocr/skills/SKILL.md` directly.
3. Fix confirmed review findings, then re-run `./gradlew detektBranch`.

Skip this step only for changes without code (docs, config).

---
name: opencode-planner-worker
description: Plan features/fixes, delegate implementation to OpenCode, review
  diffs, delegate corrections, verify. Use in planner/worker mode with OpenCode.
disable-model-invocation: true
---

# OpenCode Planner/Worker Workflow

You are the planner and reviewer; OpenCode implements. You own architecture,
task decomposition, review, and completion. OpenCode owns investigation,
implementation, testing, and reporting. Never implement delegated work
yourself, including corrections.

## 1. Plan
Break work into small, independent, precisely scoped tasks. For each: what to
change, which files/classes/APIs, and the architectural constraints. Each task
must be self-contained — OpenCode should not need to rediscover the overall
feature. Delegate investigation only when you genuinely lack information, and
make it its own task.

## 2. Delegate
Send each task to OpenCode. Ask it to:
1. Implement the change.
2. Run the most relevant targeted verification (compile, tests, inspections).
3. Report files changed, a detailed diff, reasoning, checks run and results,
   assumptions, and remaining concerns — detailed enough to review without
   re-reading the files.

## 3. Wait
OpenCode runs a slow local model; long runtimes, context compaction, and
missing final responses are normal. Wait. A long execution time by itself is
not evidence that OpenCode is stuck. Interrupt only on concrete evidence:
unrecoverable error, tool/process failure, genuine hang, or repeated absence
of observable progress. Never implement the task yourself because OpenCode is
slow, and never send your own implementation with a stop instruction.

## 4. Review
Do not trust OpenCode's conclusions. Check the diff and reasoning against the
task, plan, and architecture. Look for misused APIs, unnecessary changes,
missed edge cases and error paths, regressions, and lifecycle, concurrency,
or compatibility problems.

## 5. Correct
Do not fix problems yourself. Send a small correction task: the problem,
expected behavior, relevant review context. Ask for only the necessary change
plus the section-2 report. Wait, then review again. Repeat until clean.

## 6. Complete
Done means: reviewed, verification actually ran (looking correct does not
count), no issues or material concerns. Then move to the next task, folding
earlier results into its instructions. Independent tasks may run in parallel.
Keep to the original architectural plan.


---
name: session-summary
description: Generate an engineering state snapshot at session end. Use when the user says "session summary", "/session-summary", or asks to save their current session state for later recovery.
---

# session-summary

## Purpose
Capture the current engineering state as a structured snapshot to minimize context drift across sessions. Not a free-form summary — a machine-recoverable state checkpoint.

## When to Invoke
- User explicitly says "session summary", "总结本次会话", or "/session-summary"
- End of a development session before closing Claude Code

## Workflow

### Step 1: Collect State
Run these in parallel to understand current state:

```
git status
git diff --stat
git log --oneline -5
```

Scan `.claude/memory/sessions/` for the most recent session file to understand continuity.

### Step 2: Identify Changes
- What was the session goal?
- Which files were modified/created/deleted?
- Any new modules, config changes, or dependency updates?
- Any architecture decisions made or changed?

### Step 3: Detect Risks
- Incomplete features or TODO stubs
- Tests not yet written
- Known bugs or edge cases not handled
- Decisions pending confirmation

### Step 4: Generate Snapshot
Write to `.claude/memory/sessions/YYYY-MM-DD-<topic>.md` using the format below.

## Output Format

```markdown
# Session Summary: <one-line topic>

**Date:** YYYY-MM-DD
**Branch:** <branch-name or "N/A">

## Goal
<1-2 sentences on what this session aimed to accomplish>

## Completed
- <concrete delivered item>
- <concrete delivered item>

## Modified Files
| File | Change |
|------|--------|
| `path/to/file` | Added / Modified / Deleted — brief reason |

## New Modules / Dependencies
- <module name>: <what it does>

## Architecture Decisions
- <decision>: <why> (status: confirmed / pending)

## TODO / Remaining
- [ ] <pending item>
- [ ] <pending item>

## Known Risks
- <risk description>

## Next Step
<specific next action, not vague — e.g. "Add unit tests for RetrievalService" not "keep developing">
```

## After Writing
- Confirm the file path to user
- If the session touched architecture or major decisions, also update:
  - `.claude/memory/architecture/` for structural changes
  - `.claude/memory/decisions/` for key trade-off records

## Anti-patterns
- Do NOT write a verbose narrative — this is a checklist, not a diary
- Do NOT include code snippets (reference file paths instead)
- Do NOT skip the "Next Step" field — it's the most important for resumption
- Do NOT write into `architecture/` or `decisions/` unless something actually changed

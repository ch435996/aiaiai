---
name: save-eval-result
description: Save evaluation results to docs/. Use when the user asks to save eval results, "保存评估结果", or wants to persist Layer 0-5 evaluation data for future review.
---

# save-eval-result

## Purpose
Save Agentic RAG five-layer evaluation results to `docs/layer{N}-eval-result.md`. Each layer has its own file, accumulating results over time for trend tracking and retrospection.

## When to Invoke
- User says "保存 Layer N 评估结果", "save eval results", or similar
- After completing an evaluation run where results should be persisted
- User wants to compare current results against historical data

## Workflow

### Step 1: Identify the layer and data source
Determine which layer (0-5) the user is referring to. The eval result data comes from the current conversation — the user will provide or reference the results just discussed.

### Step 2: Collect the data from conversation
Extract from the current conversation:
- Test date (today)
- Test set size and categories
- Key metrics (Recall@5, Recall@20, MRR, Routing Accuracy, etc.)
- Category breakdowns
- Failures and their analysis
- Configuration details (embedding model, reranker model, etc.)

### Step 3: Write to docs/
File path: `docs/layer{N}-eval-result.md`

If the file already exists, prepend the new entry at the top (after the title) with a `## Run: YYYY-MM-DD` heading. If it doesn't exist, create it with the sections below.

## Output Format

```markdown
# Layer {N}: {Layer Name} — Evaluation Results

> Last updated: YYYY-MM-DD

## Run: YYYY-MM-DD

### Configuration
- Embedding model: ...
- Reranker model: ... (if applicable)
- Test set: {N} queries, {categories}

### Overall Metrics
| Metric | Value |
|--------|-------|
| ... | ... |

### Category Breakdown
| Category | Metric | Value |
|----------|--------|-------|
| ... | ... | ... |

### Failures
| ID | Query | Issue | Severity |
|----|-------|-------|----------|
| ... | ... | ... | ... |

### Key Findings
- ...
- ...

### Recommendations
- ...
```

## After Writing
- Confirm file path to user
- If this is a significant result (new baseline established), also suggest updating CLAUDE.md eval status table

## Anti-patterns
- Do NOT save incomplete or partial results (wait for full eval output)
- Do NOT overwrite historical runs — always prepend new runs
- Do NOT include raw conversation noise — filter to structured data only

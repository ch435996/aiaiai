---
name: paper-ingest
description: Standardized paper ingestion pipeline for the 3D reconstruction / point cloud completion knowledge base. Use when the user uploads a paper, asks to "ingest a paper", or runs "/paper-ingest". This skill enforces uniform metadata schema, chunk strategy, and structural extraction so the RAG system stays coherent as the knowledge base grows.
---

# paper-ingest

## Purpose
Ensure every paper enters the knowledge base with identical structure, chunking, and metadata — preventing the drift that breaks retrieval and comparison quality over time. This is not a loose guideline; it is the enforced pipeline contract.

## Fixed Configuration (Do Not Deviate)

### Chunk Strategy
```
chunk_size = 800 tokens
overlap    = 100 tokens
```
Rationale: 800 tokens gives enough context for a method description or result table while keeping retrieval focused. 100-token overlap prevents boundary cuts on continuations.

### Embedding Model
```
model:      text-embedding-v3 (DashScope, OpenAI-compatible)
dimensions: 1024
endpoint:   https://dashscope.aliyuncs.com/compatible-mode/v1
```
Bundled config in `src/main/resources/application.yml` under `aiaiai.embedding`.

### Target Store
```
Pinecone index:      aiaiai-knowledge
namespace:           knowledge
```
Bundled config in `src/main/resources/application.yml` under `aiaiai.pinecone`.

## Metadata Schema (Mandatory)

Every paper MUST carry this metadata block. No field is optional.

```json
{
  "title": "",
  "authors": [],
  "year": "",
  "venue": "",
  "domain": "point-cloud-completion",
  "method": "",
  "dataset": [],
  "metrics": [],
  "contributions": [],
  "limitations": []
}
```

| Field | Type | Purpose |
|-------|------|---------|
| `title` | string | Exact paper title for dedup and citation |
| `authors` | string[] | First-author last name at minimum |
| `year` | string | Publication year — enables temporal filtering |
| `venue` | string | CVPR / ICCV / NeurIPS / arXiv etc. |
| `domain` | enum | Fixed to `point-cloud-completion`; extend only with consensus |
| `method` | string | Core method name (SnowflakeNet, PCN, FoldingNet, etc.) |
| `dataset` | string[] | Datasets used (ShapeNet, KITTI, Completion3D, etc.) |
| `metrics` | string[] | Metrics reported (CD, F-score, EMD, etc.) |
| `contributions` | string[] | 1-3 sentence-level contributions |
| `limitations` | string[] | Self-reported or reviewer-identified limits |

### Why This Schema?
- `method` + `dataset` enables "compare all methods evaluated on ShapeNet"
- `metrics` enables "find papers reporting F-score"
- `contributions` + `limitations` feed the "future work" and gap-analysis flows

## Structural Extraction (Section-Level)

When parsing a paper, extract these sections with priority. Each becomes a separate chunk (or chunk group) tagged with its `section_type` in metadata.

| Section | `section_type` | RAG Purpose |
|---------|----------------|-------------|
| Abstract | `abstract` | Quick relevance filtering |
| Method / Architecture | `method` | Method comparison queries |
| Dataset & Experiments | `dataset` | Dataset-driven retrieval |
| Results / Metrics | `metrics` | Benchmark lookups |
| Limitations / Discussion | `limitations` | Future work, gap analysis |
| Contributions / Conclusion | `contributions` | Novelty identification |

Sections not listed above (related work, background, acknowledgments) may be stored but carry lower retrieval priority — prefix them with `[supplementary]` in the chunk text.

## Workflow

### Step 1: Extract Text
- If PDF: extract via the `pdf` skill or direct text extraction
- If user pastes text inline: accept as-is

### Step 2: Validate Metadata
- Fill the metadata schema COMPLETELY
- If a field cannot be determined from the paper, mark it as `"unknown"` — never omit it
- Report any `"unknown"` fields to the user for manual fill

### Step 3: Chunk
- Split text by the section boundaries above
- For sections exceeding 800 tokens, split with 100-token overlap
- Prepend each chunk with a breadcrumb: `[title] > [section_type]` so retrieval results are self-identifying

### Step 4: Embed & Store
- Embed each chunk with text-embedding-v3 (1024d)
- Store in Pinecone `knowledge` namespace
- Attach the full metadata JSON as Pinecone metadata (enables metadata filtering later)

### Step 5: Confirm
- Print a summary: paper title, chunk count, any `"unknown"` fields
- Save the metadata JSON to `.claude/memory/sessions/<date>-paper-ingest-<method>.json` for audit trail

## Anti-patterns
- Do NOT change chunk size "for this paper" — 800/100 is fixed
- Do NOT skip sections because they "look short" — every section gets tagged
- Do NOT use a different embedding model — text-embedding-v3 only
- Do NOT omit metadata fields — `"unknown"` is acceptable; absence is not
- Do NOT store in the `memory` namespace — papers go to `knowledge`

## Audit Trail
Each ingest writes a metadata record to `.claude/memory/sessions/` named:
```
YYYY-MM-DD-paper-ingest-<method-slug>.json
```
This allows re-ingestion if the chunk strategy evolves.

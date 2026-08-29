# public-small-v1

`public-small-v1` is a deterministic, fully synthetic retrieval and reranking fixture. It contains neutral fictional documentation for Atlas, Nimbus, Orchid, Quartz, and Zephyr.

## Contents

- `corpus.jsonl`: 20 original synthetic chunks sorted by `chunkId`.
- `cases.jsonl`: 12 cases sorted by `caseId`, including answerable, multi-evidence, alternative-evidence, disambiguation, rerank-change, and abstention cases.
- `embeddings.jsonl`: frozen 12-dimensional unit vectors for every chunk and query.
- `manifest.json`: dataset contract, counts, generation settings, and data-file digests.
- `checksums.sha256`: SHA-256 digests, including the manifest digest.
- `SOURCES_AND_LICENSE.md`: synthetic-source and license boundary.

## Generate

From the repository root:

```powershell
python -X utf8 backend/evals/scripts/generate_public_small_v1.py
```

The generator uses Python standard library only, seed `20260809`, sorted JSON keys, stable row order, UTF-8, and LF line endings. It emits no timestamp. Repeated runs with the same generator are byte-identical.

## Retrieval contract

- Index: exact cosine over the frozen unit vectors.
- Candidate count: `8`.
- Final count after local lexical reranking: `5`.
- Vector ties: ascending `chunkId`.
- Local rerank score: `0.7 * cosine + 0.3 * query-term coverage`; rerank ties use ascending `chunkId`, then `documentId`.
- Each case records the expected exact-cosine candidates and reranked top results calculated by the generator.

The vectors are intentionally small and imperfect. This fixture tests deterministic evaluation plumbing and known ranking behavior; it is not evidence of production retrieval quality.

## Evidence semantics

- `relevantChunkIds` is the strict labeled relevance set.
- `acceptableChunkIds` contains evidence accepted by the deterministic grader.
- Every object in `requiredEvidenceGroups` is a required group; one chunk from each group satisfies evidence coverage.
- Multiple chunk IDs inside one group are genuinely equivalent alternatives for that fact.
- `requiredSourceIds` is exactly the source closure of acceptable evidence.
- Unanswerable cases have empty positive evidence fields and should lead to abstention.

`manifest.json` cannot safely contain its own digest because that would be self-referential. Its SHA-256 is therefore recorded in `checksums.sha256`; all non-self-referential core data digests are recorded inside the manifest.

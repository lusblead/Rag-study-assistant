# Step 3.2 decision evaluation fixtures

Everything in this directory is synthetic `FIXTURE_ONLY` test data. It verifies
the confusion-matrix orientation and metric arithmetic only. It is not a
human-reviewed Answerability Dev set, must not receive a formal dataset
manifest, and must not be reported as business-quality evidence.

Formal private data is mounted outside the repository and accepted only through
`FormalDecisionDatasetLoader` with manifest hashes and explicit human-review
metadata.

The frozen snapshot must include every production-policy-visible candidate
field (`chunkId`, `documentId`, `documentName`, `title`, `content`,
`sourcePage`, rank, and the named decision score). These private fields are
available only to the policy adapter; sweep reports remain aggregate-only.

The checked-in manifest is deliberately non-runnable: quality-gate values are
`null` until a human precommits and approves them. The loader rejects null
primitive gates, non-private datasets, evaluator-version drift, and missing
corpus/configuration hashes.

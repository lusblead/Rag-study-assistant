"""从固定 revision 的 T2Retrieval 构造可复现的公开检索评测子集。

这个脚本只负责数据准备，不调用 Embedding，也不生成“标准答案”。
标准答案直接来自上游 qrels；脚本仅做确定性抽样并把 Parquet 转成项目易读的 JSONL。
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pyarrow.parquet as parquet


SOURCE_DATASET = "mteb/T2Retrieval"
SOURCE_REVISION = "921dd3af6e78d1ae7ee0368aa8d7eaee02c8f08e"
SEED = "rag-study-assistant-t2-public-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 T2Retrieval 公开评测子集")
    parser.add_argument("--source-dir", type=Path, required=True,
                        help="包含 corpus.parquet、queries.parquet、qrels.parquet 的目录")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="输出 corpus.jsonl、cases.jsonl、manifest.json 的目录")
    parser.add_argument("--query-count", type=int, default=100)
    parser.add_argument("--document-count", type=int, default=2000)
    parser.add_argument("--neighbor-radius", type=int, default=4,
                        help="每个相关文档前后各加入多少篇相邻文档，增加近主题干扰项")
    parser.add_argument("--max-content-chars", type=int, default=4000,
                        help="发送给 BGE-M3 前保留的最大 Unicode 字符数")
    return parser.parse_args()


def stable_rank(namespace: str, value: str) -> str:
    """使用固定种子计算稳定排序键，避免依赖 Python 的随机哈希。"""
    raw = f"{SEED}:{namespace}:{value}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            stream.write("\n")


def main() -> None:
    args = parse_args()
    source_paths = {
        "corpus": args.source_dir / "corpus.parquet",
        "queries": args.source_dir / "queries.parquet",
        "qrels": args.source_dir / "qrels.parquet",
    }
    missing = [str(path) for path in source_paths.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"缺少上游 Parquet 文件: {missing}")

    corpus_rows = parquet.read_table(source_paths["corpus"]).to_pylist()
    query_rows = parquet.read_table(source_paths["queries"]).to_pylist()
    qrel_rows = parquet.read_table(source_paths["qrels"]).to_pylist()

    query_text = {str(row["_id"]): str(row["text"]) for row in query_rows}
    relevant_by_query: dict[str, set[str]] = {}
    for row in qrel_rows:
        if int(row["score"]) <= 0:
            continue
        query_id = str(row["query-id"])
        corpus_id = str(row["corpus-id"])
        relevant_by_query.setdefault(query_id, set()).add(corpus_id)

    eligible_queries = [query_id for query_id in relevant_by_query if query_id in query_text]
    eligible_queries.sort(key=lambda value: stable_rank("query", value))
    selected_queries = eligible_queries[: args.query_count]
    if len(selected_queries) != args.query_count:
        raise ValueError(f"可用 query 只有 {len(selected_queries)} 条，少于要求的 {args.query_count}")

    corpus_index = {str(row["_id"]): index for index, row in enumerate(corpus_rows)}
    selected_document_ids: set[str] = set()
    for query_id in selected_queries:
        selected_document_ids.update(relevant_by_query[query_id])

    # T2 corpus 的相邻行经常来自相近主题。把相关文档附近的行加入子集，
    # 比只加入全局随机负例更能暴露“语义相近但并不相关”的排序错误。
    positive_ids = list(selected_document_ids)
    for document_id in positive_ids:
        center = corpus_index.get(document_id)
        if center is None:
            raise ValueError(f"qrels 引用不存在的 corpus-id: {document_id}")
        start = max(0, center - args.neighbor_radius)
        end = min(len(corpus_rows), center + args.neighbor_radius + 1)
        for index in range(start, end):
            selected_document_ids.add(str(corpus_rows[index]["_id"]))

    if len(selected_document_ids) > args.document_count:
        raise ValueError(
            "相关文档与相邻困难负例已经超过 document-count；"
            "请提高 --document-count，不能静默丢弃 qrels"
        )

    remaining_ids = [
        str(row["_id"]) for row in corpus_rows
        if str(row["_id"]) not in selected_document_ids
    ]
    remaining_ids.sort(key=lambda value: stable_rank("document", value))
    selected_document_ids.update(
        remaining_ids[: args.document_count - len(selected_document_ids)]
    )

    selected_source_rows = [
        row for row in corpus_rows if str(row["_id"]) in selected_document_ids
    ]
    selected_source_rows.sort(key=lambda row: int(str(row["_id"])))

    corpus_output: list[dict] = []
    truncated_count = 0
    for chunk_id, row in enumerate(selected_source_rows, start=1):
        content = str(row["text"]).strip()
        truncated = len(content) > args.max_content_chars
        if truncated:
            content = content[: args.max_content_chars]
            truncated_count += 1
        corpus_output.append({
            "chunkId": chunk_id,
            "documentId": int(str(row["_id"])),
            "documentVersionId": 1,
            "title": f"T2 corpus {row['_id']}",
            "content": content,
            "truncated": truncated,
        })

    cases_output = []
    for query_id in selected_queries:
        relevant_ids = sorted(int(value) for value in relevant_by_query[query_id])
        cases_output.append({
            "caseId": f"t2-{query_id}",
            "sourceQueryId": query_id,
            "query": query_text[query_id],
            "relevantDocumentIds": relevant_ids,
            "tags": ["public", "zh", "retrieval", "t2"],
        })

    args.output_dir.mkdir(parents=True, exist_ok=True)
    corpus_path = args.output_dir / "corpus.jsonl"
    cases_path = args.output_dir / "cases.jsonl"
    write_jsonl(corpus_path, corpus_output)
    write_jsonl(cases_path, cases_output)

    manifest = {
        "datasetId": "t2-retrieval-public-v1",
        "sourceDataset": SOURCE_DATASET,
        "sourceRevision": SOURCE_REVISION,
        "license": "Apache-2.0",
        "selectionSeed": SEED,
        "queryCount": len(cases_output),
        "documentCount": len(corpus_output),
        "positiveDocumentCount": len(set().union(
            *(relevant_by_query[query_id] for query_id in selected_queries)
        )),
        "neighborRadius": args.neighbor_radius,
        "maxContentChars": args.max_content_chars,
        "truncatedDocumentCount": truncated_count,
        "sourceFiles": {
            name: {"sha256": sha256_file(path), "bytes": path.stat().st_size}
            for name, path in source_paths.items()
        },
        "generatedFiles": {
            "corpus.jsonl": {"sha256": sha256_file(corpus_path), "bytes": corpus_path.stat().st_size},
            "cases.jsonl": {"sha256": sha256_file(cases_path), "bytes": cases_path.stat().st_size},
        },
        "evidenceBoundary": (
            "公开 T2Retrieval 固定子集，用于项目内 A/B；"
            "不是官方全量 C-MTEB 排行榜结果，也不能替代课程业务 Golden Dataset"
        ),
    }
    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

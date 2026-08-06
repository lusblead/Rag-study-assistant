#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成公开可复现的无密钥检索 Smoke Fixture。

内容全部为自行编写的虚构技术文档，不复制 T2、Obsidian 或其他私有文本；
向量为固定种子生成的确定性 64 维单位向量，不依赖外部 Embedding API。

用法（从仓库根目录）：
    python -X utf8 backend/evals/scripts/generate_retrieval_smoke_fixture.py

输出目录：backend/src/test/resources/eval-fixtures/retrieval-smoke/
"""

from __future__ import annotations

import hashlib
import json
import math
import random
from pathlib import Path


FIXTURE_ID = "retrieval-smoke-v1"
SCHEMA_VERSION = 1
DIMENSION = 64
SEED = 20260806
# parents[0]=scripts, parents[1]=evals, parents[2]=backend；再拼 src/test/resources。
OUT_DIR = Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "eval-fixtures" / "retrieval-smoke"


def unit(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0.0:
        raise ValueError("zero vector")
    return [v / norm for v in vector]


def random_unit(rng: random.Random) -> list[float]:
    return unit([rng.uniform(-1.0, 1.0) for _ in range(DIMENSION)])


def blend(base: list[float], noise: list[float], base_weight: float) -> list[float]:
    return unit([base_weight * b + (1.0 - base_weight) * n for b, n in zip(base, noise)])


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    rng = random.Random(SEED)
    doc_a = random_unit(rng)   # 虚构网关部署手册
    doc_b = random_unit(rng)   # 虚构任务队列手册
    doc_c = random_unit(rng)   # 虚构缓存手册
    doc_topics = {
        "虚构网关部署手册.md": doc_a,
        "虚构任务队列手册.md": doc_b,
        "虚构缓存手册.md": doc_c,
    }

    chunks = [
        # doc_a：网关
        (1, "虚构网关部署手册.md", "网关默认超时", "虚构网关默认请求超时时间为 3 秒。超过 3 秒未收到上游响应时，网关返回 504 并记录超时指标。"),
        (2, "虚构网关部署手册.md", "网关重试", "虚构网关对幂等请求最多重试 2 次，重试间隔依次为 500 毫秒和 1000 毫秒。非幂等请求不自动重试。"),
        (3, "虚构网关部署手册.md", "网关限流", "虚构网关按租户维度限流，默认每秒 100 个请求。超过限流阈值时返回 429 并附带 Retry-After 响应头。"),
        (4, "虚构网关部署手册.md", "网关健康检查", "虚构网关每 30 秒向上游执行一次健康检查，连续失败 3 次后将该上游标记为不健康并暂停转发。"),
        # doc_b：队列
        (5, "虚构任务队列手册.md", "队列超时", "虚构任务队列默认任务处理超时为 60 秒。超时任务会被判定为失败并进入重试队列。"),
        (6, "虚构任务队列手册.md", "队列重试", "虚构任务队列对失败任务最多重试 3 次，退避策略为 1 秒、2 秒、4 秒。重试耗尽后进入死信队列。"),
        (7, "虚构任务队列手册.md", "队列 Worker", "虚构任务队列使用固定数量的 Worker 消费任务。Worker 通过租约机制防止同一任务被并发消费。"),
        (8, "虚构任务队列手册.md", "队列死信", "虚构任务队列的死信队列保留任务原始载荷和失败原因，供人工排查后重新入队或归档。"),
        # doc_c：缓存
        (9, "虚构缓存手册.md", "缓存 TTL", "虚构缓存默认 TTL 为 5 分钟。过期键在读取时惰性淘汰，并在后台定期清理过期数据。"),
        (10, "虚构缓存手册.md", "缓存淘汰", "虚构缓存达到容量上限时按最近最少使用策略淘汰键，不区分键的创建时间。"),
        (11, "虚构缓存手册.md", "缓存一致性", "虚构缓存采用先更新数据库再失效缓存的顺序，失效失败时通过延迟双删兜底保证最终一致。"),
        (12, "虚构缓存手册.md", "分布式锁", "虚构缓存提供基于 SETNX 的分布式锁，默认锁超时 30 秒，持有方需在任务结束后主动释放。"),
    ]

    chunk_noise = {cid: random_unit(rng) for cid, *_ in chunks}
    chunk_vectors: dict[int, list[float]] = {}
    for cid, doc, _title, _content in chunks:
        chunk_vectors[cid] = blend(doc_topics[doc], chunk_noise[cid], 0.75)

    cases = [
        {
            "caseId": "fixture_0001",
            "query": "虚构网关默认请求超时是多少秒？",
            "questionType": "single_chunk",
            "answerable": True,
            "relevantChunkIds": [1],
            "acceptableChunkIds": [1],
            "sourceDocuments": ["虚构网关部署手册.md"],
            "confusingChunkIds": [],
            "targetTopic": "虚构网关部署手册.md",
        },
        {
            "caseId": "fixture_0002",
            "query": "任务处理多久后会被判定为失败？",
            "questionType": "paraphrase",
            "answerable": True,
            "relevantChunkIds": [5],
            "acceptableChunkIds": [5, 6],
            "sourceDocuments": ["虚构任务队列手册.md"],
            "confusingChunkIds": [],
            "targetTopic": "虚构任务队列手册.md",
        },
        {
            "caseId": "fixture_0003",
            "query": "任务重试耗尽后系统如何处理？",
            "questionType": "multi_evidence",
            "answerable": True,
            "relevantChunkIds": [6, 8],
            "acceptableChunkIds": [6, 8],
            "sourceDocuments": ["虚构任务队列手册.md"],
            "confusingChunkIds": [],
            "targetTopic": "虚构任务队列手册.md",
        },
        {
            "caseId": "fixture_0004",
            "query": "缓存里的 TTL 是多久？",
            "questionType": "confusing",
            "answerable": True,
            "relevantChunkIds": [9],
            "acceptableChunkIds": [9],
            "sourceDocuments": ["虚构缓存手册.md"],
            "confusingChunkIds": [10],
            "targetTopic": "虚构缓存手册.md",
        },
        {
            "caseId": "fixture_0005",
            "query": "分布式锁默认持有上限是多少毫秒？",
            "questionType": "dense_miss",
            "answerable": True,
            "relevantChunkIds": [12],
            "acceptableChunkIds": [12],
            "sourceDocuments": ["虚构缓存手册.md"],
            "confusingChunkIds": [],
            # 故意指向错误主题，使 Dense 相似度低，用于暴露 Dense 漏召回边界。
            "targetTopic": "虚构网关部署手册.md",
        },
        {
            "caseId": "fixture_0006",
            "query": "本地资料是否提供了该项目的真实线上用户满意度百分比？",
            "questionType": "unanswerable",
            "answerable": False,
            "relevantChunkIds": [],
            "acceptableChunkIds": [],
            "sourceDocuments": [],
            "confusingChunkIds": [1, 5],
            "targetTopic": "虚构网关部署手册.md",
        },
        {
            "caseId": "fixture_0007",
            "query": "网关限流和缓存淘汰分别使用什么策略？",
            "questionType": "multi_evidence",
            "answerable": True,
            "relevantChunkIds": [3, 10],
            "acceptableChunkIds": [3, 10],
            "sourceDocuments": ["虚构网关部署手册.md", "虚构缓存手册.md"],
            "confusingChunkIds": [],
            "targetTopic": "虚构网关部署手册.md",
        },
        {
            "caseId": "fixture_0008",
            "query": "缓存容量写满后如何决定删除哪些键？",
            "questionType": "paraphrase",
            "answerable": True,
            "relevantChunkIds": [10],
            "acceptableChunkIds": [10],
            "sourceDocuments": ["虚构缓存手册.md"],
            "confusingChunkIds": [9],
            "targetTopic": "虚构缓存手册.md",
        },
    ]

    query_noise = {case["caseId"]: random_unit(rng) for case in cases}
    query_vectors: dict[str, list[float]] = {}
    for case in cases:
        topic = doc_topics[case["targetTopic"]]
        query_vectors[case["caseId"]] = blend(topic, query_noise[case["caseId"]], 0.55)

    out = OUT_DIR
    out.mkdir(parents=True, exist_ok=True)

    corpus_lines = []
    for cid, doc, title, content in chunks:
        corpus_lines.append(
            json.dumps(
                {
                    "chunkId": cid,
                    "documentId": {"虚构网关部署手册.md": 101, "虚构任务队列手册.md": 102, "虚构缓存手册.md": 103}[doc],
                    "title": title,
                    "content": content,
                    "sourceDocument": doc,
                },
                ensure_ascii=False,
            )
        )
    corpus_text = "\n".join(corpus_lines) + "\n"

    case_lines = []
    for case in cases:
        stripped = dict(case)
        stripped.pop("targetTopic", None)
        case_lines.append(json.dumps(stripped, ensure_ascii=False))
    case_text = "\n".join(case_lines) + "\n"

    embedding_lines = []
    for cid, vector in chunk_vectors.items():
        embedding_lines.append(json.dumps({"key": f"chunk:{cid}", "vector": [round(v, 6) for v in vector]}, ensure_ascii=False))
    for case in cases:
        vector = query_vectors[case["caseId"]]
        rounded = [round(v, 6) for v in vector]
        embedding_lines.append(json.dumps({"key": f"query:{case['caseId']}", "vector": rounded}, ensure_ascii=False))
        embedding_lines.append(json.dumps({"key": f"querytext:{case['query']}", "vector": rounded}, ensure_ascii=False))
    embedding_text = "\n".join(embedding_lines) + "\n"

    # 使用 write_bytes 固定 LF 换行，避免 Windows 把 \n 转成 \r\n 导致哈希漂移。
    (out / "corpus.jsonl").write_bytes(corpus_text.encode("utf-8"))
    (out / "cases.jsonl").write_bytes(case_text.encode("utf-8"))
    (out / "embeddings.jsonl").write_bytes(embedding_text.encode("utf-8"))

    manifest = {
        "fixtureId": FIXTURE_ID,
        "schemaVersion": SCHEMA_VERSION,
        "corpusSha256": sha256_text(corpus_text),
        "caseSha256": sha256_text(case_text),
        "embeddingDimension": DIMENSION,
        "metric": "recall@1/3/5/10,mrr,ndcg@10,sourceCoverage,evidenceGroupCoverage,unanswerablePrecision",
        "expectedCaseCount": len(cases),
        "expectedChunkCount": len(chunks),
        "source": "self-authored synthetic technical fiction, no external text copied",
    }
    (out / "manifest.json").write_bytes(
        (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    print(f"fixture generated: {out}")
    print(f"chunks={len(chunks)} cases={len(cases)} dimension={DIMENSION}")


if __name__ == "__main__":
    main()

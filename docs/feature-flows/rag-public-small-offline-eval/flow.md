---
feature_id: "rag.public-small-offline-eval"
title: "Public small offline retrieval evaluation"
status: "implemented"
---

# Public small offline retrieval evaluation

## Purpose

在不读取私有语料、不使用密钥、网络、真实 Embedding 或 LLM 的前提下，校验版本化公开小型数据集，并在完全相同的精确余弦候选上比较无 Rerank 与当前本地词法 Rerank，生成可重复的逐 case Markdown 报告。

本流程是离线组件评测，只证明固定合成样例上的指标实现、Rerank 接线和回归稳定性；不代表真实课程检索质量、线上索引、生成质量或生产验收。

## Entry, preconditions, and terminal outcomes

- Entry: 从仓库根目录运行无密钥 PowerShell A/B 命令。
- Preconditions: 数据文件、manifest 和 checksum 一致；ID 与行顺序稳定；字段引用闭合；公开内容通过隐私门禁。
- Success outcome: 两个方案在同一候选集上完成，重复运行逐字节一致，并生成包含配置、逐 case、总体指标和退化样例的 Markdown 报告。
- Rejection outcome: 数据缺失、哈希漂移、结构错误、排序不稳定或隐私命中时，命令非零退出且不接受报告。
- Failure outcome: 两方案输入配置不同、指标计算失败或重复运行漂移时，命令非零退出并保留可诊断测试错误。

## Runtime flow

```mermaid
flowchart TD
    E1(["E1 运行公开小型检索 A/B 命令"]) --> D1{"D1 数据结构、校验和、排序与隐私门禁均通过"}
    D1 -->|否| X1(["X1 拒绝运行，不接受报告"])
    D1 -->|是| A1["A1 用固定向量生成精确余弦候选并稳定排序"]
    A1 --> A2["A2 为 A/B 复制同一候选列表与固定配置"]
    A2 --> A3["A3 方案 A 使用 NoOp Rerank"]
    A2 --> A4["A4 方案 B 使用本地词法 Rerank"]
    A3 --> A5["A5 计算逐 case 与总体通用检索指标"]
    A4 --> A5
    A5 --> D2{"D2 两次运行的排序、指标与报告正文完全一致"}
    D2 -->|否| X2(["X2 判定不可复现并失败"])
    D2 -->|是| A6["A6 写入固定 Markdown A/B 报告"]
    A6 --> X3(["X3 离线 A/B 报告完成"])
    G1[["G1 隐私与来源门禁"]] -.-> D1
    G2[["G2 Manifest、Checksum 与稳定排序门禁"]] -.-> D1
    G2 -.-> A1
    G3[["G3 单变量配对比较保证"]] -.-> A2
    G3 -.-> A3
    G3 -.-> A4
    G4[["G4 证据边界与报告完整性保证"]] -.-> A6
```

## Component sequence

```mermaid
sequenceDiagram
    actor User
    participant Script as PowerShell runner
    participant Test as JUnit A/B harness
    participant Dataset as Public dataset validator
    participant Candidate as Exact cosine candidate builder
    participant A as NoOpKnowledgeReranker
    participant B as LocalLexicalKnowledgeReranker
    participant Metrics as RetrievalMetricsCalculator
    participant Report as Markdown report

    User->>Script: run public-small-v1 A/B
    Script->>Test: Maven targeted test with fixed output path
    Test->>Dataset: validate manifest, checksums, schema, order, privacy
    alt validation rejected
        Dataset-->>Test: deterministic validation error
        Test-->>Script: non-zero result
        Script-->>User: failure without accepted report
    else validation accepted
        Dataset-->>Test: frozen corpus, cases and vectors
        Test->>Candidate: exact cosine search with fixed candidateK
        Candidate-->>Test: one stable candidate list per case
        par scheme A
            Test->>A: rerank same candidates and topK
            A-->>Test: original candidate order
        and scheme B
            Test->>B: rerank same candidates and topK
            B-->>Test: lexical reranked order
        end
        Test->>Metrics: calculate case and aggregate metrics
        Metrics-->>Test: paired metrics and regressions
        Test->>Test: repeat full evaluation and compare deterministic body
        Test->>Report: write validated Markdown
        Report-->>Script: report path
        Script-->>User: success and evidence boundary
    end
```

## State lifecycle

本流程不修改业务持久化状态。唯一输出是可重新生成的 Markdown 报告，因此不建立业务状态机。

## Safeguards

- G1：只接受声明为中性原创虚构技术文本的数据；公开数据文件自动拒绝用户绝对路径、邮箱、手机号、疑似凭据和私有知识库标识。
- G2：核心文件 SHA-256、数量、ID 唯一性、引用闭合、固定 seed 和稳定排序全部一致后才运行。
- G3：A/B 共用同一 corpus、cases、向量、精确余弦候选、candidateK、topK 和 seed，唯一变量是 Reranker 实现。
- G4：报告必须列出配置、逐 case 排名与指标、总体指标、改善/退化样例，并明确 fixture 结果不代表真实或生产效果。

## Failure, recovery, and observability

流程没有网络调用、重试和补偿。任何校验、指标或确定性断言失败都由 Maven 返回非零退出码；修正稳定输入或评测逻辑后重跑即可。报告是派生工件，不是 source of truth；数据集、manifest、checksum、评测器版本和复现命令共同构成归因依据。

## Implementation notes

机器可读的代码与测试映射维护在 `traceability.yaml`。

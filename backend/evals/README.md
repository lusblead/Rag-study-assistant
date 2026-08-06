# RAG 评测（Rag-study-assistant）

本目录存放评测脚本与数据集挂载说明。评测分为三条路径，边界必须严格区分。

## 1. 三条评测路径

| 路径 | 数据来源 | API Key | 目标 | 是否可以代表真实效果 |
|---|---|---|---|
| A. Fixture Smoke | `backend/src/test/resources/eval-fixtures/retrieval-smoke/`（虚构技术文档 + 确定性预生成向量） | 不需要 | clone 后验证评测代码、Milvus、指标与报告链路可运行 | 否 |
| B. Public T2 | `mteb/T2Retrieval` 固定 revision 子集（`prepare_t2_subset.py` 生成） | 需要 | 验证真实 Embedding 与 Milvus 链路，作为公共检索参考 | 仅子集参考，非官方全量 |
| C. Local Obsidian | 外置私有 reviewed 数据集（`DatasetDir` 挂载 + Manifest/Checksum 校验） | 需要 | 受控业务检索评测 | 受控业务参考，非生产装配 |

## 2. 必需软件

- Java（JDK 21+；`JAVA_HOME` 必须设置，脚本不再硬编码本机路径）
- Maven（使用 `backend/mvnw` 或系统 `mvn`）
- Docker（临时 Milvus 2.4.11 由 Compose 启动）

## 3. A. Fixture Smoke（无密钥，clone 后可运行）

```powershell
& 'backend\evals\scripts\run_retrieval_fixture_smoke.ps1'
```

说明：

- 不读取项目 MySQL、不要求 API Key、不调用外部 Embedding；
- 使用固定种子预生成的 64 维向量 + 临时 Milvus FLAT；
- 报告输出到 `backend/evals/runs/`；
- 报告包含 overall/cases、case 数校验，并断言不含 API Key 敏感字段；
- 该结果不代表真实检索质量，不得写入简历或作为效果数字。

## 4. B. Public T2 Evaluation（需要 API Key）

准备数据：

```powershell
# 先按 prepare_t2_subset.py 的说明下载固定 revision 的 Parquet 并生成子集
& '.cache\eval-venv\Scripts\python.exe' -X utf8 `
  'backend\evals\scripts\prepare_t2_subset.py' `
  --source-dir '.cache\eval-data\t2' `
  --output-dir 'backend\evals\datasets\t2-retrieval-public-v1'
```

运行：

```powershell
& 'backend\evals\scripts\run_public_retrieval_eval.ps1'
```

说明：

- 脚本默认从项目 MySQL 读取已保存的 Embedding 配置；也可显式设置
  `RAG_EVAL_EMBEDDING_BASE_URL`、`RAG_EVAL_EMBEDDING_MODEL`、`RAG_EVAL_EMBEDDING_API_KEY`；
- 缺少 API Key 或数据集不存在时 fail fast，不会静默跳过；
- API Key 只存在于父子进程环境，不写入命令参数、日志或报告；
- 当前固定 100 query/2,000 document 子集结果**不是官方全量 C-MTEB 成绩**。

## 5. C. Local Obsidian Evaluation（需要外置数据集 + API Key）

```powershell
& 'backend\evals\scripts\run_local_obsidian_retrieval_eval.ps1' `
  -DatasetDir 'D:\EvalDatasets\local-obsidian-v3-reviewed'
```

说明：

- 数据集不进入仓库；挂载目录必须包含 `corpus.jsonl`、case 文件与 `manifest.json`；
- 执行前校验 corpus/case 存在、SHA-256 匹配、case 数与 chunk 数匹配；
  Checksum 不匹配时**拒绝执行**；
- Manifest 模板见
  [`datasets/local-obsidian-v3-reviewed.manifest.example.json`](datasets/local-obsidian-v3-reviewed.manifest.example.json)，
  挂载说明见 [`datasets/README.md`](datasets/README.md)。

## 6. 哪些数据不会进入仓库

- 私有 Obsidian Dataset（`backend/evals/datasets/*`，除 README 与 `*.example.json`）；
- 运行报告（`backend/evals/runs/`）；
- 汇总报告与 case 明细 CSV（`backend/evals/reports/`）；
- Embedding 缓存（`.cache/`）；
- 仓库根目录旧 `eval/` 数据目录与 `*.zip`。

评测 Java 类（`LocalObsidianRetrievalEvalTest`、`PublicRetrievalEvalTest`、
`RetrievalFixtureSmokeTest`、`EvalFixtureSupport`）与脚本会提交入库。

## 7. 报告输出位置

- Fixture Smoke：`backend/evals/runs/<timestamp>-fixture-smoke.json`
- Public T2：`backend/evals/runs/<timestamp>-<mode>-t2-public-bge-m3-milvus.json`
- Local Obsidian：`backend/evals/runs/<timestamp>-<mode>-local-obsidian-v3-bge-m3-milvus.json`

报告均为不含密钥的 JSON；`providerUsage` 记录 request/retry/tokens，
`configuration` 记录模型、索引类型、candidateK/topK、query mode。

## 8. 当前指标边界

- Fixture Smoke：只证明链路可运行，指标无效果含义；
- Public T2：当前为固定子集，候选集偏小，排序指标偏高，不能冒充官方全量；
- Local Obsidian：`reviewed 100` 为受控业务检索集，审核者为 ChatGPT；
  不是完整 Golden RAG Dataset，也不能代表生产装配效果；
- 三条路径都未评测 RAG 生成、引用、Grounding、拒答与端到端答案。

## 9. 常见故障

| 故障 | 处理 |
|---|---|
| Docker 不可用 | 启动 Docker Desktop；`docker info` 通过后再运行 |
| `JAVA_HOME` 缺失 | 设置 `JAVA_HOME` 指向 JDK 21+；脚本会明确报错 |
| API Key 缺失 | 设置 `RAG_EVAL_EMBEDDING_API_KEY`（Public/Local）；Fixture Smoke 不需要 |
| Dataset 缺失 | Public：先运行 `prepare_t2_subset.py`；Local：挂载外置目录并校验 manifest |
| Checksum 不匹配 | 校验 manifest 的 SHA-256 与真实文件一致；拒绝执行 |
| Milvus 未就绪 | 脚本等待 `/healthz` 90 秒；仍失败时查看 `docker compose logs` |

## 10. 边界声明

- Fixture Smoke 不代表真实检索质量；
- Public T2 固定子集不是官方全量成绩；
- Local Obsidian 数据集不进入仓库；
- 所有真实路径都要求 API Key，且 Key 不落盘、不进日志和报告。

# 评测数据集挂载说明

本目录**不包含任何私有评测数据**。评测数据分为三类：

1. **公开合成 Fixture**（可入库）：位于
   `backend/src/test/resources/eval-fixtures/retrieval-smoke/`，全部为自行编写的虚构
   技术文档与确定性预生成向量，clone 后即可运行无密钥 Smoke。
2. **外置私有 Dataset**（不入库）：例如本地 Obsidian v3 reviewed 数据集，通过
   `DatasetDir` 挂载，并必须提供 Manifest 与 Checksum 校验。
3. **版本化公开小型 Dataset**（可入库）：`public-small-v1/` 只包含原创中性虚构
   技术文本、固定向量、来源/许可证说明、Manifest 和 Checksum，用于无密钥 A/B 回归。

`public-small-v1` 与外置私有 Dataset 完全隔离。其内容不是对私有语料做脱敏替换，
也不能用作业务质量或生产效果证据。

## 外置 Dataset 挂载协议

1. 把私有数据集放到仓库外的目录（例如 `D:\EvalDatasets\local-obsidian-v3-reviewed`），
   目录内至少包含：
   - `corpus.jsonl`
   - `standard_reviewed_100_retrieval.jsonl`（或 `run_local_obsidian_retrieval_eval.ps1`
     的 `-CaseFile` 指定文件）
   - `manifest.json`（按下方模板生成）
2. 以 `DatasetDir` 参数挂载：

```powershell
& 'backend\evals\scripts\run_local_obsidian_retrieval_eval.ps1' `
  -DatasetDir 'D:\EvalDatasets\local-obsidian-v3-reviewed'
```

3. 执行前脚本与评测类会校验：
   - corpus 文件存在；
   - case 文件存在；
   - corpus/case 的 SHA-256 与 manifest 一致；
   - case 数与 manifest 一致；
   - chunk 数与 manifest 一致。
   任何一项不匹配都会拒绝执行，不会只打印 Warning 后继续。

## Manifest 模板

见 `local-obsidian-v3-reviewed.manifest.example.json`。实际挂载时必须把
`corpusSha256`、`retrievalCaseSha256`、`expectedChunkCount`、`expectedCaseCount`
替换为真实值，并保留 `localMountPath` 为示例路径（该字段仅用于说明，不参与校验）。

## 为什么不把私有数据入库

- 本地 Obsidian 语料可能包含个人笔记、未公开文档与隐私内容；
- 数据集体积大且与课程/私有知识绑定，不适合公开分发；
- 通过 Manifest/Checksum 挂载可以保证可复现性的同时保护数据。

## Step 3.2 Answerability 决策集

生成前 `ANSWER / CLARIFY / REFUSE` 评估使用独立的外置私有数据集，不能复用
二分类 Retrieval answerability 标签冒充三分类决策真值。结构模板见
`local-answerability-v1.manifest.example.json`；实际数据至少包括经人确认的
`dev.cases.jsonl` 与同一 pipeline 冻结得到的 `dev.snapshots.jsonl`，并覆盖无召回、
低相关、单证据、多证据一致、多证据冲突、模糊问题和知识库无答案七类场景。

模板中的哈希、pipeline fingerprint、score kind、review protocol 与所有 quality gate
都是待用户预提交的占位，不是默认业务结论。只有替换为真实值、每条 case 标为
`HUMAN_CONFIRMED / HUMAN` 且结构门禁通过后，才允许运行
`run_answerability_dev_sweep.ps1`。仓库内 `src/test/resources/evaluation/decision/`
全部是 `FIXTURE_ONLY`，只能证明指标算术和失败边界。

## 数据质量检查

`backend/evals/scripts/check_dataset_quality.py` 提供 `public-small-v1` 与
`reviewed-local-v1` 两个离线 profile。公开数据必须同时满足 Manifest、
`checksums.sha256`、来源与许可证声明以及零隐私/凭据命中；私有 reviewed 数据会校验
完整 100 与 Dev/Test 45/55 的成员和 canonical row 一致性。私有路径与联系方式只按
类别计数，疑似凭据仍是硬失败；未通过 `--human-reviewer` 显式声明的 reviewer 不计入
confirmed human review。重复 `chunk_business_key` 作为待复核的数据重复候选聚合报告，
不会仅凭该重复升级为身份冲突硬失败。

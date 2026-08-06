# 评测数据集挂载说明

本目录**不包含任何私有评测数据**。评测数据分为两类：

1. **公开合成 Fixture**（可入库）：位于
   `backend/src/test/resources/eval-fixtures/retrieval-smoke/`，全部为自行编写的虚构
   技术文档与确定性预生成向量，clone 后即可运行无密钥 Smoke。
2. **外置私有 Dataset**（不入库）：例如本地 Obsidian v3 reviewed 数据集，通过
   `DatasetDir` 挂载，并必须提供 Manifest 与 Checksum 校验。

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


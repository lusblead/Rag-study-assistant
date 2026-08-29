# Retrieval evaluation plan

## Decision and evaluated unit

本轮离线决策是：在冻结的公开合成样例上，当前本地词法 Rerank 相比不做 Rerank
如何改变候选顺序和检索指标，以及这些变化能否稳定复现。

评测单位是一条 query 对应的一次 Top K chunk 排名。它是 retrieval/rerank 组件评测，
不是生成、引用、Grounding、对话或端到端业务评测。

## System map

```text
版本化原创 corpus/cases/固定向量
→ Manifest、Checksum、结构、排序与隐私门禁
→ 内存 exact-cosine 候选生成
→ A: NoOp Rerank / B: Local Lexical Rerank
→ 通用检索指标
→ 配对改善/退化分析
→ 固定 Markdown 报告
```

真实应用中的解析、切块、Embedding Provider、Milvus 写入与可见性、查询改写、生成和
引用均不在这条离线流程内。需要这些证据时分别运行 Fixture Smoke、Public T2、外置
业务数据集或受控端到端验收，不能从本报告外推。

## Frozen comparison contract

- Dataset：`public-small-v1`，版本、seed、核心文件哈希和数量写入 manifest。
- Candidate index：内存精确余弦；score 降序，chunk ID 升序同分。
- Candidate K：8。
- Top K：5。
- Scheme A：`NoOpKnowledgeReranker`。
- Scheme B：`LocalLexicalKnowledgeReranker(0.7,0.3)`。
- 唯一变量：Reranker 实现。
- 重试、采样、外部请求：均为 0。

## Case and grader contract

公开集覆盖单证据、多证据、相似概念区分、无答案、可替代证据和 Rerank 顺序变化。
每条可回答 case 定义严格相关 chunk、可接受替代 chunk、必要证据组和必要来源；无答案
case 的正向证据字段为空。

确定性 grader 计算：

- Recall@K、Precision@K、MRR、nDCG@K；
- 可接受替代证据版本的 Recall/MRR/nDCG；
- Source Coverage@K；
- Required Evidence Group Coverage@K；
- 无答案样例的误召回率与空结果准确率。

重复 chunk 在截断 K 前按首次出现去重。空结果和零分母返回 0，不产生 NaN。无答案
样例不进入可回答宏平均。

## Hard gates and reporting

以下为硬门禁：核心哈希与规模一致、ID 唯一且稳定排序、引用闭合、隐私扫描通过、两方案
除 Reranker 外配置完全相同、重复运行排名/指标/报告正文完全一致。任何门禁失败都拒绝
接受报告。

本小型合成集不设置“真实质量达标”阈值。报告必须保留逐 case 排名、总体指标、改善和
退化样例，并明确数字仅代表固定离线 fixture。

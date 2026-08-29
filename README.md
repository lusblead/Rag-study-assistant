# Rag-study-assistant

基于 RAG 的课程学习助手，包含课程管理、文档解析入库、知识库问答、AI 出题、题库练习、模型设置。

## 当前源码启动

前提：已安装 Docker Desktop。

```bash
start.bat
# 或：docker compose up -d --build
```

当前默认入口要求 Docker，不提供 portable 降级。2026-08-09 的一次性空库实测发现：当前工作区
镜像未在首个模型设置查询前自动执行 Flyway，后端会因缺表退出；显式迁移测试只能用于继续隔离
验收，不能视为启动已修复。修复前请以 backend 健康检查和日志为准，详情见
[隔离实战验收记录](docs/acceptance/runs/2026-08-09-RAG-ISOLATED-E2E.md)。

启动后访问：

```text
前端：http://localhost:5173
后端：http://localhost:8080
MySQL：localhost:3307
Milvus：localhost:19530
MinIO：http://localhost:9001
```

仓库根 `start.bat` 固定尝试启动完整 Docker 模式，不提供免 Docker 降级。详细前提、停止/日志
命令与模式边界见 [QUICKSTART.md](QUICKSTART.md)。

`release/Rag-study-assistant-one-click/` 是旧 portable 快照，缺少当前 `ingestionlab`
版本化摄取与 ACTIVE-version 主链，不能用于当前生产验收；其存在不表示 portable 已同步或已验收。

## 功能概览

- 课程 CRUD
- 文档上传、解析、删除
- 支持 TXT / Markdown / DOC / DOCX / PPTX / 普通文字版 PDF
- 文本切片与知识片段入库
- RAG 问答和 SSE 流式问答
- 多轮会话历史
- DeepSeek / OpenAI-compatible LLM
- OpenAI-compatible Embedding
- 本地检索、Milvus 检索和 rerank
- AI 出题并保存题库
- 练习提交、错题查询
- 前端页面配置模型和 API key

## 当前摄取与问答主链

`POST /api/documents/{id}/ingest` 与
`POST /api/agent/documents/{documentId}/ingest` 统一进入
`IngestApplicationService -> ReliableIngestSubmitter -> Outbox/Poller -> IngestJobWorker`。
只有核验通过的版本才切换为 ACTIVE；在线问答通过 `MilvusKnowledgeRetriever` 执行
ACTIVE-version 检索，再由 `DynamicKnowledgeReranker` 按运行时设置选择 none、local 或远端
Rerank。问答时序与当前空检索/模型超时边界见
[RAG 问答 Feature Flow](docs/feature-flows/rag-question-answer/flow.md)。

## 本地开发

需要本地安装 JDK 21、Maven、Node.js 20+。

前端：

```bash
cd frontend
npm install
npm run dev
```

后端：

```bash
cd backend
mvn spring-boot:run
```

后端启动前需要 MySQL + Milvus 已运行（可用 Docker Compose 启动基础设施）：

```bash
docker compose up -d mysql etcd minio milvus
```

生产端到端尚需逐层验收；源码存在、单元测试、H2/Mock 和历史报告不能替代真实
MySQL/Milvus/模型运行。执行清单见
[RAG 生产端到端验收清单](docs/acceptance/RAG_PRODUCTION_E2E_CHECKLIST.md)。

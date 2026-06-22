# Rag-study-assistant

基于 RAG 的课程学习助手，包含课程管理、文档解析入库、知识库问答、AI 出题、题库练习、模型设置。

## 一键启动

前提：已安装 Docker Desktop。

```bash
docker compose up -d --build
```

启动后访问：

```text
前端：http://localhost:5173
后端：http://localhost:8080
MySQL：localhost:3307
Milvus：localhost:19530
MinIO：http://localhost:9001
```

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

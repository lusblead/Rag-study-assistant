# Rag-study-assistant

基于 RAG 的课程学习助手，包含课程管理、文档解析入库、知识库问答、AI 出题、题库练习、模型设置和一键启动演示能力。

## 默认一键启动

默认交付方式不依赖 Docker Desktop。

前提：

- Windows 10/11
- 能联网

启动：

```text
双击 start.bat
```

启动后访问：

```text
http://localhost:8080
```

`start.bat` 会自动处理：

- 下载便携 JDK 21 到 `.runtime/`
- 如果缺少运行产物，下载便携 Node.js 20 并构建前端
- 使用 Maven Wrapper 构建后端
- 使用 portable profile 启动后端
- 由后端直接托管前端静态页面

portable 模式默认使用：

- H2 文件数据库，数据在 `data/`
- 本地向量/词法检索，不需要 Milvus
- 本地 LLM/Embedding 占位能力，可跑通演示流程
- 前端设置页可切换 DeepSeek、OpenAI-compatible 模型和 API Key

常用脚本：

```text
start.bat   启动应用
stop.bat    停止应用
status.bat  查看状态
logs.bat    查看日志
```

## 配置真实模型

启动后进入前端“设置”页面配置：

- LLM provider/base URL/model/API key
- Embedding provider/base URL/model/API key

也可以复制 `.env.example` 为 `.env` 后填写：

```env
LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-pro
LLM_API_KEY=你的DeepSeekKey

EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_API_KEY=你的SiliconFlowKey
EMBEDDING_DIMENSION=1024
```

API key 不会写入前端 localStorage；后端保存运行时模型配置。

## 重新打包

在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-release.ps1
```

输出：

```text
release/Rag-study-assistant-one-click.zip
```

打包脚本会构建：

- `app/backend.jar`
- `app/frontend/`

下载者解压后双击 `start.bat` 即可运行。

## Docker 可选部署

如果需要 MySQL + Milvus 的完整部署，仍可使用 Docker Compose：

```bash
docker compose up -d --build
```

Docker 模式服务：

```text
前端：http://localhost:5173
后端：http://localhost:8080
MySQL：localhost:3307
Milvus：localhost:19530
MinIO：http://localhost:9001
```

Docker 模式不是默认交付要求，只作为开发/完整部署选项保留。

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

前端：

```bash
cd frontend
npm install
npm run dev
```

后端 portable profile：

```bash
backend\mvnw.cmd -f pom.xml -pl backend spring-boot:run -Dspring-boot.run.profiles=portable
```

后端默认 MySQL/Milvus profile 可配合 Docker Compose 使用。

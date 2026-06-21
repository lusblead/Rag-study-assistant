# Rag-study-assistant

基于 RAG 的个人课程知识库与智能学习助手系统。

当前后端已支持：

- 课程 CRUD
- 文档上传、列表、删除
- TXT / Markdown / DOC / DOCX / PPTX / 普通文字版 PDF 解析
- 文本切片与知识片段入库
- Milvus 向量写入、搜索、删除
- DeepSeek / OpenAI-compatible 大模型答疑
- OpenAI-compatible 真实 Embedding
- 相似度阈值过滤、候选召回与 rerank 重排序
- 多轮会话历史保存
- RAG 答疑接口与 SSE 流式答疑接口
- AI 出题并保存题库
- 练习提交与错题查询
- 删除文档或课程时清理 MySQL 记录、上传文件和 Milvus 向量

## 一键启动

前提：电脑已安装 Docker Desktop。

如果使用默认真实模型配置，先在项目根目录创建 `.env`：

```env
DEEPSEEK_API_KEY=你的DeepSeekKey
SILICONFLOW_API_KEY=你的Embedding服务Key
```

默认语言模型：

```text
LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-pro
```

默认 Embedding 推荐：

```text
EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_BASE_URL=https://api.siliconflow.com/v1
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
```

`BAAI/bge-m3` 适合中文课程资料和较长文本检索。如果你换成别的 Embedding 模型，必须同步修改 `EMBEDDING_DIMENSION` 和 Milvus collection，避免向量维度不一致。

RAG 检索默认配置：

```text
RAG_TOP_K=5
RAG_CANDIDATE_K=20
RAG_SIMILARITY_THRESHOLD=0.2
RAG_HISTORY_LIMIT=8
```

Rerank 默认使用本地词项重排，离线可用：

```text
RERANK_PROVIDER=local
```

如果要使用 SiliconFlow 远程 rerank：

```env
RERANK_PROVIDER=siliconflow
RERANK_MODEL=BAAI/bge-reranker-v2-m3
RERANK_API_KEY=你的SiliconFlowKey
```

`RERANK_FAIL_OPEN=true` 时，远程 rerank 失败会自动退回本地 rerank，避免答疑接口直接失败。

在项目根目录执行：

```bash
docker compose up -d --build
```

启动后服务地址：

```text
前端页面：http://localhost:5173
后端 API：http://localhost:8080
MySQL：localhost:3307
Milvus：localhost:19530
MinIO 控制台：http://localhost:9001
```

默认 MySQL：

```text
数据库：rag_study_assistant
用户名：root
密码：root
```

Spring Boot 启动时会自动执行：

```text
backend/src/main/resources/db/schema.sql
```

## 本地开发启动

如果只想本地开发后端，可以先启动 MySQL 和 Milvus：

```bash
docker compose up -d mysql etcd minio milvus
```

再启动后端：

```bash
mvn spring-boot:run -pl backend
```

默认配置读取环境变量，未设置时使用本地默认值。

前端使用 Vue 3 + Vite。单独启动前端：

```bash
cd frontend
npm install
npm run dev
```

默认访问 `http://localhost:5173`，默认请求 `http://localhost:8080`。如需修改 API 地址，可以在前端设置页保存，也可以在启动前配置：

```bash
set VITE_API_BASE_URL=http://localhost:8080
```

## Agent 模式

默认：

```yaml
agent:
  mock: false
```

这会使用：

```text
MilvusVectorStoreService
OpenAiCompatibleEmbeddingClient
OpenAiCompatibleChatClient
```

默认需要真实 API Key，能够跑通真实 LLM 和真实 Embedding 链路。

如果只是快速调试、不想启动 Milvus，可以设置：

```bash
set AGENT_MOCK=true
```

或在 `application.yml` 中临时改成：

```yaml
agent:
  mock: true
```

Mock 模式会使用内存向量库，重启后向量会丢失。

如果想保留 Milvus 但不用真实 API，可以设置：

```bash
set LLM_PROVIDER=local
set EMBEDDING_PROVIDER=local
```

这会使用本地演示模型和本地 hash embedding，只适合调试流程，不适合最终展示效果。

## 最小接口流程

### 1. 创建课程

```http
POST /api/courses
Content-Type: application/json
```

```json
{
  "name": "计算机网络",
  "description": "测试课程",
  "term": "2026春"
}
```

### 2. 上传文档

```http
POST /api/documents/upload
Content-Type: multipart/form-data
```

参数：

```text
file: txt / md / pdf 文件
courseId: 课程 id
```

### 3. 触发 Agent 入库

```http
POST /api/agent/documents/{documentId}/ingest
```

重复调用该接口会先清理旧 chunks 和旧向量，再重新入库。

### 4. RAG 答疑

```http
POST /api/agent/chat
Content-Type: application/json
```

```json
{
  "courseId": 1,
  "sessionId": null,
  "question": "TCP 和 UDP 有什么区别？"
}
```

首次对话可以不传 `sessionId` 或传 `null`，响应会返回新的 `sessionId`。后续追问把这个 `sessionId` 带上即可使用多轮历史：

```json
{
  "courseId": 1,
  "sessionId": 3,
  "question": "它的适用场景有哪些？"
}
```

### 4.1 SSE 流式答疑

```http
POST /api/agent/chat/stream
Accept: text/event-stream
Content-Type: application/json
```

请求体同普通答疑。返回事件：

```text
event: session
data: {"sessionId":3}

event: references
data: 召回并重排后的引用片段

event: delta
data: 模型增量文本

event: done
data: [DONE]
```

### 4.2 会话历史

```http
GET /api/agent/chat/sessions?courseId=1
GET /api/agent/chat/sessions/{sessionId}/messages
DELETE /api/agent/chat/sessions/{sessionId}
```

### 5. AI 出题

```http
POST /api/agent/questions/generate
Content-Type: application/json
```

```json
{
  "courseId": 1,
  "requirement": "生成 3 道单选题，难度 medium"
}
```

### 6. 查询题库

```http
GET /api/questions?courseId=1
```

### 7. 提交练习

```http
POST /api/practice/submit
Content-Type: application/json
```

```json
{
  "courseId": 1,
  "questionId": 1,
  "userAnswer": "A"
}
```

## 不要提交的本地文件

仓库不应提交：

```text
uploads/
.cache/
target/
.env
Docker 数据卷目录
```

## 后续可增强

- 增加知识片段浏览和题目编辑 / 删除页面
- 增加扫描版 PDF OCR
- 增加简答题语义判分
- 增加 BM25 + 向量混合检索

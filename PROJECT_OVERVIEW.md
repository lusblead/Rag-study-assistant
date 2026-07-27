# RAG Study Assistant 项目功能与结构说明

> 本文依据当前仓库代码整理，快照日期：2026-07-05。接口细节以 Controller 与 `backend/docs/api-guide.md` 为准，运行参数以 `backend/src/main/resources/application.yml` 为准。

## 1. 项目定位

RAG Study Assistant 是面向课程资料的学习辅助系统。用户可以创建课程、上传课件，将文档解析为知识片段并建立向量索引，然后基于课程资料进行问答、生成题目和练习。

项目当前包含完整前端、后端、关系数据库和向量检索基础设施。后端采用 Maven 多模块结构；目前父工程仅声明 `backend` 子模块，`frontend` 是独立的 Vue/Vite 工程。

## 2. 已实现功能

### 2.1 课程管理

- 新增、查询、修改、删除课程。
- 支持按课程名称模糊查询。
- 文档、题目、练习记录、聊天会话均按课程组织。

### 2.2 文档与知识库

- 上传、下载/预览、查询和删除课程文档。
- 文件默认保存到 `uploads/documents/{courseId}/`，使用时间戳前缀避免重名。
- 单文件和单请求最大 100 MB。
- 支持 TXT、Markdown、DOC、DOCX、PPTX，以及可提取文字的 PDF；PDF 解析还预留了 Tesseract OCR 配置。
- 上传后可异步提交解析，状态流转为 `UPLOADED -> PARSING -> PARSED/FAILED`。
- 解析流程包括文档读取、文本清洗、文本切片、Embedding、知识片段入库和向量索引写入。
- 知识片段正文保存在关系数据库，向量标识与 Milvus 数据关联。
- 删除文档时清理文件、知识片段和对应向量数据。

### 2.3 RAG 课程问答

- 根据课程范围检索相关知识片段并调用语言模型回答。
- 同时提供普通 JSON 响应和 SSE 流式响应。
- 回答可返回引用片段，便于前端展示资料来源。
- 支持多轮会话、历史消息查询和会话删除。
- 检索参数包括候选数量、最终 Top-K、相似度阈值和历史消息数量。
- 支持本地词法/向量混合重排、外部重排服务和不重排模式，并可配置失败时放行。

### 2.4 题库与 AI 出题

- 支持单选题、多选题、判断题、简答题。
- 支持 `easy`、`medium`、`hard` 三种难度。
- 题目可关联来源知识片段、来源文档、知识点、章节标签和出题批次。
- 支持保存单题、批量保存、按课程/题型/难度查询及删除题目。
- 支持根据课程资料和用户要求生成题目。
- 支持以练习或考试模式创建出题批次，选择文档、题量、题型、难度及是否参考真实题目风格。
- 支持查询和删除出题批次，前端可将题目导出为可打印内容。

### 2.5 练习记录

- 提交题目答案并保存判定结果。
- 支持规则判题，并为 AI 判题结果预留 `grading_mode` 与 `grading_feedback` 字段。
- 支持按课程查询全部练习记录和错题记录。

### 2.6 模型与重排设置

- 可通过前端设置语言模型、Embedding 模型和重排服务。
- 支持 DeepSeek 和其他 OpenAI-compatible 接口。
- API Key 在后端设置表中保存；前端本地存储会清空 Key 字段，避免将明文 Key 持久化到浏览器设置中。
- 支持分别测试 LLM、Embedding 和 Rerank 配置。
- 提供 Mock、本地占位实现，便于不接入外部 AI 服务时调试流程。

### 2.7 前端页面

- 首页：项目入口和课程选择。
- 知识库：课程维护、拖拽上传、解析状态轮询、文档预览与删除。
- 智能问答：流式/非流式问答、引用展示、会话历史管理。
- 练习中心：AI 出题、批次管理、题目筛选、答题、错题与练习记录、打印导出。
- 设置：后端地址、LLM、Embedding、Rerank 参数配置和连通性测试。
- 帮助：使用说明。

## 3. 核心业务流程

### 3.1 文档入库

```text
创建课程
  -> 上传文件并创建 documents 记录（UPLOADED）
  -> 提交异步解析任务（PARSING）
  -> 按文件类型解析文本
  -> 清洗并切分文本
  -> 写入 knowledge_chunks
  -> 生成 Embedding 并写入本地实现或 Milvus
  -> 更新文档状态与切片数量（PARSED/FAILED）
```

### 3.2 RAG 问答

```text
用户选择课程并提问
  -> 保存/读取会话历史
  -> 对问题生成向量
  -> 从课程知识片段中召回候选
  -> 可选重排与阈值过滤
  -> 拼装课程上下文和历史消息
  -> 调用 LLM
  -> 返回答案、引用和会话 ID
```

### 3.3 出题与练习

```text
选择课程、文档和出题要求
  -> 读取相关知识片段
  -> 调用 LLM 生成结构化题目
  -> 创建 question_batches 并保存 questions
  -> 用户答题
  -> 判题并写入 practice_records
  -> 查询练习历史或错题
```

## 4. 仓库结构

```text
Rag-study-assistant/
├── pom.xml                         Maven 父 POM，仅聚合 backend
├── backend/
│   ├── pom.xml                     Spring Boot 后端依赖与构建配置
│   ├── docs/                       API 与配置说明
│   └── src/
│       ├── main/java/com/rag/backend/
│       │   ├── common/             统一响应、业务异常、文本解码
│       │   ├── config/             CORS、MVC、MyBatis、异步任务、迁移配置
│       │   ├── course/             课程管理
│       │   ├── document/           文档上传、查询、下载、异步入库
│       │   ├── question/           题库、章节标签、批量出题
│       │   ├── practice/           答题与练习记录
│       │   ├── rerank/             运行时重排设置
│       │   └── agent/              RAG、解析、切片、向量、模型、会话等 AI 能力
│       ├── main/resources/
│       │   ├── application.yml     默认 MySQL/Milvus/模型配置
│       │   └── db/
│       │       ├── schema.sql      MySQL 建表脚本
│       │       └── schema-h2.sql   H2 建表脚本
│       └── test/                    后端测试
├── frontend/
│   ├── src/
│   │   ├── pages/                  首页、知识库、问答、练习、设置、帮助
│   │   ├── components/             通用 UI 组件
│   │   ├── api.ts                  REST/SSE 客户端及前端设置
│   │   ├── types.ts                TypeScript 数据类型
│   │   └── styles.css              全局样式
│   ├── package.json                Vue/Vite 构建与测试脚本
│   └── vite.config.ts              开发与打包配置
├── sql/init.sql                    手工初始化数据库脚本
├── docker-compose.yml              MySQL、Milvus、MinIO、前后端编排
├── scripts/                        Windows 启停、状态、日志、发布脚本
├── start.bat / stop.bat            一键启动与停止入口
└── QUICKSTART.md                   一键包使用说明
```

## 5. 后端分层与模块职责

常规业务模块采用以下调用关系：

```text
Controller -> Service 接口 -> ServiceImpl -> MyBatis Mapper -> 数据库
```

| 包 | 主要职责 |
|---|---|
| `common` | `Result<T>` 统一响应、`BizException`、全局异常处理、文本编码识别 |
| `config` | 跨域、静态资源、MyBatis、Jackson、上传目录、异步线程池、兼容性迁移 |
| `course` | 课程 CRUD 和其他模块的课程存在性校验 |
| `document` | 文件生命周期、文档元数据、异步解析任务编排 |
| `question` | 题目 CRUD、批量出题、批次与来源片段关联 |
| `practice` | 答案提交、判题、练习记录和错题查询 |
| `rerank` | 重排参数的查询、更新、测试和动态应用 |
| `agent.parse` | 不同文件格式的文本解析 |
| `agent.chunk` | 文本清洗与固定窗口切片 |
| `agent.embedding` | 本地、Mock、OpenAI-compatible Embedding |
| `agent.vector` | 本地/Mock 与 Milvus 向量存储 |
| `agent.retrieval` / `agent.rerank` | 知识召回和结果重排 |
| `agent.llm` / `agent.prompt` | LLM 客户端及问答、出题 Prompt |
| `agent.ingest` | 文档解析、切片、Embedding 和索引写入总流程 |
| `agent.chat` / `agent.history` | RAG 问答及会话持久化 |
| `agent.generation` | AI 题目生成 |
| `agent.settings` | LLM 与 Embedding 运行时设置 |

协作约束：`agent` 包属于同学 C 的负责范围，其他模块改动不应顺带修改该包。

## 6. 数据结构

| 表 | 用途 | 关键关联 |
|---|---|---|
| `courses` | 课程基本信息 | 业务数据的顶层归属 |
| `documents` | 上传文档及解析状态 | `course_id` |
| `knowledge_chunks` | 文档切片正文与向量状态 | `course_id`、`document_id` |
| `question_batches` | 一次 AI 出题任务/套卷 | `course_id` |
| `question_batch_documents` | 出题批次与选中文档的多对多关系 | `batch_id`、`document_id` |
| `question_batch_chunks` | 出题批次与引用切片的多对多关系 | `batch_id`、`chunk_id` |
| `questions` | 题目内容、答案、解析和标签 | `course_id`、`source_chunk_id`、`batch_id` |
| `practice_records` | 用户答案、对错和判题反馈 | `course_id`、`question_id` |
| `chat_sessions` | 课程问答会话 | `course_id` |
| `chat_messages` | 会话内用户/助手消息 | `session_id` |
| `agent_model_settings` | LLM 与 Embedding 运行时配置 | 固定配置记录 |
| `rerank_runtime_settings` | Rerank 运行时配置 | 固定配置记录 |

数据库脚本主要使用索引而非数据库外键维护关系，引用完整性主要由 Service 层校验和清理逻辑保证。

## 7. REST API 总览

除文件下载和 SSE 流外，业务接口通常使用统一响应体：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

| 方法与路径 | 功能 |
|---|---|
| `POST /api/courses` | 创建课程 |
| `GET /api/courses` | 查询课程，可按名称筛选 |
| `PUT /api/courses/{id}` | 修改课程 |
| `DELETE /api/courses/{id}` | 删除课程 |
| `POST /api/documents/upload` | 上传文档 |
| `GET /api/documents?courseId=...` | 查询课程文档 |
| `POST /api/documents/{id}/ingest` | 异步解析文档 |
| `GET /api/documents/{id}/file` | 下载或预览原文件 |
| `DELETE /api/documents/{id}` | 删除文档及关联数据 |
| `POST /api/agent/chat` | RAG 问答 |
| `POST /api/agent/chat/stream` | SSE 流式 RAG 问答 |
| `GET /api/agent/chat/sessions` | 查询课程会话 |
| `GET /api/agent/chat/sessions/{sessionId}/messages` | 查询会话消息 |
| `DELETE /api/agent/chat/sessions/{sessionId}` | 删除会话 |
| `POST /api/questions` | 保存题目 |
| `GET /api/questions` | 按课程/题型/难度查询题目 |
| `DELETE /api/questions/{id}` | 删除题目 |
| `POST /api/agent/questions/generate` | 直接生成题目 |
| `POST /api/question-batches/generate` | 创建批量出题任务并生成题目 |
| `GET /api/question-batches?courseId=...` | 查询出题批次 |
| `DELETE /api/question-batches/{id}` | 删除出题批次 |
| `POST /api/practice/submit` | 提交答案 |
| `GET /api/courses/{courseId}/practice/records` | 查询练习记录 |
| `GET /api/courses/{courseId}/practice/wrong-questions` | 查询错题记录 |
| `GET/PUT /api/agent/model-settings` | 查询或更新模型设置 |
| `POST /api/agent/model-settings/test` | 测试模型设置 |
| `GET/PUT /api/rerank-settings` | 查询或更新重排设置 |
| `POST /api/rerank-settings/test` | 测试重排设置 |

## 8. 技术栈

### 后端

- Java 21
- Spring Boot 4.0.7（Web MVC、Validation、Actuator）
- MyBatis Spring Boot Starter 4.0.1
- MySQL 8 / H2
- Milvus Java SDK 2.4.11
- Apache PDFBox 3.0.3
- Apache POI 5.3.0
- Reactor Core（SSE/流式处理支撑）
- Maven、Lombok

### 前端

- Vue 3.5
- TypeScript 5.6
- Vite 5.4
- Marked + DOMPurify（Markdown 渲染与安全清洗）
- Lucide Vue Next（图标）

### 部署与外部服务

- Docker Compose
- MySQL
- Milvus + etcd + MinIO
- DeepSeek 或其他 OpenAI-compatible LLM
- OpenAI-compatible Embedding 服务
- 可选 SiliconFlow Rerank 服务
- 可选 Tesseract OCR

## 9. 配置与运行模式

### 9.1 Docker 完整模式

```bash
docker compose up -d --build
```

默认地址：

| 服务 | 地址 |
|---|---|
| 前端 | `http://localhost:5173` |
| 后端 | `http://localhost:8080` |
| MySQL（宿主机） | `localhost:3307` |
| Milvus | `localhost:19530` |
| MinIO 控制台 | `http://localhost:9001` |

### 9.2 本地开发模式

前提：JDK 21、Maven、Node.js 20+，并准备 MySQL 与 Milvus。

```bash
# 基础设施
docker compose up -d mysql etcd minio milvus

# 后端（仓库根目录）
mvn spring-boot:run -pl backend

# 前端
cd frontend
npm install
npm run dev
```

### 9.3 Windows 一键便携模式

执行 `start.bat`。发布脚本会准备便携 JDK/Node 及构建产物；该模式使用 H2 文件数据库、本地检索和后端静态托管前端，不要求安装 Docker、MySQL 或 Milvus。相关说明见 `QUICKSTART.md` 和 `scripts/package-release.ps1`。

## 10. 常用命令

```bash
# 编译后端
mvn compile -pl backend

# 后端测试
mvn test -pl backend

# 后端打包
mvn package -pl backend

# 前端测试
npm --prefix frontend test

# 前端构建
npm --prefix frontend run build
```

## 11. 关键配置项

| 环境变量 | 默认值/说明 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL `rag_study_assistant` 数据库连接 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 默认 `root` / `root` |
| `APP_UPLOAD_DIR` | 默认 `./uploads` |
| `VECTOR_PROVIDER` | 默认 `milvus` |
| `MILVUS_HOST` / `MILVUS_PORT` | 默认 `localhost:19530` |
| `MILVUS_COLLECTION` | 默认 `knowledge_chunk_vectors_bge_m3` |
| `EMBEDDING_DIMENSION` | 默认 `1024`，必须与模型输出一致 |
| `RAG_TOP_K` / `RAG_CANDIDATE_K` | 默认 `5` / `20` |
| `RAG_SIMILARITY_THRESHOLD` | 默认 `0.2` |
| `RAG_HISTORY_LIMIT` | 默认 `8` |
| `LLM_PROVIDER` / `LLM_BASE_URL` / `LLM_MODEL` | LLM 服务设置 |
| `LLM_API_KEY` | LLM Key，不应提交到仓库 |
| `EMBEDDING_PROVIDER` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` | Embedding 设置 |
| `EMBEDDING_API_KEY` | Embedding Key，不应提交到仓库 |
| `RERANK_PROVIDER` | 默认 `local` |
| `RERANK_API_KEY` | 外部 Rerank Key，不应提交到仓库 |
| `AGENT_MOCK` | `true` 时使用纯内存 Mock，默认 `false` |
| `OCR_TESSERACT_*` | OCR 命令、语言、DPI 和超时设置 |

## 12. 开发注意事项

- 不要创建或修改 `com.rag.backend.agent` 包下内容，除非由负责该模块的同学明确安排。
- MySQL 与 H2 各有一份 schema，修改表结构时应同步维护 `schema.sql`、`schema-h2.sql`，必要时同步 `sql/init.sql` 和兼容迁移代码。
- Embedding 模型变化时应同步核对 `EMBEDDING_DIMENSION` 和 Milvus Collection；维度不匹配会导致向量写入或检索失败。
- 外部模型 Key 通过环境变量或设置页面配置，不应写入 Git 跟踪文件。
- 文档解析为异步流程，前端应依据文档状态轮询，而不是把提交成功视为解析完成。
- 删除课程、文档、批次等资源时需要关注关联记录、磁盘文件和向量数据是否同步清理。
- 当前仓库的 README、QUICKSTART 和部分源码注释存在字符编码显示异常；维护文档时统一使用 UTF-8。


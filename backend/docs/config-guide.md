# 配置说明

项目所有运行配置集中在 `backend/src/main/resources/application.yml` 中，通过**环境变量**覆盖默认值。下方按模块逐一说明每个配置项的含义、默认值和适用场景。

---

## 1. 数据源（spring.datasource）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| driver-class-name | — | `com.mysql.cj.jdbc.Driver` | JDBC 驱动，固定不变 |
| url | `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/rag_study_assistant?...` | 数据库连接串，`createDatabaseIfNotExist=true` 会自动建库 |
| username | `MYSQL_USER` | `root` | 数据库用户名 |
| password | `MYSQL_PASSWORD` | `root` | 数据库密码 |

**前置条件**：本地 MySQL 已运行，且 `MYSQL_USER` / `MYSQL_PASSWORD` 所指定的账号拥有建库、建表权限。

### 数据库自动初始化（spring.sql.init）

| 配置项 | 值 | 说明 |
|--------|-----|------|
| mode | `always` | 每次启动都执行 schema.sql |
| schema-locations | `classpath:db/schema.sql` | 建表脚本路径 |
| continue-on-error | `true` | 表已存在时不中断 |

所有建表语句均使用 `CREATE TABLE IF NOT EXISTS`，可安全重复执行。

---

## 2. 文件上传（app.upload / spring.servlet.multipart）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| app.upload.dir | `APP_UPLOAD_DIR` | `./uploads` | 上传文件存储根目录，按 `documents/{courseId}/` 子目录组织 |
| spring.servlet.multipart.max-file-size | — | `100MB` | 单文件大小上限 |
| spring.servlet.multipart.max-request-size | — | `100MB` | 单次请求体大小上限 |

`app.upload.dir` 支持相对路径（相对于应用启动目录）和绝对路径。

---

## 3. 向量存储（milvus / vector）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| milvus.host | `MILVUS_HOST` | `localhost` | Milvus 服务地址 |
| milvus.port | `MILVUS_PORT` | `19530` | Milvus gRPC 端口 |
| milvus.collection-name | `MILVUS_COLLECTION` | `knowledge_chunk_vectors_bge_m3` | 向量集合名称 |
| milvus.embedding-dimension | `EMBEDDING_DIMENSION` | `1024` | 向量维度（BGE-M3 为 1024） |
| vector.provider | `VECTOR_PROVIDER` | `milvus` | 向量存储后端，可选 `milvus` |

**前置条件**：Milvus 服务已启动且已创建对应 collection，向量维度与 embedding 模型输出一致。

---

## 4. RAG 检索参数（rag）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| rag.top-k | `RAG_TOP_K` | `5` | 最终返回给 LLM 的片段数量 |
| rag.candidate-k | `RAG_CANDIDATE_K` | `20` | 粗排召回的候选片段数量 |
| rag.similarity-threshold | `RAG_SIMILARITY_THRESHOLD` | `0.2` | 向量相似度最低阈值，低于此值的结果被丢弃 |
| rag.history-limit | `RAG_HISTORY_LIMIT` | `8` | 多轮对话携带的历史消息条数 |

**调优建议**：
- 回答太泛 → 增大 `top-k`（带来更多上下文）
- 回答偏离主题 → 提高 `similarity-threshold`（过滤低相关片段）
- 多轮对话丢失上文 → 增大 `history-limit`

---

## 5. 重排序（rerank）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| rerank.provider | `RERANK_PROVIDER` | `local` | `local`（本地加权混合）或 `remote`（调用远端 API） |
| rerank.base-url | `RERANK_BASE_URL` | `https://api.siliconflow.cn/v1` | 远端 rerank API 地址（仅 remote 模式使用） |
| rerank.api-key | `RERANK_API_KEY` | `${SILICONFLOW_API_KEY}` | 远端 API Key |
| rerank.model | `RERANK_MODEL` | `BAAI/bge-reranker-v2-m3` | 远端 rerank 模型 |
| rerank.timeout-seconds | `RERANK_TIMEOUT_SECONDS` | `60` | 远端调用超时 |
| rerank.fail-open | `RERANK_FAIL_OPEN` | `true` | 远端调用失败时是否回退到粗排结果 |
| rerank.max-chunks-per-doc | `RERANK_MAX_CHUNKS_PER_DOC` | `1024` | 单文档最大参与重排的片段数 |
| rerank.overlap-tokens | `RERANK_OVERLAP_TOKENS` | `50` | 分块时相邻片段重叠 token 数 |
| rerank.local.vector-weight | `RERANK_LOCAL_VECTOR_WEIGHT` | `0.7` | 本地模式向量相似度权重 |
| rerank.local.lexical-weight | `RERANK_LOCAL_LEXICAL_WEIGHT` | `0.3` | 本地模式词法匹配权重 |

**模式说明**：
- `local`：无需外部 API，用向量相似度 + 词法匹配加权打分（两个 weight 之和应为 1.0）
- `remote`：调用 SiliconFlow 等平台的 BGE-Reranker 模型做精准重排，需配置 `base-url` + `api-key`

---

## 6. Agent 调试开关（agent）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| agent.mock | `AGENT_MOCK` | `false` | `true` 时启用纯内存 Mock，无需 Milvus/Embedding/LLM 即可跑通后端 |

`mock: true` 适合纯后端调试或前端联调阶段，不依赖任何外部服务。

---

## 7. 大模型（llm）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| llm.provider | `LLM_PROVIDER` | `deepseek` | LLM 提供商标识 |
| llm.base-url | `LLM_BASE_URL` | `https://api.deepseek.com` | API 地址（OpenAI 兼容格式） |
| llm.api-key | `LLM_API_KEY` | `${DEEPSEEK_API_KEY}` | API Key |
| llm.model | `LLM_MODEL` | `deepseek-v4-pro` | 模型名称 |
| llm.system-prompt | `LLM_SYSTEM_PROMPT` | （见下方默认值） | 系统提示词 |
| llm.temperature | `LLM_TEMPERATURE` | `0.6` | 生成温度（0-1），越高越随机 |
| llm.timeout-seconds | `LLM_TIMEOUT_SECONDS` | `120` | 请求超时 |
| llm.thinking | `LLM_THINKING` | `false` | 是否开启思考链（仅部分模型支持） |
| llm.reasoning-effort | `LLM_REASONING_EFFORT` | （空） | 推理强度等级，留空使用模型默认值 |

默认 system-prompt：
> You are a knowledgeable study assistant. Provide detailed, thorough explanations. Break down complex concepts, use examples, and structure your answers clearly. Always ground your answers in the provided course material.

**替换 LLM 示例**（设为环境变量）：
```bash
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-xxxx
export LLM_MODEL=gpt-4o
export LLM_PROVIDER=openai
```

只要 `base-url` 兼容 OpenAI chat completions 格式、`api-key` 有效、`model` 名称正确即可切换。

---

## 8. 向量嵌入（embedding）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| embedding.provider | `EMBEDDING_PROVIDER` | `openai-compatible` | Embedding 提供商 |
| embedding.base-url | `EMBEDDING_BASE_URL` | `https://api.siliconflow.com/v1` | API 地址 |
| embedding.api-key | `EMBEDDING_API_KEY` | `${SILICONFLOW_API_KEY}` | API Key |
| embedding.model | `EMBEDDING_MODEL` | `BAAI/bge-m3` | Embedding 模型（输出维度 1024） |
| embedding.timeout-seconds | `EMBEDDING_TIMEOUT_SECONDS` | `120` | 请求超时 |

**注意**：更换 embedding 模型时需同步修改 `milvus.embedding-dimension`，二者必须一致。

---

## 9. OCR（ocr.tesseract）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| ocr.tesseract.command | `OCR_TESSERACT_COMMAND` | `tesseract` | Tesseract 可执行文件路径或命令名 |
| ocr.tesseract.language | `OCR_TESSERACT_LANGUAGE` | `chi_sim+eng` | 识别语言（中英混合） |
| ocr.tesseract.dpi | `OCR_TESSERACT_DPI` | `220` | 图片 DPI，影响识别精度 |
| ocr.tesseract.timeout-seconds | `OCR_TESSERACT_TIMEOUT_SECONDS` | `60` | 识别超时 |

**前置条件**：系统已安装 Tesseract OCR 且对应语言包已下载。
```bash
# Windows：下载安装 https://github.com/UB-Mannheim/tesseract/wiki
# Linux：
sudo apt install tesseract-ocr tesseract-ocr-chi-sim
# macOS：
brew install tesseract tesseract-lang
```

---

## 10. API 通用密钥（api-key）

`application.properties` 中定义：

```properties
spring.application.name=backend
```

目前无额外的通用 API Key 配置项。

---

## 11. CORS 跨域

`CorsConfig.java` 中硬编码，无需额外配置：

- 允许路径：`/api/**`
- 允许来源：`*`（所有来源）
- 允许方法：`GET, POST, PUT, DELETE, OPTIONS`
- 允许携带凭证（Cookie / Authorization header）

---

## 12. MyBatis

`MyBatisConfig.java` 手动装配（绕过了 `mybatis-spring-boot-starter` 的自动配置），关键行为：

- 实体类别名包：`com.rag.backend`
- Mapper XML 路径：`classpath:com/rag/backend/**/*.xml`
- 自动驼峰转换：`mapUnderscoreToCamelCase = true`（数据库 `course_id` → Java `courseId`）
- SQL 日志：通过 `StdOutImpl` 输出到控制台

---

## 快速启动检查清单

按顺序确认以下服务可用：

| # | 组件 | 检查方式 | 依赖配置 |
|---|------|----------|----------|
| 1 | MySQL | `mysql -u root -p` 能登录 | `SPRING_DATASOURCE_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` |
| 2 | Milvus | `curl http://localhost:19530/health` 返回 OK | `MILVUS_HOST`, `MILVUS_PORT` |
| 3 | SiliconFlow API（或替代平台） | `curl -H "Authorization: Bearer $SILICONFLOW_API_KEY" https://api.siliconflow.cn/v1/models` | `SILICONFLOW_API_KEY` |
| 4 | DeepSeek API（或替代 LLM） | `curl -H "Authorization: Bearer $DEEPSEEK_API_KEY" https://api.deepseek.com/models` | `DEEPSEEK_API_KEY` / `LLM_API_KEY` |
| 5 | Tesseract OCR（可选） | `tesseract --version` | — |

全部就绪后：
```bash
mvn spring-boot:run -pl backend
```

如果只需调通后端（不跑 RAG 链路），设置 `AGENT_MOCK=true` 跳过 2-5。

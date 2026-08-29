# 基础后端 API 说明

**负责：姜绪梁（同学B）** | 基础路径：`http://localhost:8080`

> 本文档面向**前端同学（D）**和 **Agent 同学（C）**，说明基础后端提供的全部接口、数据表和项目结构。

---

## 〇、项目结构速览

```
Rag-study-assistant/                          ← 仓库根目录
├── pom.xml                                   ← 父 POM（版本管理）
├── sql/init.sql                              ← 手动初始化脚本
└── backend/                                  ← 后端模块（本子项目）
    ├── pom.xml
    └── src/main/
        ├── java/com/rag/backend/
        │   ├── BackendApplication.java       ← 启动入口
        │   ├── common/                       ← 统一返回体、业务异常、全局异常处理
        │   │   ├── Result.java
        │   │   ├── BizException.java
        │   │   └── GlobalExceptionHandler.java
        │   ├── config/                       ← 跨域、静态资源、上传、MyBatis 配置
        │   │   ├── CorsConfig.java
        │   │   ├── WebMvcConfig.java
        │   │   ├── FileUploadConfig.java
        │   │   └── MyBatisConfig.java
        │   ├── course/                       ← 课程模块
        │   │   ├── CourseController.java
        │   │   ├── CourseService.java
        │   │   ├── CourseServiceImpl.java
        │   │   ├── CourseMapper.java
        │   │   └── model/ (Course, CourseCreateRequest, CourseResponse)
        │   ├── document/                     ← 文档管理模块
        │   │   ├── DocumentController.java
        │   │   ├── DocumentService.java
        │   │   ├── DocumentServiceImpl.java
        │   │   ├── DocumentMapper.java
        │   │   └── model/ (CourseDocument, DocumentUploadRequest, DocumentResponse)
        │   ├── question/                     ← 题库模块
        │   │   ├── QuestionController.java
        │   │   ├── QuestionService.java
        │   │   ├── QuestionServiceImpl.java
        │   │   ├── QuestionMapper.java
        │   │   └── model/ (Question, QuestionResponse, QuestionQueryRequest)
        │   ├── practice/                     ← 练习记录模块
        │   │   ├── PracticeController.java
        │   │   ├── PracticeService.java
        │   │   ├── PracticeServiceImpl.java
        │   │   ├── PracticeMapper.java
        │   │   └── model/ (PracticeRecord, SubmitAnswerRequest, PracticeResultResponse)
        │   └── agent/                        ← Agent 模块（同学C 的负责范围，请勿修改）
        └── resources/
            ├── application.yml               ← 后端统一配置文件
            └── db/schema.sql                 ← 建表 SQL（启动时自动执行）
```

**技术栈：** Java 21 / Spring Boot 4.0.7 / MyBatis-Spring 3.0.4 / MySQL / Lombok / Maven

**分层说明：** 每个模块内部统一采用 Controller → Service(接口) → ServiceImpl → Mapper → model 的分层结构。Mapper 使用 MyBatis 注解 SQL（`@Select` / `@Insert` / `@Update` / `@Delete`），不再依赖 MyBatis-Plus。

---

## 一、数据库表

### 1.1 courses（课程表）

> 由同学A 负责维护，基础后端仅通过 `CourseMapper.selectById()` 做存在性校验。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键，自增 |
| name | VARCHAR(100) | 课程名称 |
| description | VARCHAR(500) | 课程描述 |
| term | VARCHAR(50) | 学期 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 1.2 documents（文档表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键，自增 |
| course_id | BIGINT | 关联课程ID |
| filename | VARCHAR(255) | 原始文件名 |
| file_type | VARCHAR(50) | pdf / pptx / docx / txt |
| file_path | VARCHAR(500) | 本地存储路径 |
| parse_status | VARCHAR(20) | UPLOADED → PARSING → PARSED / FAILED |
| chunk_count | INT | 知识切片数量，默认 0 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 1.3 knowledge_chunks（知识片段表）

> Agent 模块使用此表存储切片正文，向量存 Milvus。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键，自增 |
| course_id | BIGINT | 课程ID |
| document_id | BIGINT | 来源文档ID |
| chunk_index | INT | 片段序号 |
| title | VARCHAR(255) | 章节标题 |
| content | TEXT | 片段正文（存 MySQL） |
| source_page | INT | 来源页码 |
| token_count | INT | 估算 token 数 |
| milvus_vector_id | VARCHAR(100) | Milvus 向量ID |
| embedding_status | VARCHAR(50) | PENDING / DONE / FAILED |
| created_at | DATETIME | 创建时间 |

### 1.4 questions（题库表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键，自增 |
| course_id | BIGINT | 关联课程ID |
| source_chunk_id | BIGINT | 来源知识片段ID（可空） |
| type | VARCHAR(20) | single_choice / multi_choice / true_false / short_answer |
| stem | TEXT | 题干 |
| options | JSON | 选项（JSON 数组字符串） |
| answer | VARCHAR(500) | 答案 |
| explanation | TEXT | 解析 |
| difficulty | VARCHAR(10) | easy / medium / hard |
| knowledge_point | VARCHAR(255) | 知识点名称 |
| created_at | DATETIME | 创建时间 |

### 1.5 practice_records（练习记录表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键，自增 |
| course_id | BIGINT | 关联课程ID |
| question_id | BIGINT | 关联题目ID |
| user_answer | VARCHAR(500) | 用户提交的答案 |
| is_correct | BOOLEAN | 是否正确 |
| created_at | DATETIME | 创建时间 |

---

## 二、课程接口

### 1. 新增课程

```
POST /api/courses
Content-Type: application/json
```

**请求体：**
```json
{
  "name": "计算机网络",
  "description": "计算机网络课程",
  "term": "2026春季"
}
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": { "id": 1, "name": "计算机网络", "description": "...", "term": "2026春季", "createdAt": "...", "updatedAt": "..." }
}
```

### 2. 课程列表

```
GET /api/courses
GET /api/courses?name=网络        ← 按名称模糊搜索（可选）
```

### 3. 修改课程

```
PUT /api/courses/{id}
Content-Type: application/json
```

请求体同新增。`404` 课程不存在。

### 4. 删除课程

```
DELETE /api/courses/{id}
```

---

## 三、文档接口

### 1. 文件上传

```
POST /api/documents/upload
Content-Type: multipart/form-data
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | File | 是 | 上传的文件（最大 100MB） |
| courseId | Long | 是 | 课程ID |

上传后文件存储路径：`./uploads/documents/{courseId}/{时间戳}_{原始文件名}`

文档初始状态为 `UPLOADED`，等待 Agent 模块解析后更新。

**错误：** `400` 课程不存在 / 文件为空 / 超过大小限制

### 2. 文档列表

```
GET /api/documents?courseId=1
```

按 `created_at` 倒序排列。

### 3. 文档删除

```
DELETE /api/documents/{id}
```

同时删除数据库记录和本地文件。`400` 文档不存在。

### 4. 文档状态流转

```
POST /api/documents/{id}/ingest
```

该接口异步提交文档解析任务，返回 HTTP `202 Accepted` 和状态为 `PARSING` 的文档。
客户端通过 `GET /api/documents?courseId={courseId}` 轮询最终状态。同一文档正在解析时重复提交返回 `409 Conflict`。

```
UPLOADED ──→ PARSING ──→ PARSED
                │
                └──→ FAILED
```

Agent 模块解析文档后，通过 `DocumentService.updateParseStatus(id, status, chunkCount)` 更新状态。

---

## 四、题目接口

### 1. 保存单个题目

```
POST /api/questions
Content-Type: application/json
```

**请求体示例：**
```json
{
  "courseId": 1,
  "type": "single_choice",
  "stem": "OSI 模型中，网络层位于第几层？",
  "options": "[\"A.第二层\",\"B.第三层\",\"C.第四层\",\"D.第五层\"]",
  "answer": "B",
  "explanation": "网络层是 OSI 模型的第三层，负责路由选择和数据转发。",
  "difficulty": "medium",
  "knowledgePoint": "OSI 参考模型",
  "sourceChunkId": 12,
  "sourceDocumentId": 3,
  "chapterTags": "[\"第三章\",\"第五章\"]"
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | Long | 是 | 课程ID |
| type | String | 是 | 通用题型及 fill_blank / composition / classical_chinese_reading / poetry_appreciation / modern_reading / translation / sentence_break / explanation / language_basic |
| stem | String | 是 | 题干 |
| options | String | 否 | JSON 数组字符串，如 `["A.X","B.Y"]` |
| answer | String | 是 | 见下方格式约定 |
| explanation | String | 否 | 解析 |
| difficulty | String | 否 | easy / medium / hard |
| knowledgePoint | String | 否 | 知识点 |
| sourceChunkId | Long | 否 | 来源知识片段ID |
| sourceDocumentId | Long | 否 | 查询响应字段，由来源知识片段关联得到的文件ID；保存请求无需填写 |
| chapterTags | String | 否 | JSON 章节数组；一道题可属于多个章节 |
| subject | String | 否 | general（默认）/ chinese |
| questionData | String | 复杂题必填 | JSON 对象字符串；保存材料、作文要求及 subQuestions |
| answerSchema | String | 否 | JSON 对象字符串；保存评分点和评分量表 |
| gradingStrategy | String | 否 | rule / manual / ai / mixed；未传时按题型确定 |

**题型对应的 answer 格式：**
- `single_choice`：`"A"` / `"B"` / `"C"` / `"D"`
- `multi_choice`：`"AB"` / `"ACD"`
- `true_false`：`"正确"` / `"错误"`
- `short_answer`：自由文本

**错误：** `400` course_id 为空 / 课程不存在 / 题干为空 / 题型为空

### 2. 题目列表

```
GET /api/questions?courseId=1
GET /api/questions?courseId=1&type=single_choice&difficulty=medium
GET /api/questions?courseId=1&subject=chinese&type=composition
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | Long | 是 | 课程ID |
| type | String | 否 | 题型筛选 |
| difficulty | String | 否 | 难度筛选 |
| subject | String | 否 | 学科筛选；不传时保持原有行为 |

---

## 五、练习接口

### 1. 提交答案

```
POST /api/practice/submit
Content-Type: application/json
```

**请求体：**
```json
{
  "courseId": 1,
  "questionId": 5,
  "userAnswer": "B"
}
```

后端自动判断对错（忽略大小写、首尾空格），保存记录并返回结果：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "courseId": 1,
    "questionId": 5,
    "userAnswer": "B",
    "isCorrect": true,
    "createdAt": "2026-06-17T20:00:00"
  }
}
```

**错误：** `400` 课程不存在 / 题目不存在

复合题使用 `answerPayload` 提交，每个小题通过稳定的 `subQuestionKey` 对应：

```json
{
  "courseId": 1,
  "questionId": 8,
  "answerPayload": {
    "answers": [
      {"subQuestionKey": "q1", "answer": "A"},
      {"subQuestionKey": "q2", "answer": "参考译文"}
    ]
  }
}
```

客观小题立即判定，主观小题返回 `isCorrect: null` 和 `gradingStatus: "manual_required"`；结果中的 `subResults` 分别给出各小题状态、参考答案与解析。

### 2. 练习记录列表

```
GET /api/courses/{courseId}/practice/records
```

返回该课程下所有提交记录，按时间倒序。

### 3. 错题列表

```
GET /api/courses/{courseId}/practice/wrong-questions
```

返回 `is_correct = false` 的记录，按时间倒序。前端可据此展示错题本。

---

## 六、给同学C（Agent 模块）的编程接口

> Agent 模块代码位于 `com.rag.backend.agent` 包下，由同学C 负责。
> 基础后端提供以下 Service 方法供 Agent 直接调用（无需走 HTTP）。

### 6.1 更新文档解析状态

```java
// 注入 DocumentService
@Autowired
private DocumentService documentService;

// 解析中
documentService.updateParseStatus(documentId, "PARSING", null);

// 解析完成
documentService.updateParseStatus(documentId, "PARSED", chunkCount);

// 解析失败
documentService.updateParseStatus(documentId, "FAILED", null);
```

状态常量定义在 `CourseDocument` 类中：
- `CourseDocument.STATUS_UPLOADED`
- `CourseDocument.STATUS_PARSING`
- `CourseDocument.STATUS_PARSED`
- `CourseDocument.STATUS_FAILED`

### 6.2 批量保存 AI 生成题目

```java
@Autowired
private QuestionService questionService;

List<Question> questions = new ArrayList<>();
Question q = new Question();
q.setCourseId(courseId);
q.setType(Question.TYPE_SINGLE_CHOICE);
q.setStem("题干的文本...");
q.setOptions("[\"A.选项1\",\"B.选项2\",\"C.选项3\",\"D.选项4\"]");
q.setAnswer("A");
q.setExplanation("解析文本...");
q.setDifficulty(Question.DIFF_MEDIUM);
q.setKnowledgePoint("知识点名称");
q.setSourceChunkId(chunkId);  // 可空
questions.add(q);

questionService.batchSave(questions);
```

题型和难度常量定义在 `Question` 类中：
- 题型：`TYPE_SINGLE_CHOICE` / `TYPE_MULTI_CHOICE` / `TYPE_TRUE_FALSE` / `TYPE_SHORT_ANSWER`
- 难度：`DIFF_EASY` / `DIFF_MEDIUM` / `DIFF_HARD`

### 6.3 knowledge_chunks 表

建表语句已包含在 `db/schema.sql` 中（随应用启动自动执行），Agent 模块可直接使用。表结构见 [1.3 节](#13-knowledge_chunks知识片段表)。

### 6.4 课程存在性校验

```java
@Autowired
private CourseMapper courseMapper;

// 返回 null 表示课程不存在
Course course = courseMapper.selectById(courseId);
```

---

## 七、RAG 问答接口

> 本节仅描述当前 `RagChatController`、`RagChatResponse` 与前端类型已经实现的响应契约；不代表生产验收、模型质量或外部服务可用性。

### 7.1 同步问答

```
POST /api/agent/chat
Content-Type: application/json
```

**请求体：**

```json
{
  "courseId": 1,
  "sessionId": 12,
  "question": "OSI 模型中网络层的职责是什么？"
}
```

`courseId` 和非空 `question` 为必填；`sessionId` 可省略或为 `null`。

成功时仍使用统一 `Result` 信封，`data` 为：

```json
{
  "sessionId": 12,
  "answer": "...",
  "references": [],
  "metadata": {
    "evidenceDecision": { "decision": "ANSWER", "reasonCode": "..." },
    "retrieval": { "degraded": false, "emptyReason": "NONE" },
    "grounding": { "status": "ACCEPTED", "generationAttempts": 1 }
  }
}
```

`references` 与 `metadata` 在前端类型中均为可选字段；兼容旧构造响应时可能缺失。`metadata` 存在时包含以下脱敏诊断：

| 路径 | 字段 |
|------|------|
| `evidenceDecision` | `decision`（`ANSWER` / `CLARIFY` / `REFUSE`）、`reasonCode`、`usableEvidenceIds`、可选 `missingInformation`、`observedSignals`、`policyVersion` |
| `evidenceDecision.observedSignals` | 检索计数、词法覆盖与歧义/冲突信号；阈值相关的 `thresholdScoreKind`、`thresholdCalibrationId`、`appliedThreshold` 可为空或缺失 |
| `retrieval` | `degraded`、`emptyReason`、各来源的 `source` / `succeeded` / 可选 `failureType` / `candidateCount` / `latencyNanos` |
| `retrieval.rerank` | 请求与实际 reranker、降级与失败原因、可选 `appliedThreshold` 与 `compositeVersion`、候选数和 `latencyNanos` |
| `retrieval.diversity` | 后端对象还可序列化多样性选择诊断：`enabled`、`strategy`、可选 `lambda`、输入/输出候选数、冗余度、唯一文档数和 `latencyNanos`；当前前端 `RagChatMetadata` 类型未声明该子对象，客户端不应依赖它 |
| `grounding` | `status`（`NOT_APPLICABLE` / `DISABLED` / `ACCEPTED` / `REPAIRED` / `REJECTED`）、`generationAttempts`、引用与 claim 计数、`sourceIds`；`citationValid`、`citationCoverage`、`failureReason`、`validatorVersion`、`semanticJudgeCalibrationId` 可为 `null` 或缺失 |

### 7.2 流式问答（SSE）

```
POST /api/agent/chat/stream
Accept: text/event-stream
Content-Type: application/json
```

请求体与同步问答相同。服务端依次发送 `session`、`references`、`metadata` 事件，再发送零个或多个 `delta`，成功结束发送 `done`。`metadata` 的 `data` 是与同步响应 `metadata` 相同的 JSON 对象，不包裹在 `Result` 信封中；其字段与可选性遵循 7.1。

| SSE event | `data` |
|------|------|
| `session` | `{ "sessionId": 12 }` |
| `references` | 检索片段数组 |
| `metadata` | `RagChatMetadata` JSON 对象 |
| `delta` | 单个文本片段 |
| `done` | `"[DONE]"` |
| `error` | `{ "code": "CHAT_STREAM_FAILED", "message": "聊天处理失败，请稍后重试" }` |

客户端应将 `metadata` 视为诊断信息，而不是业务成功、检索质量或生产可用性的证明。

---

## 八、通用响应格式

所有接口统一返回：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数错误 / 业务异常 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

业务异常通过 `BizException` 抛出，可在任意层使用：

```java
throw new BizException(400, "自定义错误信息");
```

`GlobalExceptionHandler` 统一拦截并转换为上述 JSON 格式，前端无需处理异常页面。

---

## 九、配置文件说明

`application.yml` 由后端负责人统一维护，关键配置项：

| 配置项 | 说明 |
|------|------|
| `spring.datasource.url` | MySQL 连接（含 `createDatabaseIfNotExist=true` 自动建库） |
| `spring.sql.init.mode=always` | 每次启动自动执行 `db/schema.sql` 建表 |
| `app.upload.dir` | 文件上传目录，默认 `./uploads` |
| `milvus.*` | Milvus 向量库连接配置（Agent 模块使用） |
| `rag.top-k` | RAG 检索 topK 数量 |
| `agent.mock` | `true` = Mock 模式（前端联调用），`false` = 真实 AI 服务 |

---

## 十、启动方式

```bash
# 从仓库根目录执行
mvn spring-boot:run -pl backend
```

前提：本地 MySQL 已启动，数据库和表会自动创建。
# 结构化语文试卷

本项目将“普通出题批次”和“可预览、排序、打印的试卷”分开建模。试卷生成按大题分段执行，某一分区失败时会保留其他成功分区，并在 `warnings` 返回原因。

- `POST /api/papers/generate`：生成并保存完整语文套卷。核心字段：`courseId`、`documentIds`、`title`、`difficulty`、`durationMinutes`、`totalScore`、`templateCode`、`requirements`。
- `GET /api/papers?courseId=1&subject=chinese`：试卷列表。
- `GET /api/papers/{id}`：按大题返回试卷、分值和完整题目。
- `PUT /api/papers/{id}`、`DELETE /api/papers/{id}`：更新元数据、删除试卷结构。删除试卷不删除题库原题。
- `POST /api/papers/{id}/questions`：加入题库已有题目。
- `PUT /api/papers/{id}/questions/reorder`：调整大题、顺序和分值。
- `DELETE /api/papers/{id}/questions/{questionId}`：仅从试卷移除题目。
- `PUT /api/questions/{id}`：编辑题目；复合题需继续提供合法的 `material` 和非空 `subQuestions`。
- `PUT /api/practice/records/{recordId}/grade`：人工批改，body 为 `score`、`maxScore`、`feedback`。

当前内置模板为 `chinese_high_school_standard_v1`（默认）和 `chinese_middle_school_standard_v1`。两者当前共用稳定的六大题骨架，后续可按年级拆分具体分值与题型。

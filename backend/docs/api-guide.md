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
        ├── java/com/rag/studyassistant/
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
  "sourceChunkId": null
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | Long | 是 | 课程ID |
| type | String | 是 | single_choice / multi_choice / true_false / short_answer |
| stem | String | 是 | 题干 |
| options | String | 否 | JSON 数组字符串，如 `["A.X","B.Y"]` |
| answer | String | 是 | 见下方格式约定 |
| explanation | String | 否 | 解析 |
| difficulty | String | 否 | easy / medium / hard |
| knowledgePoint | String | 否 | 知识点 |
| sourceChunkId | Long | 否 | 来源知识片段ID |

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
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | Long | 是 | 课程ID |
| type | String | 否 | 题型筛选 |
| difficulty | String | 否 | 难度筛选 |

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

> Agent 模块代码位于 `com.rag.studyassistant.agent` 包下，由同学C 负责。
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

## 七、通用响应格式

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

## 八、配置文件说明

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

## 九、启动方式

```bash
# 从仓库根目录执行
mvn spring-boot:run -pl backend
```

前提：本地 MySQL 已启动，数据库和表会自动创建。

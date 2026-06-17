# 文档管理与题库模块 API 说明

**负责：同学B** | 基础路径：`http://localhost:8080`

---

## 一、数据库表

### documents（文档表）

| 字段         | 类型         | 说明                                   |
|-------------|-------------|----------------------------------------|
| id          | BIGINT (PK) | 主键，自增                              |
| course_id   | BIGINT      | 关联课程ID（courses 表外键）            |
| filename    | VARCHAR(255)| 原始文件名                              |
| file_type   | VARCHAR(50) | 文件类型：pdf / pptx / docx / txt      |
| file_path   | VARCHAR(500)| 本地存储绝对路径                        |
| parse_status| VARCHAR(20) | UPLOADED / PARSING / PARSED / FAILED   |
| chunk_count | INT         | 知识切片数量，默认 0                    |
| created_at  | DATETIME    | 创建时间                                |
| updated_at  | DATETIME    | 更新时间                                |

### questions（题库表）

| 字段            | 类型         | 说明                                         |
|----------------|-------------|----------------------------------------------|
| id             | BIGINT (PK) | 主键，自增                                    |
| course_id      | BIGINT      | 关联课程ID                                    |
| source_chunk_id| BIGINT      | 来源知识片段ID（可为空）                       |
| type           | VARCHAR(20) | single_choice / multi_choice / true_false / short_answer |
| stem           | TEXT        | 题干                                          |
| options        | JSON        | 选项（JSON 数组字符串）                        |
| answer         | VARCHAR(500)| 答案                                          |
| explanation    | TEXT        | 解析                                          |
| difficulty     | VARCHAR(10) | easy / medium / hard                         |
| knowledge_point| VARCHAR(255)| 知识点名称                                    |
| created_at     | DATETIME    | 创建时间                                      |

---

## 二、文档接口

### 1. 文件上传

```
POST /api/documents/upload
Content-Type: multipart/form-data
```

**请求参数：**

| 参数     | 类型   | 必填 | 说明         |
|---------|--------|------|-------------|
| file    | File   | 是   | 上传的文件    |
| courseId| Long   | 是   | 课程ID       |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "courseId": 1,
    "filename": "计算机网络笔记.pdf",
    "fileType": "pdf",
    "filePath": "./uploads/documents/1/20260616180000_计算机网络笔记.pdf",
    "parseStatus": "UPLOADED",
    "chunkCount": 0,
    "createdAt": "2026-06-16T18:00:00",
    "updatedAt": "2026-06-16T18:00:00"
  }
}
```

**错误：**
- `400` 课程不存在 / 文件为空
- `400` 文件大小超过限制（最大 100MB）
- `500` 文件保存失败

---

### 2. 文档列表

```
GET /api/documents?courseId=1
```

**请求参数：**

| 参数     | 类型 | 必填 | 说明   |
|---------|------|------|-------|
| courseId| Long | 是   | 课程ID |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "courseId": 1,
      "filename": "计算机网络笔记.pdf",
      "fileType": "pdf",
      "filePath": "./uploads/documents/1/20260616180000_计网笔记.pdf",
      "parseStatus": "PARSED",
      "chunkCount": 15,
      "createdAt": "2026-06-16T18:00:00",
      "updatedAt": "2026-06-16T18:05:00"
    }
  ]
}
```

---

### 3. 文档删除

```
DELETE /api/documents/{id}
```

**路径参数：**

| 参数 | 类型 | 说明     |
|-----|------|---------|
| id  | Long | 文档ID   |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

**错误：**
- `400` 文档不存在

---

## 三、题目接口

### 1. 保存单个题目

```
POST /api/questions
Content-Type: application/json
```

**请求体：**
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

| 字段           | 类型   | 必填 | 说明                                        |
|---------------|--------|------|--------------------------------------------|
| courseId      | Long   | 是   | 课程ID                                      |
| type          | String | 是   | single_choice / multi_choice / true_false / short_answer |
| stem          | String | 是   | 题干文本                                     |
| options       | String | 否   | JSON 数组字符串，如 `["A.X","B.Y"]`          |
| answer        | String | 是   | 答案文本                                     |
| explanation   | String | 否   | 解析                                        |
| difficulty    | String | 否   | easy / medium / hard                        |
| knowledgePoint| String | 否   | 知识点名称                                   |
| sourceChunkId | Long   | 否   | 来源知识片段ID                                |

**题型对应的 answer 格式：**
- `single_choice`: "A" / "B" / "C" / "D"
- `multi_choice`: "AB" / "ACD"
- `true_false`: "正确" / "错误"
- `short_answer`: 自由文本

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "courseId": 1,
    "type": "single_choice",
    "stem": "OSI 模型中，网络层位于第几层？",
    "options": "[\"A.第二层\",\"B.第三层\",\"C.第四层\",\"D.第五层\"]",
    "answer": "B",
    "explanation": "网络层是 OSI 模型的第三层...",
    "difficulty": "medium",
    "knowledgePoint": "OSI 参考模型",
    "createdAt": "2026-06-16T18:00:00"
  }
}
```

---

### 2. 题目列表

```
GET /api/questions?courseId=1&type=single_choice&difficulty=medium
```

**请求参数：**

| 参数       | 类型   | 必填 | 说明                           |
|-----------|--------|------|-------------------------------|
| courseId  | Long   | 是   | 课程ID                         |
| type      | String | 否   | 题型筛选                         |
| difficulty| String | 否   | 难度筛选                         |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "courseId": 1,
      "type": "single_choice",
      "stem": "OSI 模型中，网络层位于第几层？",
      "options": "[\"A.第二层\",\"B.第三层\",\"C.第四层\",\"D.第五层\"]",
      "answer": "B",
      "explanation": "...",
      "difficulty": "medium",
      "knowledgePoint": "OSI 参考模型",
      "createdAt": "2026-06-16T18:00:00"
    }
  ]
}
```

---

## 四、给同学C（Agent 模块）的接口

### 更新文档解析状态

```java
// 方式1：直接注入 DocumentService 调用
@Autowired
private DocumentService documentService;

// 解析完成时更新
documentService.updateParseStatus(documentId, Document.STATUS_PARSED, chunkCount);

// 解析失败时更新
documentService.updateParseStatus(documentId, Document.STATUS_FAILED, null);
```

### 批量保存 AI 生成题目

```java
// 注入 QuestionService
@Autowired
private QuestionService questionService;

// 构建题目列表并批量保存
List<Question> aiQuestions = new ArrayList<>();
Question q = new Question();
q.setCourseId(courseId);
q.setType(Question.TYPE_SINGLE_CHOICE);
q.setStem("题干的文本...");
q.setOptions("[\"A.选项1\",\"B.选项2\",\"C.选项3\",\"D.选项4\"]");
q.setAnswer("A");
q.setExplanation("解析文本...");
q.setDifficulty(Question.DIFF_MEDIUM);
q.setKnowledgePoint("知识点名称");
aiQuestions.add(q);

questionService.batchSave(aiQuestions);
```

---

## 五、通用响应格式

所有接口统一使用以下响应格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

| code | 含义     |
|------|---------|
| 200  | 成功     |
| 400  | 参数错误  |
| 404  | 资源不存在 |
| 500  | 服务器错误 |

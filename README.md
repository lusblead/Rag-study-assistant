# Rag-study-assistant

基于 RAG 的个人课程知识库与智能学习助手系统。

当前后端已支持：

- 课程 CRUD
- 文档上传、列表、删除
- TXT / Markdown / 普通文字版 PDF 解析
- 文本切片与知识片段入库
- Milvus 向量写入、搜索、删除
- RAG 答疑接口
- AI 出题并保存题库
- 练习提交与错题查询

## 一键启动

前提：电脑已安装 Docker Desktop。

在项目根目录执行：

```bash
docker compose up -d --build
```

启动后服务地址：

```text
后端 API：http://localhost:8080
MySQL：localhost:3306
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

## Agent 模式

默认：

```yaml
agent:
  mock: false
```

这会使用：

```text
MilvusVectorStoreService
LocalHashEmbeddingClient
LocalChatClient
```

这样不需要外部大模型 API Key，也能完整跑通 Milvus 向量链路。

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
  "question": "TCP 和 UDP 有什么区别？"
}
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

- 接入真实 Embedding API，替换 `LocalHashEmbeddingClient`
- 接入真实 LLM API，替换 `LocalChatClient`
- 增加 SSE 流式答疑
- 增加 DOCX / PPTX 解析
- 增加前端页面
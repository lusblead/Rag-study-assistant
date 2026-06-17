# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 前置说明

这是一个 RAG-based 学习助手系统（RAG study assistant）的后端模块。由多位同学协作开发——本仓库负责**同学B 的文档管理与题库基础模块**，为 RAG Agent（同学C）和前端（同学D）提供数据基础。

**项目采用 Maven 多模块结构**，工作目录在 `Rag-study-assistant/`（仓库根目录）：

- `pom.xml` — 父 POM（packaging: pom），统一管理 Spring Boot 版本
- `backend/pom.xml` — 后端模块 POM，包含所有业务代码
- `sql/init.sql` — 数据库初始化脚本（由基础后端维护）

## 常用命令

```bash
# 从仓库根目录（Rag-study-assistant/）执行：

# 编译整个项目
mvn compile

# 仅编译 backend 模块
mvn compile -pl backend

# 运行 Spring Boot 应用
mvn spring-boot:run -pl backend

# 运行测试
mvn test -pl backend

# 打包
mvn package -pl backend
```

## 技术栈

- Java 21, Spring Boot 4.0.7 (webmvc), MyBatis-Spring 3.0.4, MySQL, Lombok, Maven
- 开发工具：Spring Boot DevTools（热重载）

## 架构

按功能模块划分的分层架构，包路径 `com.rag.studyassistant`（源码在 `backend/src/main/java/`）：

```
common/       → 统一返回、业务异常、全局异常处理
config/       → 跨域、静态资源、文件上传等配置
course/       → 课程模块（controller/service/mapper/model）
document/     → 文档管理模块（controller/service/mapper/model）
question/     → 题库管理模块（controller/service/mapper/model）
practice/     → 练习记录模块（controller/service/mapper/model）
agent/        → AI Agent 核心模块（由同学C负责）
```

每个功能模块内部结构（以 course 为例）：
```
course/
├── CourseController.java
├── CourseService.java
├── CourseServiceImpl.java
├── CourseMapper.java
└── model/
    ├── Course.java           实体类（@TableName 映射表）
    ├── CourseCreateRequest.java  请求 DTO
    └── CourseResponse.java       响应 DTO
```

### 核心实体与表

- **Course** (`courses`) — 课程表（由同学A 负责，本模块仅通过 `CourseMapper` 做存在性校验）
- **CourseDocument** (`documents`) — 文档表：文件路径、类型、解析状态（UPLOADED/PARSING/PARSED/FAILED）、切片数量
- **Question** (`questions`) — 题库表：支持 single_choice / multi_choice / true_false / short_answer 四种题型，`options` 字段为 JSON 字符串，难度分 easy/medium/hard
- **PracticeRecord** (`practice_records`) — 练习记录表：提交答案、对错判定
- **KnowledgeChunk** (`knowledge_chunks`) — 知识片段表：正文存 MySQL，Milvus 向量 ID 关联

状态/类型常量直接定义在实体类中（如 `CourseDocument.STATUS_UPLOADED`、`Question.TYPE_SINGLE_CHOICE`）。

实体类使用手工 getter/setter（非 Lombok @Data）。`created_at` / `updated_at` 由数据库 `DEFAULT CURRENT_TIMESTAMP` 自动维护。

### 统一响应格式

所有接口返回 `Result<T>`：`Result.ok(data)` 返回 200，`Result.fail(code, msg)` 返回错误码。
`BizException` 用于业务异常（默认 400），`GlobalExceptionHandler` 统一处理：
- `BizException` → 返回对应 code 和 message
- `IllegalArgumentException` → 400
- `RuntimeException` → 500
- `Exception`（兜底） → 500 "系统内部错误"

### 文件上传

文件存储在 `app.upload.dir`（默认 `./uploads`），按 `documents/{courseId}/` 子目录组织，文件名加时间戳前缀防冲突。

## 数据库自动初始化

应用启动时自动初始化数据库，无需手动执行 SQL：

- JDBC URL 中的 `createDatabaseIfNotExist=true` 自动创建数据库
- `spring.sql.init.mode=always` + `schema.sql` 自动建表（所有建表语句均使用 `CREATE TABLE IF NOT EXISTS`，可重复执行）
- 前提：本地 MySQL 服务已运行，root 密码为 `java0975`（开发环境默认值，见 `application.yml`）

建表 SQL：`backend/src/main/resources/db/schema.sql`
手动初始化脚本：`sql/init.sql`

## 模块间协作

- **依赖同学A**：`courses` 表已就绪，文档/题目保存时通过 `CourseMapper.selectById()` 校验课程存在性
- **被同学C依赖**：
  - `DocumentService.updateParseStatus(id, status, chunkCount)` — 文档解析后更新状态
  - `QuestionService.batchSave(List<Question>)` — 批量保存 AI 生成题目
  - `knowledge_chunks` 表 — 知识片段正文存储
- **被同学D依赖**：REST API（详见 `backend/docs/api-guide.md`）

## 不要做的事

- 不要创建或修改 agent 包下的任何内容（同学C 的负责范围）

## API 文档

完整接口说明（含请求示例、字段约定、响应码）在 `backend/docs/api-guide.md`。

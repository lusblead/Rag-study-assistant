-- Courses table for RAG study assistant
CREATE TABLE IF NOT EXISTS courses (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL COMMENT '课程名称',
    description VARCHAR(500) COMMENT '课程描述',
    term        VARCHAR(50)  COMMENT '学期',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

-- Documents table
CREATE TABLE IF NOT EXISTS documents (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id    BIGINT       NOT NULL COMMENT '关联课程ID',
    filename     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_type    VARCHAR(50)  NOT NULL COMMENT '文件类型（pdf/pptx/docx/txt）',
    file_path    VARCHAR(500) NOT NULL COMMENT '本地存储路径',
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' COMMENT '解析状态：UPLOADED/PARSING/PARSED/FAILED',
    chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '知识切片数量',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_documents_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- Knowledge chunks table (Agent module uses this for chunk content in MySQL)
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id        BIGINT       NOT NULL COMMENT '课程ID',
    document_id      BIGINT       NOT NULL COMMENT '文档ID',
    chunk_index      INT          NOT NULL COMMENT '片段序号',
    title            VARCHAR(255) COMMENT '章节标题',
    content          TEXT         NOT NULL COMMENT '片段正文',
    source_page      INT          COMMENT '来源页码',
    token_count      INT          COMMENT '估算token数量',
    milvus_vector_id VARCHAR(100) COMMENT 'Milvus向量ID',
    embedding_status VARCHAR(50)  DEFAULT 'PENDING' COMMENT 'PENDING/DONE/FAILED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chunks_course (course_id),
    INDEX idx_chunks_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识片段表';

-- Questions / Quiz table
CREATE TABLE IF NOT EXISTS question_batches (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id                BIGINT       NOT NULL COMMENT '课程ID',
    title                    VARCHAR(255) NOT NULL COMMENT '批次或套卷名称',
    mode                     VARCHAR(20)  NOT NULL DEFAULT 'practice' COMMENT 'practice/exam',
    requirement              TEXT COMMENT '出题要求',
    question_count           INT          NOT NULL DEFAULT 0,
    question_type            VARCHAR(64),
    difficulty               VARCHAR(10),
    reference_real_questions BOOLEAN      NOT NULL DEFAULT FALSE,
    style_summary            TEXT COMMENT '可复用的真实命题风格画像',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_question_batches_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 出题批次与套卷';

CREATE TABLE IF NOT EXISTS question_batch_documents (
    batch_id    BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    PRIMARY KEY (batch_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS question_batch_chunks (
    batch_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    PRIMARY KEY (batch_id, chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS questions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id       BIGINT        NOT NULL COMMENT '关联课程ID',
    source_chunk_id BIGINT        COMMENT '来源知识片段ID（可为空）',
    batch_id        BIGINT        COMMENT '所属出题批次',
    type            VARCHAR(64)   NOT NULL COMMENT '可扩展题型标识',
    stem            TEXT          NOT NULL COMMENT '题干',
    options         JSON          COMMENT '选项（JSON格式，如 ["A.选项1","B.选项2"]）',
    answer          VARCHAR(500)  NOT NULL COMMENT '答案',
    explanation     TEXT          COMMENT '解析',
    difficulty      VARCHAR(10)   COMMENT '难度：easy/medium/hard',
    knowledge_point VARCHAR(255)  COMMENT '知识点',
    chapter_tags    JSON          COMMENT '题目所属章节数组；允许多个章节',
    question_data   TEXT          COMMENT '复杂题材料、要求和小题 JSON',
    answer_schema   TEXT          COMMENT '结构化参考答案和评分标准 JSON',
    subject         VARCHAR(32)   NOT NULL DEFAULT 'general' COMMENT 'general/chinese',
    grading_strategy VARCHAR(32)  NOT NULL DEFAULT 'rule' COMMENT 'rule/manual/ai/mixed',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_questions_course (course_id),
    INDEX idx_questions_batch (batch_id),
    INDEX idx_questions_type (type),
    INDEX idx_questions_subject (subject),
    INDEX idx_questions_difficulty (difficulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题库表';

-- Practice records table
CREATE TABLE IF NOT EXISTS practice_records (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id   BIGINT       NOT NULL COMMENT '关联课程ID',
    question_id BIGINT       NOT NULL COMMENT '关联题目ID',
    user_answer VARCHAR(500) COMMENT '用户答案',
    is_correct  BOOLEAN      COMMENT '是否正确',
    grading_mode VARCHAR(40) COMMENT 'rule/ai',
    grading_feedback TEXT COMMENT 'AI grading feedback',
    answer_payload TEXT COMMENT '复合题答案 JSON',
    score DECIMAL(7,2),
    max_score DECIMAL(7,2),
    grading_status VARCHAR(32) NOT NULL DEFAULT 'graded',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_practice_course (course_id),
    INDEX idx_practice_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='练习记录表';

ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS grading_mode VARCHAR(40) COMMENT 'rule/ai';
ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS grading_feedback TEXT COMMENT 'AI grading feedback';
ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS answer_payload TEXT COMMENT '复合题答案 JSON';
ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS score DECIMAL(7,2);
ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS max_score DECIMAL(7,2);
ALTER TABLE practice_records ADD COLUMN IF NOT EXISTS grading_status VARCHAR(32) NOT NULL DEFAULT 'graded';

-- Chat sessions table
CREATE TABLE IF NOT EXISTS chat_sessions (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id  BIGINT       NOT NULL COMMENT '关联课程ID',
    title      VARCHAR(100) COMMENT '会话标题',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_chat_sessions_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮会话表';

-- Runtime model settings used by the frontend settings page.
CREATE TABLE IF NOT EXISTS agent_model_settings (
    id                 BIGINT PRIMARY KEY,
    llm_provider       VARCHAR(80)  NOT NULL,
    llm_base_url       VARCHAR(500) NOT NULL,
    llm_model          VARCHAR(200) NOT NULL,
    llm_api_key        TEXT,
    embedding_provider VARCHAR(80)  NOT NULL,
    embedding_base_url VARCHAR(500) NOT NULL,
    embedding_model    VARCHAR(200) NOT NULL,
    embedding_api_key  TEXT,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent runtime model settings';

CREATE TABLE IF NOT EXISTS rerank_runtime_settings (
    id         BIGINT PRIMARY KEY,
    provider   VARCHAR(40)  NOT NULL,
    base_url   VARCHAR(500) NOT NULL,
    model      VARCHAR(200) NOT NULL,
    api_key    TEXT,
    fail_open  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时 Rerank 配置';

-- Chat messages table
CREATE TABLE IF NOT EXISTS chat_messages (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT      NOT NULL COMMENT '会话ID',
    role       VARCHAR(20) NOT NULL COMMENT 'user/assistant',
    content    TEXT        NOT NULL COMMENT '消息内容',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_chat_messages_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮会话消息表';

CREATE TABLE IF NOT EXISTS papers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, course_id BIGINT NOT NULL,
    subject VARCHAR(32) NOT NULL DEFAULT 'chinese', title VARCHAR(255) NOT NULL,
    paper_type VARCHAR(64), grade_level VARCHAR(64), difficulty VARCHAR(32),
    duration_minutes INT, total_score DECIMAL(7,2), template_code VARCHAR(128),
    requirements TEXT, warnings TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_papers_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷元数据';

CREATE TABLE IF NOT EXISTS paper_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, paper_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
    section_key VARCHAR(128) NOT NULL, section_title VARCHAR(255) NOT NULL,
    section_instructions VARCHAR(500), question_order INT NOT NULL, score DECIMAL(7,2),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_paper_question (paper_id, question_id),
    INDEX idx_paper_question_order (paper_id, question_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷题目与分区顺序';

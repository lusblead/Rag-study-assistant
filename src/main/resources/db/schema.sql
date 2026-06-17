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

-- Questions / Quiz table
CREATE TABLE IF NOT EXISTS questions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id       BIGINT        NOT NULL COMMENT '关联课程ID',
    source_chunk_id BIGINT        COMMENT '来源知识片段ID（可为空）',
    type            VARCHAR(20)   NOT NULL COMMENT '题型：single_choice/multi_choice/true_false/short_answer',
    stem            TEXT          NOT NULL COMMENT '题干',
    options         JSON          COMMENT '选项（JSON格式，如 ["A.选项1","B.选项2"]）',
    answer          VARCHAR(500)  NOT NULL COMMENT '答案',
    explanation     TEXT          COMMENT '解析',
    difficulty      VARCHAR(10)   COMMENT '难度：easy/medium/hard',
    knowledge_point VARCHAR(255)  COMMENT '知识点',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_questions_course (course_id),
    INDEX idx_questions_type (type),
    INDEX idx_questions_difficulty (difficulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题库表';

-- 方案三迁移基线：只建立可靠摄取改造前已经存在的业务表。
-- Flyway 已记录的迁移不可回写；后续字段一律放到更高版本迁移。
CREATE TABLE courses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    term VARCHAR(50),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    parse_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    chunk_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_documents_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    title VARCHAR(255),
    content TEXT NOT NULL,
    source_page INT,
    token_count INT,
    milvus_vector_id VARCHAR(100),
    embedding_status VARCHAR(50) DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chunks_course (course_id),
    INDEX idx_chunks_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE question_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'practice',
    requirement TEXT,
    question_count INT NOT NULL DEFAULT 0,
    question_type VARCHAR(64),
    difficulty VARCHAR(10),
    reference_real_questions BOOLEAN NOT NULL DEFAULT FALSE,
    style_summary TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_question_batches_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE question_batch_documents (
    batch_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    PRIMARY KEY (batch_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE question_batch_chunks (
    batch_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    PRIMARY KEY (batch_id, chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE questions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    source_chunk_id BIGINT,
    batch_id BIGINT,
    type VARCHAR(64) NOT NULL,
    stem TEXT NOT NULL,
    options JSON,
    answer VARCHAR(500) NOT NULL,
    explanation TEXT,
    difficulty VARCHAR(10),
    knowledge_point VARCHAR(255),
    chapter_tags JSON,
    question_data TEXT,
    answer_schema TEXT,
    subject VARCHAR(32) NOT NULL DEFAULT 'general',
    grading_strategy VARCHAR(32) NOT NULL DEFAULT 'rule',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_questions_course (course_id),
    INDEX idx_questions_batch (batch_id),
    INDEX idx_questions_type (type),
    INDEX idx_questions_subject (subject),
    INDEX idx_questions_difficulty (difficulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE practice_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    user_answer VARCHAR(500),
    is_correct BOOLEAN,
    grading_mode VARCHAR(40),
    grading_feedback TEXT,
    answer_payload TEXT,
    score DECIMAL(7,2),
    max_score DECIMAL(7,2),
    grading_status VARCHAR(32) NOT NULL DEFAULT 'graded',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_practice_course (course_id),
    INDEX idx_practice_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chat_sessions_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_messages_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_model_settings (
    id BIGINT PRIMARY KEY,
    llm_provider VARCHAR(80) NOT NULL,
    llm_base_url VARCHAR(500) NOT NULL,
    llm_model VARCHAR(200) NOT NULL,
    llm_api_key TEXT,
    embedding_provider VARCHAR(80) NOT NULL,
    embedding_base_url VARCHAR(500) NOT NULL,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_api_key TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rerank_runtime_settings (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(200) NOT NULL,
    api_key TEXT,
    fail_open BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE papers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    subject VARCHAR(32) NOT NULL DEFAULT 'chinese',
    title VARCHAR(255) NOT NULL,
    paper_type VARCHAR(64),
    grade_level VARCHAR(64),
    difficulty VARCHAR(32),
    duration_minutes INT,
    total_score DECIMAL(7,2),
    template_code VARCHAR(128),
    requirements TEXT,
    warnings TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_papers_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paper_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    paper_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    section_key VARCHAR(128) NOT NULL,
    section_title VARCHAR(255) NOT NULL,
    section_instructions VARCHAR(500),
    question_order INT NOT NULL,
    score DECIMAL(7,2),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_paper_question (paper_id, question_id),
    INDEX idx_paper_question_order (paper_id, question_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

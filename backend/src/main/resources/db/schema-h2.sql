CREATE TABLE IF NOT EXISTS courses (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    term        VARCHAR(50),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS documents (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id    BIGINT       NOT NULL,
    filename     VARCHAR(255) NOT NULL,
    file_type    VARCHAR(50)  NOT NULL,
    file_path    VARCHAR(1000) NOT NULL,
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    chunk_count  INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_course ON documents(course_id);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id        BIGINT       NOT NULL,
    document_id      BIGINT       NOT NULL,
    chunk_index      INT          NOT NULL,
    title            VARCHAR(255),
    content          CLOB         NOT NULL,
    source_page      INT,
    token_count      INT,
    milvus_vector_id VARCHAR(100),
    embedding_status VARCHAR(50)  DEFAULT 'PENDING',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunks_course ON knowledge_chunks(course_id);
CREATE INDEX IF NOT EXISTS idx_chunks_document ON knowledge_chunks(document_id);

CREATE TABLE IF NOT EXISTS questions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id       BIGINT        NOT NULL,
    source_chunk_id BIGINT,
    type            VARCHAR(20)   NOT NULL,
    stem            CLOB          NOT NULL,
    options         CLOB,
    answer          VARCHAR(500)  NOT NULL,
    explanation     CLOB,
    difficulty      VARCHAR(10),
    knowledge_point VARCHAR(255),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_questions_course ON questions(course_id);
CREATE INDEX IF NOT EXISTS idx_questions_type ON questions(type);
CREATE INDEX IF NOT EXISTS idx_questions_difficulty ON questions(difficulty);

CREATE TABLE IF NOT EXISTS practice_records (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id   BIGINT       NOT NULL,
    question_id BIGINT       NOT NULL,
    user_answer VARCHAR(500),
    is_correct  BOOLEAN,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_practice_course ON practice_records(course_id);
CREATE INDEX IF NOT EXISTS idx_practice_question ON practice_records(question_id);

CREATE TABLE IF NOT EXISTS chat_sessions (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id  BIGINT       NOT NULL,
    title      VARCHAR(100),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_course ON chat_sessions(course_id);

CREATE TABLE IF NOT EXISTS agent_model_settings (
    id                 BIGINT PRIMARY KEY,
    llm_provider       VARCHAR(80)  NOT NULL,
    llm_base_url       VARCHAR(500) NOT NULL,
    llm_model          VARCHAR(200) NOT NULL,
    llm_api_key        CLOB,
    embedding_provider VARCHAR(80)  NOT NULL,
    embedding_base_url VARCHAR(500) NOT NULL,
    embedding_model    VARCHAR(200) NOT NULL,
    embedding_api_key  CLOB,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL,
    content    CLOB        NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id);

-- Step 2.2：MySQL 仍是 Chunk 正文事实源；严格创建单一 ngram FULLTEXT 索引。
-- 不包裹异常处理：插件、权限或索引创建失败必须直接令 Flyway/startup 失败。
ALTER TABLE knowledge_chunks
    ADD FULLTEXT INDEX ft_knowledge_chunks_title_content (title, content)
    WITH PARSER ngram;

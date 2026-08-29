package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.repository.KnowledgeChunkMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalFullTextContractTest {

    @Test
    void mapperQueriesFreezeCourseVersionThresholdOrderAndLimit() throws Exception {
        String natural = sql("selectLexicalNatural");
        String phrase = sql("selectLexicalBooleanPhrase");

        assertCommonContract(natural);
        assertCommonContract(phrase);
        assertTrue(natural.contains("in natural language mode"));
        assertTrue(phrase.contains("in boolean mode"));
        assertFalse(natural.contains("query expansion"));
        assertFalse(phrase.contains("query expansion"));
        assertFalse(natural.contains("${"), "queries must use bound parameters");
        assertFalse(phrase.contains("${"), "queries must use bound parameters");
    }

    @Test
    void v4MigrationCreatesOneCombinedNgramFullTextIndex() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V4__knowledge_chunks_lexical_fulltext.sql")) {
            assertTrue(stream != null, "V4 migration must be on the classpath");
            String migration = new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            assertTrue(migration.contains(
                    "add fulltext index ft_knowledge_chunks_title_content (title, content) with parser ngram"));
            assertFalse(migration.contains("if not exists"),
                    "a failed prerequisite must not be silently ignored");
        }
    }

    private String sql(String methodName) throws Exception {
        Method method = KnowledgeChunkMapper.class.getMethod(
                methodName, long.class, List.class, String.class,
                double.class, int.class);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value())
                .toLowerCase(Locale.ROOT)
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ");
    }

    private void assertCommonContract(String sql) {
        assertTrue(sql.contains("match(kc.title, kc.content)"));
        assertTrue(sql.contains("kc.course_id = #{courseid}"));
        assertTrue(sql.contains("kc.document_version_id in"));
        assertTrue(sql.contains("collection=\"activeversionids\""));
        assertTrue(sql.contains("> 0"),
                "threshold zero must not return unrelated rows");
        assertTrue(sql.contains(">= #{threshold}"));
        assertTrue(sql.contains("order by raw_score desc, kc.id asc"));
        assertTrue(sql.contains("limit #{candidatek}"));
    }
}

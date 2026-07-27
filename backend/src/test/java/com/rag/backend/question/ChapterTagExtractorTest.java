package com.rag.backend.question;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChapterTagExtractorTest {
    @Test
    void extractsChineseAndEnglishChapterNamesWithoutDuplicates() {
        assertEquals(
                List.of("第三章", "第5章"),
                ChapterTagExtractor.extract("理论3-第三章+软件质量.pdf", "DBMS-ch5-实现篇.pdf", "第三章复习")
        );
    }

    @Test
    void supportsLeadingNumberCourseFilesAndFullWidthDigits() {
        assertEquals(
                List.of("第2章", "第4章"),
                ChapterTagExtractor.extract("2-知识表示与知识图谱.pdf", "第４章 串.pdf")
        );
    }
}

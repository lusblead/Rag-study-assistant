package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedTableExtractorTest {
    private final RuleBasedTableExtractor extractor = new RuleBasedTableExtractor();

    @Test
    void convertsSimpleAlignedTableToMarkdown() {
        List<ParsedTable> tables = extractor.extract(2, "姓名  年龄\n张三  18\n\n普通正文");

        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().pageNo()).isEqualTo(2);
        assertThat(tables.getFirst().markdown()).contains("| 姓名 | 年龄 |");
        assertThat(tables.getFirst().markdown()).contains("| 张三 | 18 |");
    }
}

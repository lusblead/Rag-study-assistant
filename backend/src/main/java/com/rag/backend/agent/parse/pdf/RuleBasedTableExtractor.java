package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RuleBasedTableExtractor implements TableExtractor {
    private static final Pattern CELL_SEPARATOR = Pattern.compile("\\s{2,}|\\t|\\|");

    @Override
    public List<ParsedTable> extract(int pageNo, String cleanedText) {
        if (cleanedText == null || cleanedText.isBlank()) {
            return List.of();
        }
        List<ParsedTable> tables = new ArrayList<>();
        List<List<String>> current = new ArrayList<>();
        int tableIndex = 0;
        for (String line : cleanedText.split("\\n")) {
            List<String> cells = splitCells(line);
            if (cells.size() >= 2) {
                current.add(cells);
            } else {
                tableIndex = flush(pageNo, tables, current, tableIndex);
            }
        }
        flush(pageNo, tables, current, tableIndex);
        return tables;
    }

    private int flush(int pageNo, List<ParsedTable> tables, List<List<String>> rows, int tableIndex) {
        if (rows.size() >= 2) {
            tables.add(new ParsedTable(pageNo, tableIndex, List.copyOf(rows), toMarkdown(rows)));
            tableIndex++;
        }
        rows.clear();
        return tableIndex;
    }

    private List<String> splitCells(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String normalized = line.trim();
        if (!normalized.contains("|") && !normalized.contains("\t") && !normalized.matches(".*\\S\\s{2,}\\S.*")) {
            return List.of();
        }
        String[] parts = CELL_SEPARATOR.split(normalized);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            String cell = part.trim();
            if (!cell.isEmpty()) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private String toMarkdown(List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns < 2) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendRow(builder, rows.get(0), columns);
        builder.append('|');
        for (int i = 0; i < columns; i++) {
            builder.append(" --- |");
        }
        builder.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendRow(builder, rows.get(i), columns);
        }
        return builder.toString().trim();
    }

    private void appendRow(StringBuilder builder, List<String> row, int columns) {
        builder.append('|');
        for (int i = 0; i < columns; i++) {
            String value = i < row.size() ? row.get(i).replace("|", "\\|") : "";
            builder.append(' ').append(value).append(" |");
        }
        builder.append('\n');
    }
}

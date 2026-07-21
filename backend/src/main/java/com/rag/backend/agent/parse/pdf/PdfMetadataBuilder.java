package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.DocumentMetadata;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;

@Component
public class PdfMetadataBuilder implements MetadataBuilder {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public DocumentMetadata build(PDDocument document, Path filePath, String fileHash) {
        PDDocumentInformation info = document.getDocumentInformation();
        return new DocumentMetadata(
                firstNonBlank(info.getTitle(), filePath.getFileName().toString()),
                info.getAuthor(),
                document.getNumberOfPages(),
                format(info.getCreationDate()),
                format(info.getModificationDate()),
                size(filePath),
                "unknown",
                filePath.toAbsolutePath().toString(),
                fileHash
        );
    }

    private String format(Calendar calendar) {
        if (calendar == null) {
            return "";
        }
        return FORMATTER.format(calendar.toInstant().atZone(ZoneId.systemDefault()));
    }

    private long size(Path filePath) {
        try {
            return Files.size(filePath);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}

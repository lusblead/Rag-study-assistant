package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.DocumentMetadata;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Path;

public interface MetadataBuilder {
    DocumentMetadata build(PDDocument document, Path filePath, String fileHash);
}

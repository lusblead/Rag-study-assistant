package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.DocumentMetadata;
import com.rag.backend.agent.model.LayoutBlock;
import com.rag.backend.agent.model.OcrInfo;
import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParseLog;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.ParsedImage;
import com.rag.backend.agent.model.ParsedTable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

@Component
public class PdfParser {
    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    private final PdfTextExtractor textExtractor;
    private final OcrDecisionPolicy ocrDecisionPolicy;
    private final OcrService ocrService;
    private final LayoutAnalyzer layoutAnalyzer;
    private final TextCleaner textCleaner;
    private final MarkdownBuilder markdownBuilder;
    private final MetadataBuilder metadataBuilder;
    private final TableExtractor tableExtractor;
    private final PdfImageExtractor imageExtractor;

    public PdfParser(PdfTextExtractor textExtractor,
                     OcrDecisionPolicy ocrDecisionPolicy,
                     OcrService ocrService,
                     LayoutAnalyzer layoutAnalyzer,
                     TextCleaner textCleaner,
                     MarkdownBuilder markdownBuilder,
                     MetadataBuilder metadataBuilder,
                     TableExtractor tableExtractor,
                     PdfImageExtractor imageExtractor) {
        this.textExtractor = textExtractor;
        this.ocrDecisionPolicy = ocrDecisionPolicy;
        this.ocrService = ocrService;
        this.layoutAnalyzer = layoutAnalyzer;
        this.textCleaner = textCleaner;
        this.markdownBuilder = markdownBuilder;
        this.metadataBuilder = metadataBuilder;
        this.tableExtractor = tableExtractor;
        this.imageExtractor = imageExtractor;
    }

    public ParsedDocument parse(Path filePath) {
        long started = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        String hash = sha256(filePath);
        log.info("PDF parse start file={} hash={}", filePath, hash);

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int totalPages = document.getNumberOfPages();
            DocumentMetadata metadata = metadataBuilder.build(document, filePath, hash);

            long textStart = System.currentTimeMillis();
            List<PageText> rawPages = textExtractor.extract(document);
            long textMs = System.currentTimeMillis() - textStart;

            List<Integer> pagesNeedingOcr = rawPages.stream()
                    .filter(page -> ocrDecisionPolicy.needsOcr(page.text()))
                    .map(PageText::pageNo)
                    .toList();

            OcrBatchResult ocrResult = pagesNeedingOcr.isEmpty()
                    ? new OcrBatchResult(Map.of(), List.of(), false, 0L)
                    : ocrService.recognizePages(new PDFRenderer(document), pagesNeedingOcr, hash);

            if (!ocrResult.failures().isEmpty()) {
                warnings.add("OCR failed pages: " + ocrResult.failures());
            }

            List<ParsedImage> images = extractImages(document, warnings);
            Map<Integer, List<ParsedImage>> imagesByPage = groupImages(images);
            Map<Integer, String> finalRawTextByPage = mergeText(rawPages, ocrResult.pageTexts());
            List<String> repeatedLines = textCleaner.detectRepeatedHeadersAndFooters(finalRawTextByPage.values());

            List<PageText> finalPages = new ArrayList<>();
            List<String> pageMarkdowns = new ArrayList<>();
            List<ParsedTable> allTables = new ArrayList<>();
            StringBuilder plainText = new StringBuilder();

            for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
                String cleaned = textCleaner.clean(finalRawTextByPage.get(pageNo), repeatedLines);
                List<ParsedImage> pageImages = imagesByPage.getOrDefault(pageNo, List.of());
                List<ParsedTable> pageTables = tableExtractor.extract(pageNo, cleaned);
                List<LayoutBlock> blocks = layoutAnalyzer.analyze(pageNo, cleaned, pageImages);
                String pageMarkdown = markdownBuilder.buildPageMarkdown(pageNo, blocks, pageTables, pageImages);
                String ocrStatus = ocrStatus(pageNo, pagesNeedingOcr, ocrResult);

                finalPages.add(new PageText(pageNo, cleaned, pageMarkdown, pageImages, pageTables, ocrStatus, blocks));
                pageMarkdowns.add(pageMarkdown);
                allTables.addAll(pageTables);
                if (!cleaned.isBlank()) {
                    plainText.append(cleaned).append("\n\n");
                }
            }

            if (plainText.toString().isBlank()) {
                throw new RuntimeException("PDF contains no extractable text. OCR failures=" + ocrResult.failures());
            }

            String title = metadata.title().isBlank() ? filePath.getFileName().toString() : metadata.title();
            String markdown = markdownBuilder.buildDocumentMarkdown(title, pageMarkdowns);
            long finished = System.currentTimeMillis();
            ParseLog parseLog = new ParseLog(started, finished, totalPages,
                    totalPages - pagesNeedingOcr.size(), pagesNeedingOcr.size(), textMs,
                    ocrResult.durationMs(), finished - started, warnings);
            OcrInfo ocrInfo = new OcrInfo(totalPages, pagesNeedingOcr.size(), ocrResult.pageTexts().size(),
                    ocrResult.failures().size(), ocrResult.failures(), ocrResult.cacheHit(), ocrResult.durationMs());

            log.info("PDF parse done file={} pages={} textPages={} ocrPages={} ocrFailures={} totalMs={}",
                    filePath, totalPages, parseLog.textPages(), parseLog.ocrPages(), ocrInfo.failedPages(), parseLog.totalMs());
            return new ParsedDocument(title, plainText.toString().trim(), finalPages, markdown, metadata,
                    List.of(), allTables, images, ocrInfo, parseLog);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PDF file", e);
        }
    }

    private List<ParsedImage> extractImages(PDDocument document, List<String> warnings) {
        try {
            return imageExtractor.extract(document);
        } catch (Exception e) {
            warnings.add("Image extraction failed: " + e.getMessage());
            return List.of();
        }
    }

    private Map<Integer, String> mergeText(List<PageText> rawPages, Map<Integer, String> ocrTextByPage) {
        Map<Integer, String> merged = new LinkedHashMap<>();
        for (PageText page : rawPages) {
            String ocrText = ocrTextByPage.get(page.pageNo());
            merged.put(page.pageNo(), ocrText == null || ocrText.isBlank() ? page.text() : ocrText);
        }
        return merged;
    }

    private Map<Integer, List<ParsedImage>> groupImages(List<ParsedImage> images) {
        Map<Integer, List<ParsedImage>> grouped = new HashMap<>();
        for (ParsedImage image : images) {
            grouped.computeIfAbsent(image.pageNo(), ignored -> new ArrayList<>()).add(image);
        }
        return grouped;
    }

    private String ocrStatus(int pageNo, List<Integer> pagesNeedingOcr, OcrBatchResult result) {
        if (!pagesNeedingOcr.contains(pageNo)) {
            return PageText.OCR_NOT_REQUIRED;
        }
        if (result.pageTexts().containsKey(pageNo)) {
            return PageText.OCR_SUCCESS;
        }
        return PageText.OCR_FAILED;
    }

    private String sha256(Path filePath) {
        try (InputStream input = Files.newInputStream(filePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }
}

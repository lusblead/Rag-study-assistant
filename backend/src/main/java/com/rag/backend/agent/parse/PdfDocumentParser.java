package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PdfDocumentParser implements DocumentParser {
    private final String tesseractCommand;
    private final String tesseractLanguage;
    private final int ocrDpi;
    private final long ocrTimeoutSeconds;

    public PdfDocumentParser(@Value("${ocr.tesseract.command:tesseract}") String tesseractCommand,
                             @Value("${ocr.tesseract.language:chi_sim+eng}") String tesseractLanguage,
                             @Value("${ocr.tesseract.dpi:220}") int ocrDpi,
                             @Value("${ocr.tesseract.timeout-seconds:60}") long ocrTimeoutSeconds) {
        this.tesseractCommand = tesseractCommand;
        this.tesseractLanguage = tesseractLanguage;
        this.ocrDpi = ocrDpi;
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageText> pages = new ArrayList<>();
            StringBuilder all = new StringBuilder();
            Exception firstOcrError = null;

            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).trim();
                if (text.isBlank()) {
                    try {
                        text = ocrPage(renderer, page - 1).trim();
                    } catch (Exception e) {
                        if (firstOcrError == null) {
                            firstOcrError = e;
                        }
                    }
                }
                if (!text.isBlank()) {
                    pages.add(new PageText(page, text));
                    all.append(text).append("\n\n");
                }
            }

            if (all.toString().isBlank()) {
                if (firstOcrError != null) {
                    throw new RuntimeException("PDF contains no extractable text and OCR failed. "
                            + "Install Tesseract with the configured language pack or upload a text-based PDF. "
                            + "Cause: " + firstOcrError.getMessage(), firstOcrError);
                }
                throw new RuntimeException("PDF contains no extractable text. Scanned PDFs need OCR.");
            }
            return new ParsedDocument(filePath.getFileName().toString(), all.toString(), pages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PDF file", e);
        }
    }

    private String ocrPage(PDFRenderer renderer, int pageIndex) throws Exception {
        Path imagePath = Files.createTempFile("rag-pdf-ocr-", ".png");
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, ocrDpi);
            ImageIO.write(image, "png", imagePath.toFile());
            ProcessBuilder builder = new ProcessBuilder(
                    tesseractCommand,
                    imagePath.toAbsolutePath().toString(),
                    "stdout",
                    "-l",
                    tesseractLanguage,
                    "--psm",
                    "6"
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(ocrTimeoutSeconds, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Tesseract OCR timed out after " + ocrTimeoutSeconds + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException(output.isBlank() ? "Tesseract OCR failed" : output.trim());
            }
            return output;
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }
}

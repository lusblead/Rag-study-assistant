package com.rag.backend.agent.parse.pdf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

// PDF 解析关键参数，均可通过 application.yml / 环境变量配置。
@Component
public class PdfParsingProperties {
    private final String tesseractCommand;
    private final String tesseractLanguage;
    private final int ocrDpi;
    private final long ocrTimeoutSeconds;
    private final int ocrThreadCount;
    private final boolean ocrCacheEnabled;
    private final Path ocrCacheDir;
    private final int maxImagePixels;

    public PdfParsingProperties(@Value("${ocr.tesseract.command:tesseract}") String tesseractCommand,
                                @Value("${ocr.tesseract.language:chi_sim+eng}") String tesseractLanguage,
                                @Value("${ocr.tesseract.dpi:220}") int ocrDpi,
                                @Value("${ocr.tesseract.timeout-seconds:60}") long ocrTimeoutSeconds,
                                @Value("${ocr.thread-count:4}") int ocrThreadCount,
                                @Value("${ocr.cache.enabled:true}") boolean ocrCacheEnabled,
                                @Value("${ocr.cache.dir:./uploads/.ocr-cache}") String ocrCacheDir,
                                @Value("${ocr.max-image-pixels:12000000}") int maxImagePixels) {
        this.tesseractCommand = tesseractCommand;
        this.tesseractLanguage = tesseractLanguage;
        this.ocrDpi = Math.max(72, ocrDpi);
        this.ocrTimeoutSeconds = Math.max(1L, ocrTimeoutSeconds);
        this.ocrThreadCount = Math.max(1, ocrThreadCount);
        this.ocrCacheEnabled = ocrCacheEnabled;
        this.ocrCacheDir = Path.of(ocrCacheDir);
        this.maxImagePixels = Math.max(1_000_000, maxImagePixels);
    }

    public String tesseractCommand() {
        return tesseractCommand;
    }

    public String tesseractLanguage() {
        return tesseractLanguage;
    }

    public int ocrDpi() {
        return ocrDpi;
    }

    public long ocrTimeoutSeconds() {
        return ocrTimeoutSeconds;
    }

    public int ocrThreadCount() {
        return ocrThreadCount;
    }

    public boolean ocrCacheEnabled() {
        return ocrCacheEnabled;
    }

    public Path ocrCacheDir() {
        return ocrCacheDir;
    }

    public int maxImagePixels() {
        return maxImagePixels;
    }
}

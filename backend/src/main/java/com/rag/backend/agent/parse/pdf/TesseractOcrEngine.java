package com.rag.backend.agent.parse.pdf;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class TesseractOcrEngine implements OcrEngine {
    private final PdfParsingProperties properties;

    public TesseractOcrEngine(PdfParsingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String recognize(BufferedImage image, int pageNo) throws Exception {
        Path imagePath = Files.createTempFile("rag-pdf-ocr-page-" + pageNo + "-", ".png");
        try {
            ImageIO.write(image, "png", imagePath.toFile());
            ProcessBuilder builder = new ProcessBuilder(
                    properties.tesseractCommand(),
                    imagePath.toAbsolutePath().toString(),
                    "stdout",
                    "-l",
                    properties.tesseractLanguage(),
                    "--psm",
                    "6"
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(properties.ocrTimeoutSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Tesseract OCR timed out after " + properties.ocrTimeoutSeconds() + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException(output.isBlank() ? "Tesseract OCR failed" : output.trim());
            }
            return output.trim();
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }
}

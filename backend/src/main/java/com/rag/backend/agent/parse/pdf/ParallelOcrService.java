package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.OcrPageFailure;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class ParallelOcrService implements OcrService {
    private final OcrEngine ocrEngine;
    private final PdfParsingProperties properties;

    public ParallelOcrService(OcrEngine ocrEngine, PdfParsingProperties properties) {
        this.ocrEngine = ocrEngine;
        this.properties = properties;
    }

    @Override
    public OcrBatchResult recognizePages(PDFRenderer renderer, Collection<Integer> pageNumbers, String documentHash) {
        long start = System.currentTimeMillis();
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        Map<Integer, String> pageTexts = new LinkedHashMap<>();
        List<OcrPageFailure> failures = new ArrayList<>();
        List<Integer> pagesToRun = new ArrayList<>();
        boolean allCacheHits = !orderedPages.isEmpty();

        for (Integer pageNo : orderedPages) {
            String cached = readCached(documentHash, pageNo);
            if (cached != null) {
                pageTexts.put(pageNo, cached);
            } else {
                allCacheHits = false;
                pagesToRun.add(pageNo);
            }
        }

        if (!pagesToRun.isEmpty()) {
            ExecutorService executor = Executors.newFixedThreadPool(properties.ocrThreadCount());
            try {
                List<Future<OcrPageResult>> futures = pagesToRun.stream()
                        .map(pageNo -> executor.submit(createOcrTask(renderer, pageNo, documentHash)))
                        .toList();
                for (Future<OcrPageResult> future : futures) {
                    try {
                        OcrPageResult result = future.get();
                        if (result.success()) {
                            pageTexts.put(result.pageNo(), result.text());
                        } else {
                            failures.add(new OcrPageFailure(result.pageNo(), result.errorMessage()));
                        }
                    } catch (Exception e) {
                        failures.add(new OcrPageFailure(0, rootMessage(e)));
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }

        Map<Integer, String> orderedText = new LinkedHashMap<>();
        pageTexts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedText.put(entry.getKey(), entry.getValue()));
        failures.sort(Comparator.comparingInt(OcrPageFailure::pageNo));
        return new OcrBatchResult(orderedText, failures, allCacheHits, System.currentTimeMillis() - start);
    }

    private Callable<OcrPageResult> createOcrTask(PDFRenderer renderer, int pageNo, String documentHash) {
        return () -> {
            try {
                BufferedImage image;
                synchronized (renderer) {
                    image = renderer.renderImageWithDPI(pageNo - 1, properties.ocrDpi());
                }
                BufferedImage bounded = downscaleIfNeeded(image);
                String text = ocrEngine.recognize(bounded, pageNo);
                writeCached(documentHash, pageNo, text);
                return OcrPageResult.success(pageNo, text);
            } catch (Exception e) {
                return OcrPageResult.failure(pageNo, rootMessage(e));
            }
        };
    }

    private BufferedImage downscaleIfNeeded(BufferedImage image) {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= properties.maxImagePixels()) {
            return image;
        }
        double scale = Math.sqrt(properties.maxImagePixels() / (double) pixels);
        int width = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private String readCached(String documentHash, int pageNo) {
        if (!properties.ocrCacheEnabled() || documentHash == null || documentHash.isBlank()) {
            return null;
        }
        Path path = cachePath(documentHash, pageNo);
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private void writeCached(String documentHash, int pageNo, String text) {
        if (!properties.ocrCacheEnabled() || documentHash == null || documentHash.isBlank()) {
            return;
        }
        try {
            Path path = cachePath(documentHash, pageNo);
            Files.createDirectories(path.getParent());
            Files.writeString(path, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // OCR cache is a best-effort optimization.
        }
    }

    private Path cachePath(String documentHash, int pageNo) {
        return properties.ocrCacheDir().resolve(documentHash).resolve("page-" + pageNo + ".txt");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record OcrPageResult(int pageNo, String text, String errorMessage, boolean success) {
        private static OcrPageResult success(int pageNo, String text) {
            return new OcrPageResult(pageNo, text, "", true);
        }

        private static OcrPageResult failure(int pageNo, String errorMessage) {
            return new OcrPageResult(pageNo, "", errorMessage, false);
        }
    }
}

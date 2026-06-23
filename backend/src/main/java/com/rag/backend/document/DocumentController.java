package com.rag.backend.document;

import com.rag.backend.common.BizException;
import com.rag.backend.common.Result;
import com.rag.backend.document.model.CourseDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * B3: 文件上传
     * POST /api/documents/upload
     */
    @PostMapping("/upload")
    public Result<CourseDocument> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam("courseId") Long courseId) {
        CourseDocument document = documentService.upload(file, courseId);
        return Result.ok(document);
    }

    /**
     * B4: 文档列表
     * GET /api/documents?courseId=
     */
    @GetMapping
    public Result<List<CourseDocument>> list(@RequestParam("courseId") Long courseId) {
        List<CourseDocument> documents = documentService.listByCourse(courseId);
        return Result.ok(documents);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> viewFile(@PathVariable Long id) {
        CourseDocument document = documentService.getById(id);
        try {
            Path filePath = Path.of(document.getFilePath()).toAbsolutePath().normalize();
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                throw new BizException(404, "Uploaded file does not exist: " + id);
            }
            Resource resource = new UrlResource(filePath.toUri());
            MediaType mediaType = MediaTypeFactory.getMediaType(document.getFilename())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            String encodedFilename = URLEncoder.encode(document.getFilename(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename)
                    .body(resource);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "Failed to open uploaded file: " + e.getMessage());
        }
    }

    /**
     * B5: 文档删除
     * DELETE /api/documents/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }
}

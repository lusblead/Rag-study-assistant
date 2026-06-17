package com.rag.backend.controller;

import com.rag.backend.common.Result;
import com.rag.backend.entity.Document;
import com.rag.backend.service.DocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    public Result<Document> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("courseId") Long courseId) {
        Document document = documentService.upload(file, courseId);
        return Result.ok(document);
    }

    /**
     * B4: 文档列表
     * GET /api/documents?courseId=
     */
    @GetMapping
    public Result<List<Document>> list(@RequestParam("courseId") Long courseId) {
        List<Document> documents = documentService.listByCourse(courseId);
        return Result.ok(documents);
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

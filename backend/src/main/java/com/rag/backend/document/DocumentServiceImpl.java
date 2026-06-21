package com.rag.backend.document;

import com.rag.backend.agent.ingest.AgentDocumentCleanupService;
import com.rag.backend.common.BizException;
import com.rag.backend.course.CourseMapper;
import com.rag.backend.document.model.CourseDocument;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Service
public class DocumentServiceImpl implements DocumentService {
    private static final Set<String> SUPPORTED_FILE_TYPES = Set.of("txt", "md", "markdown", "doc", "docx", "pdf", "pptx");

    private final DocumentMapper documentMapper;
    private final CourseMapper courseMapper;
    private final AgentDocumentCleanupService cleanupService;

    @Value("${app.upload.dir:./uploads}")
    private String uploadBaseDir;

    private Path uploadBasePath;

    public DocumentServiceImpl(DocumentMapper documentMapper,
                               CourseMapper courseMapper,
                               AgentDocumentCleanupService cleanupService) {
        this.documentMapper = documentMapper;
        this.courseMapper = courseMapper;
        this.cleanupService = cleanupService;
    }

    @PostConstruct
    private void init() {
        this.uploadBasePath = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
    }

    @Override
    public CourseDocument upload(MultipartFile file, Long courseId) {
        if (courseMapper.selectById(courseId) == null) {
            throw new BizException(404, "Course does not exist: " + courseId);
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "Upload file must not be empty");
        }

        String originalFilename = file.getOriginalFilename();
        String fileType = getFileType(originalFilename);
        if (!SUPPORTED_FILE_TYPES.contains(fileType)) {
            throw new BizException(400, "Unsupported file type: " + fileType
                    + ". Supported types: TXT, Markdown, DOC, DOCX, PPTX, PDF.");
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String storedFilename = timestamp + "_" + sanitizeFilename(originalFilename);
        String relativePath = "documents/" + courseId + "/" + storedFilename;
        Path targetPath = uploadBasePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(uploadBasePath)) {
            throw new BizException(400, "Invalid file path");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save uploaded file", e);
        }

        CourseDocument document = new CourseDocument();
        document.setCourseId(courseId);
        document.setFilename(originalFilename);
        document.setFileType(fileType);
        document.setFilePath(targetPath.toAbsolutePath().toString());
        document.setParseStatus(CourseDocument.STATUS_UPLOADED);
        document.setChunkCount(0);

        documentMapper.insert(document);
        return document;
    }

    @Override
    public List<CourseDocument> listByCourse(Long courseId) {
        return documentMapper.selectListByCourseId(courseId);
    }

    @Override
    public CourseDocument getById(Long id) {
        CourseDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw new BizException(404, "Document does not exist: " + id);
        }
        return document;
    }

    @Override
    public void delete(Long id) {
        CourseDocument document = getById(id);

        cleanupService.cleanupDocument(id);

        try {
            Path filePath = Paths.get(document.getFilePath());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete uploaded file", e);
        }

        documentMapper.deleteById(id);
    }

    @Override
    public void updateParseStatus(Long id, String status, Integer chunkCount) {
        CourseDocument document = getById(id);
        document.setParseStatus(status);
        if (chunkCount != null) {
            document.setChunkCount(chunkCount);
        }
        documentMapper.updateParseStatus(document);
    }

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}

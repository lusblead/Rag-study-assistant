package com.rag.backend.document;

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

@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final CourseMapper courseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @Value("${app.upload.dir:./uploads}")
    private String uploadBaseDir;

    /** 解析后的绝对路径根目录，避免相对路径受 Tomcat 工作目录影响 */
    private Path uploadBasePath;

    public DocumentServiceImpl(DocumentMapper documentMapper, CourseMapper courseMapper,
                               KnowledgeChunkMapper knowledgeChunkMapper) {
        this.documentMapper = documentMapper;
        this.courseMapper = courseMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
    }

    @PostConstruct
    private void init() {
        this.uploadBasePath = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
    }

    @Override
    public CourseDocument upload(MultipartFile file, Long courseId) {
        // 1. 校验 course_id 是否存在
        if (courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("课程不存在: " + courseId);
        }

        // 2. 校验文件是否为空
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        // 3. 获取文件类型
        String originalFilename = file.getOriginalFilename();
        String fileType = getFileType(originalFilename);

        // 4. 生成唯一文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String storedFilename = timestamp + "_" + originalFilename;

        // 5. 构建存储路径（使用绝对路径，避免受 Tomcat 工作目录影响）
        String relativePath = "documents/" + courseId + "/" + storedFilename;
        Path targetPath = uploadBasePath.resolve(relativePath);

        try {
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        // 6. 写入 documents 表
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
            throw new BizException(404, "文档不存在: " + id);
        }
        return document;
    }

    @Override
    public void delete(Long id) {
        CourseDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw new BizException(404, "文档不存在: " + id);
        }

        // 1. 删除关联的知识片段（MySQL）
        //    TODO: 同步删除 Milvus 向量 —— 当前 Mock 模式下 VectorStoreService 为内存实现，
        //          后续接入真实 Milvus 后需在此处调用 VectorStoreService.deleteByDocumentId(documentId)
        knowledgeChunkMapper.deleteByDocumentId(id);

        // 2. 删除本地文件
        try {
            Path filePath = Paths.get(document.getFilePath());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            // 文件删除失败不影响数据库记录的删除
        }

        // 3. 删除 documents 记录
        documentMapper.deleteById(id);
    }

    @Override
    public void updateParseStatus(Long id, String status, Integer chunkCount) {
        CourseDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw new BizException(404, "文档不存在: " + id);
        }
        document.setParseStatus(status);
        if (chunkCount != null) {
            document.setChunkCount(chunkCount);
        }
        documentMapper.updateParseStatus(document);
    }

    // -- private helpers -------------------------------------------

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}

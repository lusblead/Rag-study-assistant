package com.rag.backend.service;

import com.rag.backend.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    /**
     * 上传文档文件
     * @param file     上传的文件
     * @param courseId 关联课程ID
     * @return 保存后的文档记录
     */
    Document upload(MultipartFile file, Long courseId);

    /**
     * 根据课程ID查询文档列表
     */
    List<Document> listByCourse(Long courseId);

    /**
     * 根据ID删除文档（同时删除本地文件）
     */
    void delete(Long id);

    /**
     * 更新文档解析状态（供同学C的Agent模块调用）
     * @param id         文档ID
     * @param status     新状态
     * @param chunkCount 切片数量
     */
    void updateParseStatus(Long id, String status, Integer chunkCount);
}

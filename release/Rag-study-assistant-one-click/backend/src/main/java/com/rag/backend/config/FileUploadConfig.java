package com.rag.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件上传配置 —— 读取 application.yml 中 app.upload 开头的配置项。
 */
@Configuration
@ConfigurationProperties(prefix = "app.upload")
public class FileUploadConfig {

    /** 上传文件存储根目录，默认 ./uploads */
    private String dir = "./uploads";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}

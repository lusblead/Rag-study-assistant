package com.rag.backend.ingestionlab.verify;

import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只做对象组合，不承载业务顺序。
 * IndexVerifier 保持纯 Java，便于组件测试；Spring 配置负责把真实端口注入进去。
 */
@Configuration
public class IngestionVerificationConfiguration {

    @Bean
    public IndexVerifier indexVerifier(
            ChunkInventory chunks,
            ConsistentVectorStore vectors) {
        return new IndexVerifier(chunks, vectors);
    }
}
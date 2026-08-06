package com.rag.backend.agent.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@ConditionalOnProperty(name = "agent.mock", havingValue = "true", matchIfMissing = true)
@Component
// 为 mock 模式提供可预测的向量生成实现。
public class MockEmbeddingClient implements EmbeddingClient {
    private final int dimension;

    /** 单元测试兼容构造器；生产 Mock Bean 使用配置维度。 */
    public MockEmbeddingClient() {
        this(128);
    }

    @Autowired
    public MockEmbeddingClient(
            @Value("${milvus.embedding-dimension:1024}") int dimension) {
        this.dimension = dimension;
    }

    @Override
    public List<Double> embed(String text){
        List<Double> vector = new ArrayList<>();
        int hash=text==null?0:text.hashCode();
        for(int i=0;i<dimension;i++){
            vector.add(((hash^(i*31))%1000)/1000.0);
        }
        return vector;
    }
}

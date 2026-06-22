package com.rag.backend.agent.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@ConditionalOnProperty(name = "agent.mock", havingValue = "true", matchIfMissing = true)
@Component
//模拟embedding逻辑
// 为 mock 模式提供可预测的向量生成实现。
public class MockEmbeddingClient implements EmbeddingClient {
    @Override
    public List<Double> embed(String text){
        List<Double> vector = new ArrayList<>();
        int hash=text==null?0:text.hashCode();
        for(int i=0;i<128;i++){
            vector.add(((hash^(i*31))%1000)/1000.0);
        }
        return vector;
    }
}

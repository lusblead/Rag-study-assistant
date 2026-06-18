package com.rag.backend.agent.embedding;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Primary
@Component
//模拟embedding逻辑
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

package com.rag.backend.agent.prompt;

import com.rag.backend.agent.model.RagPromptContext;
import org.springframework.stereotype.Component;

@Component
public class RagPromptTemplate implements PromptTemplate<RagPromptContext> {
    @Override
    public String render(RagPromptContext context){
        return """
                你是课程学习助手，请严格基于课程资料回答问题。
                如果课程资料中没有足够依据，请回答：当前知识库中没有找到足够依据。

                【课程资料】
                %s

                【用户问题】
                %s
                """.formatted(context.referencesText(), context.question());
    }
}

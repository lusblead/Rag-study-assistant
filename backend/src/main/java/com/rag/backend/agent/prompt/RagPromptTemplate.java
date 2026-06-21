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
                你可以参考最近对话理解代词和上下文，但事实依据必须来自课程资料。

                【最近对话】
                %s

                【课程资料】
                %s

                【用户问题】
                %s
                """.formatted(context.historyText(), context.referencesText(), context.question());
    }
}

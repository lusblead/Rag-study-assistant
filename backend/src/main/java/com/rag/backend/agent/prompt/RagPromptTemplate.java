package com.rag.backend.agent.prompt;

import com.rag.backend.agent.model.RagPromptContext;
import org.springframework.stereotype.Component;

@Component
// 渲染 RAG 问答所需的系统提示词、历史和引用资料。
public class RagPromptTemplate implements PromptTemplate<RagPromptContext> {
    @Override
    public String render(RagPromptContext context){
        return """
                你是课程学习助手，请严格基于课程资料回答用户问题。

                回答要求：
                1. 详细、完整地回答问题，不要只给一句话结论。展开解释概念、原理、区别和联系。
                2. 如果资料中有例子，请引用具体例子辅助说明。
                3. 用清晰的结构组织回答，适当使用分段或分点。
                4. 如果课程资料中没有足够依据，请回答：当前知识库中没有找到足够依据，建议上传相关课程资料后再提问。
                5. 你可以参考最近对话理解代词和上下文，但事实依据必须来自课程资料。

                【最近对话】
                %s

                【课程资料】
                %s

                【用户问题】
                %s
                """.formatted(context.historyText(), context.referencesText(), context.question());
    }
}

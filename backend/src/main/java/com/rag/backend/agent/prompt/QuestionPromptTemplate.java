package com.rag.backend.agent.prompt;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QuestionPromptTemplate {
    public String render(String requirement, List<RetrievedChunk> chunks) {
        String references = chunks.stream()
                .map(chunk -> "片段ID：" + chunk.chunkId() + "\n" + chunk.content())
                .collect(Collectors.joining("\n\n"));

        return """
                你是课程出题助手，请严格基于课程资料出题。

                【出题要求】
                %s

                【课程资料】
                %s

                请输出 JSON 数组，每个元素包含：
                type, stem, options, answer, explanation, difficulty, knowledgePoint, sourceChunkId。

                题型 type 只能是：single_choice, multi_choice, true_false, short_answer。
                难度 difficulty 只能是：easy, medium, hard。
                options 必须是 JSON 数组字符串；简答题可以为 null。
                """.formatted(requirement, references);
    }
}
package com.rag.backend.agent.prompt;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
// 渲染 AI 出题所需的课程资料和输出格式约束。
public class QuestionPromptTemplate {
    public String render(String requirement, List<RetrievedChunk> chunks) {
        String references = chunks.stream()
                .map(chunk -> """
                        [chunkId=%s]
                        %s
                        """.formatted(chunk.chunkId(), chunk.content()))
                .collect(Collectors.joining("\n"));

        return """
                你是课程出题助手，请严格基于提供的课程资料生成题目，不要编造资料之外的事实。

                【出题要求】
                %s

                【课程资料】
                %s

                请只返回一个合法 JSON 数组，不要使用 Markdown 代码块，不要输出解释性文字。
                数组中的每个元素必须是对象，并且包含以下字段：
                - type：题型，只能是 "single_choice"、"multi_choice"、"true_false"、"short_answer"
                - stem：题干
                - options：选项数组；简答题填写 null
                - answer：标准答案；单选题用 "A"，多选题用 "AB"，判断题用 "正确" 或 "错误"
                - explanation：解析，必须基于课程资料
                - difficulty：难度，只能是 "easy"、"medium"、"hard"
                - knowledgePoint：简短知识点名称
                - sourceChunkId：课程资料中的数字 chunkId

                选择题选项必须带有清晰的 A/B/C/D 标签，例如：
                ["A. ...", "B. ...", "C. ...", "D. ..."]

                除非出题要求明确指定其他语言，题干、选项、答案、解析和知识点都使用中文。
                """.formatted(requirement, references);
    }
}

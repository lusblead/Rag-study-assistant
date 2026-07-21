package com.rag.backend.agent.prompt;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QuestionPromptTemplate {
    public String render(String requirement, List<RetrievedChunk> chunks) {
        String references = chunks.stream()
                .map(chunk -> """
                        [chunkId=%s]
                        %s
                        """.formatted(chunk.chunkId(), chunk.content()))
                .collect(Collectors.joining("\n"));

        return """
                You are a course question-generation assistant.
                Generate questions strictly from the provided course material.

                User requirement:
                %s

                Course material:
                %s

                Return ONLY a valid JSON array. Do not wrap it in markdown fences.
                Each array item must be an object with exactly these fields:
                - type: one of "single_choice", "multi_choice", "true_false", "short_answer"
                - stem: question text
                - options: an array of option strings, or null for short_answer
                - answer: standard answer. Use "A" for single choice, "AB" for multi choice, "正确"/"错误" for true_false
                - explanation: concise explanation grounded in the material
                - difficulty: one of "easy", "medium", "hard"
                - knowledgePoint: short knowledge point name
                - sourceChunkId: numeric chunkId from the course material

                For choice questions, options must use visible labels like:
                ["A. ...", "B. ...", "C. ...", "D. ..."]
                Use Chinese for stem, options, answer, explanation, and knowledgePoint unless the user requirement clearly asks otherwise.
                """.formatted(requirement, references);
    }
}

package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FixedWindowTextChunker implements TextChunker {
    private final TextCleaner textCleaner;

    public FixedWindowTextChunker(final TextCleaner textCleaner) {
        this.textCleaner = textCleaner;
    }

    private int estimateTokenCount(String text){
        return Math.max(1,text.length());
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument document,int chunkSize,int overlap){
        String content = textCleaner.clean(document.content());
        List<TextChunk> chunks = new ArrayList<>();

        int start = 0;
        int index = 0;

        while(start < content.length()){
            int end=Math.min(start + chunkSize, content.length());
            String piece=content.substring(start, end).trim();

            if(!piece.isBlank()){
                chunks.add(new TextChunk(
                        index++,
                        document.title(),
                        piece,
                        null,
                        estimateTokenCount(piece)
                ));
            }

            if(end==content.length()){
                break;
            }
            start = Math.max(0,end-overlap);
        }
        return chunks;
    }
}

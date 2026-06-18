package com.rag.backend.agent.chunk;

import org.springframework.stereotype.Component;

@Component
public class TextCleaner {
    public String clean(String raw){
        if(raw==null){
            return "";
        }
        String normalized = raw.replace("\r\n","\n")
                .replace('\r','\n');
        return normalized.replaceAll("\n{3,}","\n\n").trim();

    }
}

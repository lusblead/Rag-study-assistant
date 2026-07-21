package com.rag.backend.agent.parse.pdf;

import java.util.Collection;
import java.util.List;

public interface TextCleaner {
    List<String> detectRepeatedHeadersAndFooters(Collection<String> pageTexts);

    String clean(String raw, Collection<String> repeatedLines);
}

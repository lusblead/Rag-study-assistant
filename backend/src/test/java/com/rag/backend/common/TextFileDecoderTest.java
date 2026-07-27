package com.rag.backend.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFileDecoderTest {

    @Test
    void decodesUtf8Text() {
        String text = "中文 UTF-8 内容";

        assertEquals(text, TextFileDecoder.decode(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesGb18030Text() {
        String text = "中文 GB18030 内容";

        assertEquals(text, TextFileDecoder.decode(text.getBytes(Charset.forName("GB18030"))));
    }

    @Test
    void stripsUtf8Bom() {
        byte[] content = "带 BOM 的内容".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[content.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(content, 0, bytes, 3, content.length);

        assertEquals("带 BOM 的内容", TextFileDecoder.decode(bytes));
    }
}

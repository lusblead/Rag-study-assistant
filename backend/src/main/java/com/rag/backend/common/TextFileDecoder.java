package com.rag.backend.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TextFileDecoder {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private TextFileDecoder() {
    }

    public static String readString(Path filePath) throws IOException {
        return decode(Files.readAllBytes(filePath));
    }

    public static String decode(byte[] bytes) {
        if (hasPrefix(bytes, 0xEF, 0xBB, 0xBF)) {
            return decodeUnchecked(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (hasPrefix(bytes, 0xFE, 0xFF)) {
            return decodeUnchecked(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (hasPrefix(bytes, 0xFF, 0xFE)) {
            return decodeUnchecked(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }

        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return decodeUnchecked(bytes, 0, bytes.length, GB18030);
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String decodeUnchecked(byte[] bytes, int offset, int length, Charset charset) {
        return charset.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
    }

    private static boolean hasPrefix(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

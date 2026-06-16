package com.yulong.chatagent.rag.vector.milvus;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class MilvusBm25Encoding {

    private MilvusBm25Encoding() {
    }

    static void requireUtf8DefaultCharset() {
        requireUtf8DefaultCharset(Charset.defaultCharset());
    }

    static void requireUtf8DefaultCharset(Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            throw new IllegalStateException(
                    "Milvus BM25 search requires a UTF-8 JVM default charset because the Milvus Java SDK "
                            + "encodes EmbeddedText with the default charset; start the JVM with -Dfile.encoding=UTF-8");
        }
    }
}

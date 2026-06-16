package com.yulong.chatagent.rag.vector.milvus;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MilvusBm25EncodingTest {

    @Test
    void acceptsUtf8DefaultCharset() {
        assertThatNoException()
                .isThrownBy(() -> MilvusBm25Encoding.requireUtf8DefaultCharset(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonUtf8DefaultCharsetBeforeMilvusCanRetryInvalidPlaceholderBytes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> MilvusBm25Encoding.requireUtf8DefaultCharset(Charset.forName("GBK")))
                .withMessageContaining("-Dfile.encoding=UTF-8");
    }
}

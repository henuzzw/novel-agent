package com.novelagent.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class Sha256Test {
    @Test
    void matchesKnownEmptyAndAsciiVectors() {
        assertThat(Sha256.ofUtf8(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.ofUtf8("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void preservesUtf8WhitespaceAndExactJsonBytes() throws Exception {
        String text = "原文\r\n  ";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String original = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(Sha256.ofUtf8(text)).isEqualTo(original).isEqualTo(Sha256.ofBytes(bytes));
        assertThat(Sha256.ofUtf8(text)).isNotEqualTo(Sha256.ofUtf8(text.trim()));
        assertThat(Sha256.ofUtf8(text)).isNotEqualTo(Sha256.ofUtf8(text.replace("\r\n", "\n")));
        assertThat(Sha256.ofUtf8("{\"a\":1,\"b\":2}"))
                .isNotEqualTo(Sha256.ofUtf8("{\"b\":2,\"a\":1}"));
    }

    @Test
    void doesNotShareMutableDigestStateAcrossConcurrentCalls() {
        var values = IntStream.range(0, 1000).parallel()
                .mapToObj(index -> Sha256.ofUtf8("abc")).distinct().toList();
        assertThat(values).containsExactly(Sha256.ofUtf8("abc"));
    }

    @Test
    void leavesNullPolicyToTheCaller() {
        assertThatThrownBy(() -> Sha256.ofUtf8(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Sha256.ofBytes(null)).isInstanceOf(NullPointerException.class);
    }
}

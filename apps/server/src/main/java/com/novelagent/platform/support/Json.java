package com.novelagent.platform.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.function.BiFunction;

/**
 * Wraps Jackson calls so callers state only the operation and the business error message.
 * Defaults to IllegalStateException; pass a factory such as IllegalArgumentException::new when invalid input is the cause.
 */
public final class Json {
    private Json() {
    }

    @FunctionalInterface
    public interface Call<T> {
        T run() throws JsonProcessingException;
    }

    public static <T> T call(Call<T> call, String message) {
        return call(call, message, IllegalStateException::new);
    }

    /** SHA-256 of the value's JSON form; equal values with equal serialization share a fingerprint. */
    public static String sha256(ObjectMapper mapper, Object value, String message) {
        return Sha256.ofUtf8(call(() -> mapper.writeValueAsString(value), message));
    }

    public static <T> T call(Call<T> call, String message,
            BiFunction<String, Throwable, ? extends RuntimeException> failure) {
        try {
            return call.run();
        } catch (JsonProcessingException exception) {
            throw failure.apply(message, exception);
        }
    }
}

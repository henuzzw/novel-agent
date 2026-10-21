package com.novelagent.platform.api;

import java.net.URI;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/**
 * Shared HTTP conventions: row versions travel as ETag / If-Match, a missing "latest" resource is 204,
 * and per-run diagnostics are never cached.
 */
public final class ApiResponses {
    private ApiResponses() {
    }

    /** Reads the expected row version from an If-Match header; surrounding quotes and spaces are ignored. */
    public static long expectedVersion(String ifMatch) {
        return Long.parseLong(ifMatch.replace("\"", "").trim());
    }

    public static <T> ResponseEntity<T> versioned(T body, long version) {
        return ResponseEntity.ok().eTag(Long.toString(version)).body(body);
    }

    public static <T> ResponseEntity<T> created(String location, T body, long version) {
        return ResponseEntity.created(URI.create(location)).eTag(Long.toString(version)).body(body);
    }

    public static <T> ResponseEntity<T> versionedOrNoContent(Optional<T> body, ToLongFunction<T> version) {
        return body.map(value -> versioned(value, version.applyAsLong(value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public static <T> ResponseEntity<T> okOrNoContent(Optional<T> body) {
        return body.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    public static <T> ResponseEntity<T> noStoreOrNotFound(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

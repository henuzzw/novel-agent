package com.novelagent.memory.infrastructure;

import com.novelagent.platform.support.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Rebuildable search projection. A locked document is replaced atomically with its chunks. */
@Component
public class SemanticChunkStore {
    private final JdbcTemplate jdbc;

    public SemanticChunkStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void synchronize(UUID documentId) {
        var sources = jdbc.query("""
                SELECT content, chunked_content_hash FROM semantic_document WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Source(rs.getString("content"), rs.getString("chunked_content_hash")), documentId);
        if (sources.isEmpty()) return;
        Source source = sources.getFirst();
        String hash = Sha256.ofUtf8(source.content());
        if (hash.equals(source.hash())) return;
        jdbc.update("DELETE FROM semantic_document_chunk WHERE document_id = ?", documentId);
        var chunks = MemoryTextChunks.split(source.content());
        jdbc.batchUpdate("""
                INSERT INTO semantic_document_chunk(id, document_id, ordinal, start_offset, end_offset, content)
                VALUES (?, ?, ?, ?, ?, ?)
                """, chunks, 100, (ps, chunk) -> {
            ps.setObject(1, UUID.nameUUIDFromBytes((documentId + ":" + hash + ":" + chunk.ordinal())
                    .getBytes(StandardCharsets.UTF_8)));
            ps.setObject(2, documentId);
            ps.setInt(3, chunk.ordinal());
            ps.setInt(4, chunk.start());
            ps.setInt(5, chunk.end());
            ps.setString(6, chunk.content());
        });
        jdbc.update("UPDATE semantic_document SET chunked_content_hash = ? WHERE id = ?", hash, documentId);
    }

    private record Source(String content, String hash) { }
}

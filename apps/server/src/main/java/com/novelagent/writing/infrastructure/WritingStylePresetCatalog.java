package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WritingStylePresetCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WritingStylePresetCatalog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<WritingStyleProfile> active() {
        return jdbc.query("SELECT profile FROM writing_style_preset WHERE active ORDER BY sort_order, preset_id",
                (rs, n) -> read(rs.getString("profile")));
    }

    public WritingStyleProfile resolve(WritingStyleProfile profile) {
        if (profile == null || profile.craft() != null) return profile;
        if (profile.basePresetId() != null) {
            var base = jdbc.query("SELECT profile FROM writing_style_preset WHERE preset_id = ? AND preset_version = ?",
                    (rs, n) -> read(rs.getString("profile")), profile.basePresetId(), profile.basePresetVersion())
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("未知基础风格版本"));
            return profile.withCraft(base.basePresetId(), base.basePresetVersion(), base.craft());
        }
        // A complete legacy match is provenance; a display name alone is not.
        var matches = jdbc.query("""
                SELECT profile FROM writing_style_preset
                WHERE legacy_profile = CAST(? AS jsonb) - 'basePresetId' - 'basePresetVersion' - 'craft'
                ORDER BY preset_version, preset_id
                """, (rs, n) -> read(rs.getString("profile")), write(profile));
        return matches.isEmpty() ? profile : matches.getFirst();
    }

    private WritingStyleProfile read(String json) {
        try {
            return mapper.readValue(json, WritingStyleProfile.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库风格档案无效", exception);
        }
    }

    private String write(WritingStyleProfile profile) {
        try {
            return mapper.writeValueAsString(profile);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码风格档案", exception);
        }
    }
}

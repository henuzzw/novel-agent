package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风格预设目录。
 *
 * <p>从数据库读取完整版本化风格，不提供写死预设回退。旧快照仅按明确基础版本或全字段匹配补技法，不能按同名猜继承；已保存快照不随目录静默覆盖。</p>
 */
@Repository
public class WritingStylePresetCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WritingStylePresetCatalog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 按启用及排序条件读取完整数据库风格档案，不修改项目已保存快照。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<WritingStyleProfile> active() {
        return jdbc.query("SELECT profile FROM writing_style_preset WHERE active ORDER BY sort_order, preset_id",
                (rs, n) -> read(rs.getString("profile")));
    }

    /**
     * 保留已有完整技法；否则按明确基础预设 ID 与版本补齐。旧档案仅在完整 legacy_profile 匹配时继承，单凭风格名称不匹配；未知基础版本拒绝返回伪造风格。
     *
     * @param profile 候选或已选完整风格档案，包含版本与技法快照。
     */
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

    /**
     * 将数据库 profile JSON 反序列化为完整风格档案；损坏数据抛出存储错误，不用简化预设掩盖。
     *
     * @param json 存储中的 JSON 文本，损坏时拒绝恢复为有效对象。
     */
    private WritingStyleProfile read(String json) {
        try {
            return mapper.readValue(json, WritingStyleProfile.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库风格档案无效", exception);
        }
    }

    /**
     * 将风格档案编码为 JSON，供完整旧档案匹配使用；编码失败拒绝查询，不只比较显示名称。
     *
     * @param profile 候选或已选完整风格档案，包含版本与技法快照。
     */
    private String write(WritingStyleProfile profile) {
        try {
            return mapper.writeValueAsString(profile);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码风格档案", exception);
        }
    }
}

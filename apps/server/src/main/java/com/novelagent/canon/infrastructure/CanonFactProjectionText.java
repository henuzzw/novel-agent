package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

final class CanonFactProjectionText {
    private CanonFactProjectionText() {
    }

    static String factSentence(FactProposal proposal) {
        return normalizedText(proposal.subject()) + " " + normalizedText(proposal.predicate())
                + " " + normalizedText(proposal.object());
    }

    static String normalizedText(String value) {
        if (isBlank(value)) {
            return "未命名";
        }
        return value.trim();
    }

    static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 读取结构化载荷字段；载荷缺失或字段为空白时使用回退值。 */
    static String payloadText(TypedFactPayload payload, Function<TypedFactPayload, String> field, String fallback) {
        String value = payload == null ? null : field.apply(payload);
        return isBlank(value) ? fallback : value;
    }

    /** 将指定提交在投影表中仍然有效的行标记为在替换版本失效。 */
    static void retireCommit(JdbcTemplate jdbc, String table, UUID commitId, long replacementVersion) {
        jdbc.update("UPDATE " + table + " SET canon_version_to = ? "
                + "WHERE source_commit_id = ? AND canon_version_to IS NULL", replacementVersion, commitId);
    }
}

package com.novelagent.writing.api;

import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityReviewVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 四维质量报告、正文来源与当前有效性。
 *
 * <p>将领域对象转换为接口响应快照，明确保留四维质量报告、正文来源与当前有效性。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record QualityReviewResponse(UUID id, UUID projectId, int chapterNumber, int versionNumber,
        UUID sourceManuscriptId, long sourceManuscriptRowVersion, String generatorType, boolean current,
        QualityReviewContent content, Instant createdAt) {
    /**
     * 将领域版本映射为四维质量报告、正文来源与当前有效性。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param report 待保存或读取的解析、审阅报告。
     * @param current 操作开始时读取的当前记录或版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static QualityReviewResponse from(QualityReviewVersion report, boolean current) {
        return new QualityReviewResponse(report.getId(), report.getProjectId(), report.getChapterNumber(), report.getVersionNumber(),
                report.getSourceManuscriptId(), report.getSourceManuscriptRowVersion(), report.getGeneratorType(), current,
                report.getContent(), report.getCreatedAt());
    }
}

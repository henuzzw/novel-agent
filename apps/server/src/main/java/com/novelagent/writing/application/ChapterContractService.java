package com.novelagent.writing.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.api.ChapterContractVersionSummaryResponse;
import com.novelagent.writing.infrastructure.ChapterContractReviewVersionRepository;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 章节合同。
 *
 * <p>只读保留历史合同与审阅；所有写入接口返回410，不再参与正文或自动创作。</p>
 */
@Service
public class ChapterContractService {
    private final WritingContextService contexts;
    private final ChapterContractVersionRepository contracts;
    private final ChapterContractReviewVersionRepository contractReviews;
    private final CharacterNameService characterNames;

    public ChapterContractService(WritingContextService contexts, ChapterContractVersionRepository contracts,
            ChapterContractReviewVersionRepository contractReviews, CharacterNameService characterNames) {
        this.contexts = contexts;
        this.contracts = contracts;
        this.contractReviews = contractReviews;
        this.characterNames = characterNames;
    }

    /**
     * 读取本章最新保存合同，可能仍是草稿；不代表已通过审阅或作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<ChapterContractResponse> latestContract(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterContractResponse::from);
    }

    /**
     * 列出退役流程留下的合同摘要，不再作为新正文生成基准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<ChapterContractVersionSummaryResponse> contractVersions(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contracts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .stream().map(value -> ChapterContractVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().chapterTitle()))).toList();
    }

    /**
     * 按项目、章号和版本 ID 读取指定合同，避免把其他章节版本用作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public ChapterContractResponse contractVersion(UUID projectId, int chapterNumber, UUID id) {
        contexts.requireOwnedProject(projectId);
        return ChapterContractResponse.from(contracts.findByIdAndProjectIdAndChapterNumber(id, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节合同版本", id)));
    }

    /**
     * 读取本章历史合同审阅报告，不再执行合同批准流程。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<ChapterContractReviewResponse> latestContractReview(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contractReviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterContractReviewResponse::from);
    }

    public void rejectRetiredWrite(UUID projectId) {
        contexts.requireOwnedProject(projectId);
        throw new ResponseStatusException(HttpStatus.GONE,
                "合同与合同审阅阶段已移除，请发布大纲后直接生成正文");
    }
}

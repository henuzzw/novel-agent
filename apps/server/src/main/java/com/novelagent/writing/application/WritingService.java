package com.novelagent.writing.application;

import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.api.ChapterContractVersionSummaryResponse;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.ManuscriptVersionSummaryResponse;
import com.novelagent.writing.api.ReturnReviewRequest;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 写作业务门面。
 *
 * <p>将章节入口委托给合同、正文和审稿三个专责服务。保留调用接口，事务、版本校验和模型编排由被委托服务负责，不在门面重复实现。</p>
 */
@Service
public class WritingService {
    private final ChapterContractService contracts;
    private final ManuscriptService manuscripts;
    private final ChapterReviewService reviews;

    public WritingService(ChapterContractService contracts, ManuscriptService manuscripts, ChapterReviewService reviews) {
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.reviews = reviews;
    }

    /**
     * 读取本章最新保存合同，可能仍是草稿；不代表已通过审阅或作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<ChapterContractResponse> latestContract(UUID projectId, int chapterNumber) {
        return contracts.latestContract(projectId, chapterNumber);
    }

    /**
     * 列出本章历史合同版本摘要，供作者显式选择生成基准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<ChapterContractVersionSummaryResponse> contractVersions(UUID projectId, int chapterNumber) {
        return contracts.contractVersions(projectId, chapterNumber);
    }

    /**
     * 按项目、章号和版本 ID 读取指定合同，避免把其他章节版本用作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public ChapterContractResponse contractVersion(UUID projectId, int chapterNumber, UUID id) {
        return contracts.contractVersion(projectId, chapterNumber, id);
    }

    /**
     * 根据当前发布规划及记忆生成或有限调整合同草稿，模型返回后核对主要来源；不自动审阅或确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ChapterContractResponse generateContract(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return contracts.generateContract(projectId, chapterNumber, request);
    }

    /**
     * 读取本章最新合同审阅报告；是否可用于批准合同还需核对来源版本和状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<ChapterContractReviewResponse> latestContractReview(UUID projectId, int chapterNumber) {
        return contracts.latestContractReview(projectId, chapterNumber);
    }

    /**
     * 独立审阅当前大纲下的合同草稿，保存与合同 ID 及行版本绑定的报告，不直接批准合同。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ChapterContractReviewResponse generateContractReview(UUID projectId, int chapterNumber,
            GenerateWritingRequest request) {
        return contracts.generateContractReview(projectId, chapterNumber, request);
    }

    /**
     * 保存作者对审阅问题的处理并确认报告；来源合同或最新报告变化时拒绝使用旧结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    public ChapterContractReviewResponse approveContractReview(UUID projectId, UUID id, long expected,
            ChapterContractReviewContent content) {
        return contracts.approveContractReview(projectId, id, expected, content);
    }

    /**
     * 按合同当前状态和行版本保存作者编辑；修改后的合同不能沿用不匹配的旧审阅批准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    public ChapterContractResponse updateContract(UUID projectId, UUID id, long expected, ChapterContractContent content) {
        return contracts.updateContract(projectId, id, expected, content);
    }

    /**
     * 核对最新合同、最新已确认审阅及来源行版本后确认合同，为正式正文提供依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    public ChapterContractResponse approveContract(UUID projectId, UUID id, long expected) {
        return contracts.approveContract(projectId, id, expected);
    }

    /**
     * 读取本章最新保存正文并按当前人物名称渲染，最新草稿不等于作者接受或有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<ManuscriptResponse> latestManuscript(UUID projectId, int chapterNumber) {
        return manuscripts.latestManuscript(projectId, chapterNumber);
    }

    /**
     * 列出本章正文版本摘要，供作者选择查看或修订基准，不切换有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(UUID projectId, int chapterNumber) {
        return manuscripts.manuscriptVersions(projectId, chapterNumber);
    }

    /**
     * 按项目、章号及版本 ID 读取指定正文，保留其合同来源、基准稿与状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public ManuscriptResponse manuscriptVersion(UUID projectId, int chapterNumber, UUID id) {
        return manuscripts.manuscriptVersion(projectId, chapterNumber, id);
    }

    /**
     * 依据本章已确认且属于当前大纲的合同生成一份新正文草稿，复核上游来源后保存，不直接确认或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ManuscriptResponse generateManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return manuscripts.generateManuscript(projectId, chapterNumber, request);
    }

    /**
     * 基于审稿所引用的正文及作者选定建议生成返工草稿，保留原确认稿；修改范围服从作者授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param reviewId 作为修订或提交依据的审稿版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ManuscriptResponse returnReviewToWriting(UUID projectId, int chapterNumber, UUID reviewId,
            long expectedVersion, ReturnReviewRequest request) {
        return reviews.returnReviewToWriting(projectId, chapterNumber, reviewId, expectedVersion, request);
    }

    /**
     * 按行版本保存作者对可编辑正文的修改，保持该版本的业务状态约束。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    public ManuscriptResponse updateManuscript(UUID projectId, UUID id, long expected, ManuscriptContent content) {
        return manuscripts.updateManuscript(projectId, id, expected, content);
    }

    /**
     * 将当前作者已确认正文复制为人工修订草稿，原稿保留且新稿记录基准来源；不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param sourceId 本次处理的基准记录 ID，不隐式改用最新版本。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    public ManuscriptResponse createManuscriptRevision(UUID projectId, int chapterNumber, UUID sourceId, long expected) {
        return manuscripts.createManuscriptRevision(projectId, chapterNumber, sourceId, expected);
    }

    /**
     * 由作者显式确认正文版本；这里只改变正文接受状态，不抽取事实或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    public ManuscriptResponse acceptManuscript(UUID projectId, UUID id, long expected) {
        return manuscripts.acceptManuscript(projectId, id, expected);
    }

    /**
     * 读取本章最新审稿结果，实际正史提交仍要求报告与确认正文匹配并经作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<ChapterReviewResponse> latestReview(UUID projectId, int chapterNumber) {
        return reviews.latestReview(projectId, chapterNumber);
    }

    /**
     * 审查作者确认正文的一致性并提取候选事实，报告保存后由作者处理问题及事实决定，不自动提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ChapterReviewResponse generateReview(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return reviews.generateReview(projectId, chapterNumber, request);
    }

    /**
     * 按审稿行版本保存问题处理与候选事实决定，不在此物化事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    public ChapterReviewResponse updateReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        return reviews.updateReview(projectId, id, expected, content);
    }

    /**
     * 确认已处理的审稿结果，阻断问题及来源约束由审稿领域规则检查；确认报告不等于正史提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    public ChapterReviewResponse approveReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        return reviews.approveReview(projectId, id, expected, content);
    }

    /**
     * 以当前人物显示名称导出选定正文的 Markdown 内容，不修改正文或业务状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public String exportManuscript(UUID projectId, UUID id) {
        return manuscripts.exportManuscript(projectId, id);
    }
}

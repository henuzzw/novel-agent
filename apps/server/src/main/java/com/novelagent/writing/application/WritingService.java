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

    public Optional<ChapterContractResponse> latestContract(UUID projectId, int chapterNumber) {
        return contracts.latestContract(projectId, chapterNumber);
    }

    public List<ChapterContractVersionSummaryResponse> contractVersions(UUID projectId, int chapterNumber) {
        return contracts.contractVersions(projectId, chapterNumber);
    }

    public ChapterContractResponse contractVersion(UUID projectId, int chapterNumber, UUID id) {
        return contracts.contractVersion(projectId, chapterNumber, id);
    }

    public ChapterContractResponse generateContract(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return contracts.generateContract(projectId, chapterNumber, request);
    }

    public Optional<ChapterContractReviewResponse> latestContractReview(UUID projectId, int chapterNumber) {
        return contracts.latestContractReview(projectId, chapterNumber);
    }

    public ChapterContractReviewResponse generateContractReview(UUID projectId, int chapterNumber,
            GenerateWritingRequest request) {
        return contracts.generateContractReview(projectId, chapterNumber, request);
    }

    public ChapterContractReviewResponse approveContractReview(UUID projectId, UUID id, long expected,
            ChapterContractReviewContent content) {
        return contracts.approveContractReview(projectId, id, expected, content);
    }

    public ChapterContractResponse updateContract(UUID projectId, UUID id, long expected, ChapterContractContent content) {
        return contracts.updateContract(projectId, id, expected, content);
    }

    public ChapterContractResponse approveContract(UUID projectId, UUID id, long expected) {
        return contracts.approveContract(projectId, id, expected);
    }

    public Optional<ManuscriptResponse> latestManuscript(UUID projectId, int chapterNumber) {
        return manuscripts.latestManuscript(projectId, chapterNumber);
    }

    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(UUID projectId, int chapterNumber) {
        return manuscripts.manuscriptVersions(projectId, chapterNumber);
    }

    public ManuscriptResponse manuscriptVersion(UUID projectId, int chapterNumber, UUID id) {
        return manuscripts.manuscriptVersion(projectId, chapterNumber, id);
    }

    public ManuscriptResponse generateManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return manuscripts.generateManuscript(projectId, chapterNumber, request);
    }

    public ManuscriptResponse returnReviewToWriting(UUID projectId, int chapterNumber, UUID reviewId,
            long expectedVersion, ReturnReviewRequest request) {
        return reviews.returnReviewToWriting(projectId, chapterNumber, reviewId, expectedVersion, request);
    }

    public ManuscriptResponse updateManuscript(UUID projectId, UUID id, long expected, ManuscriptContent content) {
        return manuscripts.updateManuscript(projectId, id, expected, content);
    }

    public ManuscriptResponse createManuscriptRevision(UUID projectId, int chapterNumber, UUID sourceId, long expected) {
        return manuscripts.createManuscriptRevision(projectId, chapterNumber, sourceId, expected);
    }

    public ManuscriptResponse acceptManuscript(UUID projectId, UUID id, long expected) {
        return manuscripts.acceptManuscript(projectId, id, expected);
    }

    public Optional<ChapterReviewResponse> latestReview(UUID projectId, int chapterNumber) {
        return reviews.latestReview(projectId, chapterNumber);
    }

    public ChapterReviewResponse generateReview(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return reviews.generateReview(projectId, chapterNumber, request);
    }

    public ChapterReviewResponse updateReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        return reviews.updateReview(projectId, id, expected, content);
    }

    public ChapterReviewResponse approveReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        return reviews.approveReview(projectId, id, expected, content);
    }

    public String exportManuscript(UUID projectId, UUID id) {
        return manuscripts.exportManuscript(projectId, id);
    }
}

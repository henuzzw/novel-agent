import type { OpeningReport, OpeningView } from '@/api/firstThreeChapters'
export function openingView(): OpeningView {
  return { available: true, source: { projectId: 'project-1', outlineId: 'outline-1', outlineRowVersion: 4,
    bibleId: 'bible-1', bibleRowVersion: 3, canonVersion: 2, strategy: 'STANDARD', outlineContext: 'outline',
    bibleContext: 'bible', styleContext: 'style', profileContext: 'profile', fingerprint: 'hash-1', unavailableReasons: [],
    chapters: [1, 2, 3].map(n => ({ chapterNumber: n, manuscriptId: `m${n}`, versionNumber: 2, rowVersion: 4,
      status: 'DRAFT', title: `章名${n}`, body: `第${n}章开头。\n${'完整正文。'.repeat(120)}\n第${n}章结尾。`,
      contractId: `c${n}`, contractVersionNumber: 1, contractRowVersion: 2, contractStatus: 'APPROVED',
      contract: { chapterTitle: `章名${n}`, pov: '主角', objective: '调查', storyTime: '第一天', locations: ['学校'],
        requiredBeats: ['行动', '后果'], requiredReveals: ['线索'], forbiddenFacts: ['未来秘密'], expectedExitState: '有进展',
        foreshadowActions: ['铺垫'], hook: '长线目标', suggestedMinWords: 1000, suggestedMaxWords: 2000 },
      versions: [{ id: `m${n}`, versionNumber: 2, rowVersion: 4, status: 'DRAFT' }, { id: `old${n}`, versionNumber: 1, rowVersion: 1, status: 'AUTHOR_ACCEPTED' }],
      qualityReview: null, qualityReviewCurrent: false })) }, budget: { estimatedInputTokens: 12000, maxOutputTokens: 6000,
        contextWindowTokens: 64000, safetyMarginTokens: 4000, inputLimitTokens: 54000, fits: true, modelCalls: 1, notice: '保守估算，非真实费用。' },
    latestReport: null, latestValidReport: null }
}
export function openingReport(view = openingView()): OpeningReport {
  return { id: 'r1', versionNumber: 1, provider: 'DEEPSEEK', reviewMode: 'MODEL', current: true,
    fingerprint: view.source.fingerprint, source: view.source, budget: view.budget, createdAt: '2026-10-05T00:00:00Z',
    content: { summary: '范围与限制', assessments: [{ dimension: 'CAUSAL_CONTINUITY', status: 'OBSERVATION', observation: '核对承接',
      evidence: [{ chapterNumber: 1, quote: '第1章结尾。' }, { chapterNumber: 2, quote: '第2章开头。' }] }],
      issues: [{ id: 'i1', dimension: 'PAYOFF', description: '兑现需作者复核', suggestion: '对照第三章行动。', evidence: [{ chapterNumber: 3, quote: '第3章结尾。' }] }] } }
}

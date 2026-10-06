import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect, type Page } from '@playwright/test'
import type { QualityReview, WritingStyleProfile } from '../src/api/writingQuality'

async function capture(page: Page, path: string) {
  await page.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: 'instant' }))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({ path, fullPage: true })
}

test('chooses and analyzes an editable style, then checks and revises a manuscript', async ({ page }, testInfo) => {
  const profile: WritingStyleProfile = { name: '悬疑克制', narrativeVoice: '克制', sentenceRhythm: '长短交替', descriptionFocus: '具体物件', dialogueStyle: '保留信息差', emotionalExpression: '动作表达', pacing: '线索递进', avoidPatterns: ['重复解释'] }
  let style: WritingStyleProfile | null = null
  let version = 0
  let applied = 0
  let manuscript = { id: 'm1', projectId: 'test-project', chapterNumber: 1, versionNumber: 1, version: 0, status: 'DRAFT', sourceContractVersionId: 'c1', baseManuscriptVersionId: null as string | null, sourceReviewVersionId: null, generatorType: 'LOCAL_TEMPLATE', changeSummary: [] as string[], content: { title: '纸条', body: '然后他走到门口，接着看见纸条，随后停下来。。', summary: '发现纸条', continuityNotes: [] } }
  let report: object | null = null
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async route => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/writing-style/presets')) return route.fulfill({ json: [profile] })
    if (path.endsWith('/writing-style')) {
      if (request.method() === 'PUT') { expect(request.postDataJSON().expectedVersion).toBe(version); style = request.postDataJSON().profile; version++; applied++ }
      return route.fulfill({ json: { profile: style, version } })
    }
    if (path.endsWith('/writing-style/actions/analyze') || path.endsWith('/writing-style/actions/upload')) {
      return route.fulfill({ json: { profile: { ...profile, name: '样本节奏' }, analysisMode: 'TEXT_METRICS', sampleCharacters: 100 } })
    }
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: { id: 'o1', status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '纸条', objective: '寻找线索' }] }] } } })
    if (path.endsWith('/contracts/latest')) return route.fulfill({ json: { id: 'c1', version: 0, versionNumber: 1, status: 'APPROVED', content: { chapterTitle: '纸条', pov: '主角', objective: '寻找线索', storyTime: '当天', locations: [], requiredBeats: [], requiredReveals: [], forbiddenFacts: [], expectedExitState: '发现纸条', foreshadowActions: [], hook: '纸条内容', suggestedMinWords: 1000, suggestedMaxWords: 2000 } } })
    if (path.endsWith('/manuscripts/latest')) return route.fulfill({ json: manuscript })
    if (path.endsWith('/manuscripts') || path.endsWith('/contracts') || /\/(character-profiles|characters|entities)$/.test(path)) return route.fulfill({ json: [] })
    if (path.endsWith('/quality-reviews/actions/generate')) {
      report = { id: 'q1', sourceManuscriptId: manuscript.id, sourceManuscriptRowVersion: manuscript.version, current: true, content: { summary: '本地规则检查；不能判断因果与动机。', scores: ['STYLE', 'FLUENCY', 'LOGIC', 'SCENE'].map(dimension => ({ dimension, score: null, rationale: '本地不提供文学评分' })), issues: [{ id: 'Q1', category: 'FLUENCY', severity: 'INFO', description: '连续标点', evidence: '。。', suggestion: '检查并删除误输入标点', resolved: false }] } }
      return route.fulfill({ status: 201, json: report })
    }
    if (path.endsWith('/quality-reviews/q1/actions/revise')) {
      expect(request.postDataJSON().issueIds).toEqual(['Q1'])
      expect(request.postDataJSON().provider).toBe('LOCAL_CODEX')
      manuscript = { ...manuscript, id: 'm2', versionNumber: 2, baseManuscriptVersionId: 'm1', changeSummary: ['检查了标点'], content: { ...manuscript.content, body: manuscript.content.body.replace('。。', '。') } }
      report = { ...report, current: false }
      return route.fulfill({ status: 201, json: manuscript })
    }
    if (path.endsWith('/quality-reviews/latest')) return report ? route.fulfill({ json: report }) : route.fulfill({ status: 204 })
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '风格验证', entryMode: 'MATERIALS', status: 'ACTIVE', currentCanonVersion: 0, version, creativeIntent: null } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '故事资料', exact: true }).click()
  await page.getByRole('button', { name: '写作风格', exact: true }).click()
  await page.getByLabel('风格预设').selectOption('悬疑克制')
  await expect(page.getByLabel('风格名称')).toHaveValue('悬疑克制')
  await page.getByRole('button', { name: '应用风格' }).click()
  await expect(page.getByText('项目风格已应用。')).toBeVisible()
  await page.getByLabel('样本文字').fill('他走到门口，停下脚步。她递来纸条，没有说话。'.repeat(10))
  await selectGlobalProvider(page, 'LOCAL_TEMPLATE')
  await page.getByRole('button', { name: '分析风格', exact: true }).click()
  await expect(page.getByLabel('风格名称')).toHaveValue('样本节奏')
  expect(applied).toBe(1)
  await page.locator('input[type=file]').setInputFiles({ name: 'sample.txt', mimeType: 'text/plain', buffer: Buffer.from('门口的纸条。'.repeat(30)) })
  await page.getByRole('button', { name: '分析风格', exact: true }).click()
  await expect(page.getByText('本地结果仅包含句式指标', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '应用风格' }).click()
  await expect.poll(() => applied).toBe(2)
  await capture(page, testInfo.outputPath('style.png'))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '写作', exact: true }).click()
  await page.locator('.chapter-flow').getByRole('button', { name: /检查与润色/ }).click()
  await selectGlobalProvider(page, 'LOCAL_TEMPLATE')
  await page.getByRole('button', { name: '检查正文', exact: true }).click()
  await expect(page.getByText('连续标点', { exact: false })).toBeVisible()
  await selectGlobalProvider(page, 'LOCAL_CODEX')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '按建议生成润色稿' }).click()
  await expect(page.locator('.editor-status')).toContainText('第 2 版')
  await expect(page.locator('.quality-panel')).toHaveAttribute('data-state', 'stale')
  await expect(page.locator('.quality-status')).toContainText('修订后需复检')
  await capture(page, testInfo.outputPath('quality.png'))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('checks authorization, source locations, stale rejection, recheck and author/canon gates', async ({ page }, testInfo) => {
  let manuscript = { id: 'm1', projectId: 'test-project', chapterNumber: 1, versionNumber: 1, version: 0, status: 'DRAFT',
    sourceContractVersionId: 'c1', baseManuscriptVersionId: null as string | null, sourceReviewVersionId: null,
    generatorType: 'LOCAL_CODEX', changeSummary: [] as string[],
    content: { title: '纸条', body: `然后他走到门口。。\n\n${'窗外的雨声压过脚步，她放下纸条，等他读完。'.repeat(30)}\n\n然后他走到门口。。`, summary: '发现纸条', continuityNotes: [] } }
  const history = new Map([[manuscript.id, structuredClone(manuscript)]])
  let report: QualityReview | null = null
  let checks = 0
  let revisions = 0
  let rejectNextRevision = false
  let releaseCheck: (() => void) | null = null
  let holdCheck = true
  let canonVersion = 0
  let openingReads = 0
  let openingModelCalls = 0
  let review: Record<string, unknown> | null = null
  const scopes: string[] = []
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async route => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '受控修订', entryMode: 'MATERIALS', status: 'ACTIVE', currentCanonVersion: canonVersion, version: 0, creativeIntent: null } })
    if (path.endsWith('/opening-review')) {
      openingReads++
      return route.fulfill({ json: { available: false, source: { projectId: 'test-project', outlineId: 'o1', outlineRowVersion: 0,
        bibleId: 'b1', bibleRowVersion: 0, canonVersion, strategy: 'STANDARD', outlineContext: '', bibleContext: '', styleContext: '', profileContext: '',
        fingerprint: 'opening-source', unavailableReasons: ['第二、三章缺少完整正文，无法进行三章通读。'], chapters: [] },
      budget: { estimatedInputTokens: 12000, maxOutputTokens: 2000, contextWindowTokens: 64000, safetyMarginTokens: 1000,
        inputLimitTokens: 61000, fits: false, modelCalls: 1, notice: '主动检查完整三章会调用一次模型。' }, latestReport: null, latestValidReport: null } })
    }
    if (path.endsWith('/opening-review/actions/check')) { openingModelCalls++; return route.fulfill({ status: 400, json: { detail: '缺少正文' } }) }
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: { id: 'o1', status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '纸条', objective: '寻找线索' }] }] } } })
    if (path.endsWith('/contracts/latest')) return route.fulfill({ json: { id: 'c1', sourceOutlineVersionId: 'o1', version: 0, versionNumber: 1, status: 'APPROVED', content: { chapterTitle: '纸条', pov: '主角', objective: '寻找线索', storyTime: '当天', locations: [], requiredBeats: [], requiredReveals: [], forbiddenFacts: [], expectedExitState: '发现纸条', foreshadowActions: [], hook: '纸条内容', suggestedMinWords: 1000, suggestedMaxWords: 2000 } } })
    if (path.endsWith('/manuscripts/latest')) return route.fulfill({ json: manuscript })
    if (/\/manuscripts\/m\d+$/.test(path)) return route.fulfill({ json: history.get(path.split('/').at(-1)!) })
    if (path.endsWith('/manuscripts') || path.endsWith('/contracts') || path.endsWith('/entities')) return route.fulfill({ json: [] })
    if (path.endsWith('/quality-reviews/latest')) return report ? route.fulfill({ json: report }) : route.fulfill({ status: 204 })
    if (path.endsWith('/quality-reviews/actions/generate')) {
      if (holdCheck) await new Promise<void>(resolve => { releaseCheck = resolve })
      checks++
      report = { id: `q${checks}`, projectId: 'test-project', chapterNumber: 1, versionNumber: checks,
        sourceManuscriptId: manuscript.id, sourceManuscriptRowVersion: manuscript.version, generatorType: 'LOCAL_CODEX', current: true, createdAt: '',
        content: { summary: '当前保存版本的质量建议，候选仍需作者核对。',
          scores: ['STYLE', 'FLUENCY', 'LOGIC', 'SCENE'].map(dimension => ({ dimension: dimension as 'STYLE' | 'FLUENCY' | 'LOGIC' | 'SCENE', score: null, rationale: '不作通过保证；结合正文由作者判断。' })),
          issues: manuscript.versionNumber >= 3 ? [] : [
            { id: 'Q1', severity: 'INFO', category: 'FLUENCY', description: '连续标点与重复表达', evidence: '然后他走到门口', suggestion: '只修正误输入标点，不增加事件。', resolved: false },
            { id: 'Q2', severity: 'WARNING', category: 'SCENE', description: `场景组织需调整。${'保留原有事件、人物关系与退出状态。'.repeat(8)}`, evidence: '她放下纸条', suggestion: '仅调整已有场景的段落组织，保留发生顺序。', resolved: false },
          ] } }
      return route.fulfill({ status: 201, json: report })
    }
    if (/\/quality-reviews\/q\d+\/actions\/revise$/.test(path)) {
      const input = request.postDataJSON()
      if (rejectNextRevision) {
        rejectNextRevision = false; report = { ...report!, current: false }
        return route.fulfill({ status: 409, json: { detail: '报告依据已过期，请重新检查' } })
      }
      expect(input.issueIds).toEqual(revisions === 0 ? ['Q1'] : ['Q2'])
      expect(input.scope).toBe(revisions === 0 ? 'EXPRESSION_ONLY' : 'SCENE_STRUCTURE')
      scopes.push(input.scope); revisions++
      history.set(manuscript.id, structuredClone(manuscript))
      manuscript = { ...manuscript, id: `m${revisions + 1}`, versionNumber: revisions + 1, status: 'DRAFT', baseManuscriptVersionId: manuscript.id,
        changeSummary: [`${input.issueIds[0]}：${revisions === 1 ? '修正标点' : '调整既有段落组织'}，等待作者核对`],
        content: { ...manuscript.content, body: manuscript.content.body.replaceAll('。。', '。') } }
      report = { ...report!, current: false }
      return route.fulfill({ status: 201, json: manuscript })
    }
    if (path.endsWith('/actions/accept')) {
      expect(revisions).toBe(2)
      expect(request.headers()['if-match']).toBe('"0"')
      manuscript = { ...manuscript, status: 'AUTHOR_ACCEPTED', version: 1 }
      report = { ...report!, current: false }
      return route.fulfill({ json: manuscript })
    }
    if (path.endsWith('/reviews/latest')) return review ? route.fulfill({ json: review }) : route.fulfill({ status: 204 })
    if (path.endsWith('/reviews/actions/generate')) {
      expect(manuscript.status).toBe('AUTHOR_ACCEPTED')
      review = { id: 'r1', projectId: 'test-project', chapterNumber: 1, sourceManuscriptVersionId: manuscript.id, versionNumber: 1,
        status: 'DRAFT', version: 0, content: { summary: '独立正史审稿', issues: [], factProposals: [] } }
      return route.fulfill({ status: 201, json: review })
    }
    if (path.endsWith('/reviews/r1/actions/approve')) {
      review = { ...review, status: 'APPROVED', version: 1 }
      return route.fulfill({ json: review })
    }
    if (path.endsWith('/canon-commits/status')) return route.fulfill({ json: { committed: canonVersion > 0, canonVersion, activeCommitId: canonVersion ? 'commit1' : null, activeManuscriptVersionId: canonVersion ? manuscript.id : null } })
    if (path.endsWith('/canon-commits')) {
      expect(review?.status).toBe('APPROVED')
      expect(request.postDataJSON()).toEqual({ reviewVersionId: 'r1', expectedCanonVersion: 0 })
      canonVersion = 1
      return route.fulfill({ status: 201, json: { id: 'commit1', canonVersion: 1 } })
    }
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '写作', exact: true }).click()
  const qualityStep = page.locator('.chapter-flow').getByRole('button', { name: /检查与润色/ })
  await qualityStep.click()
  const panel = page.locator('.quality-panel')
  await expect(panel).toHaveAttribute('data-state', 'unrun')
  expect(checks).toBe(0)
  expect(openingReads).toBe(0)
  await panel.getByRole('button', { name: '本次跳过', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'skipped')
  await page.locator('.chapter-flow').getByRole('button', { name: /正文草稿/ }).click()
  await qualityStep.click()
  await expect(panel).toHaveAttribute('data-state', 'skipped')
  await panel.getByRole('button', { name: '检查正文', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'rechecking')
  await expect.poll(() => !!releaseCheck).toBe(true)
  holdCheck = false; releaseCheck!()
  await expect(panel).toHaveAttribute('data-state', 'valid')
  await expect(panel.getByRole('radio', { name: '仅表达润色', exact: true })).toBeChecked()
  await expect(panel.locator('input[value="Q2"]')).toBeDisabled()
  await panel.getByRole('button', { name: '定位原文', exact: true }).first().click()
  await expect(panel.locator('mark')).toHaveText('然后他走到门口')
  await panel.getByRole('button', { name: '下一处原文', exact: true }).click()
  await expect(panel.locator('.quality-original header')).toContainText('第 3 段')
  await capture(page, testInfo.outputPath('quality-valid-located.png'))
  await panel.locator('input[value="Q1"]').check()
  await panel.getByRole('button', { name: '按建议生成润色稿', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'stale')
  await expect(page.locator('.quality-change-summary')).toContainText('Q1：修正标点')
  await expect(panel.locator('.quality-status')).toContainText('修订后需复检')
  await panel.getByRole('button', { name: '复检正文', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'valid')
  await panel.getByRole('radio', { name: '选中问题的场景结构修订', exact: true }).check()
  await panel.locator('input[value="Q2"]').check()
  rejectNextRevision = true
  await panel.getByRole('button', { name: '按建议生成润色稿', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'stale')
  await expect(panel.getByRole('button', { name: '按建议生成润色稿', exact: true })).toBeDisabled()
  await expect(page.locator('.editor-status')).toContainText('第 2 版')
  expect(revisions).toBe(1)
  await panel.getByRole('button', { name: '复检正文', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'valid')
  await panel.locator('input[value="Q2"]').check()
  await capture(page, testInfo.outputPath('quality-scene-authorization.png'))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(await panel.evaluate(root => [...root.querySelectorAll('.quality-actions')].every(group => {
    const boxes = [...group.querySelectorAll('button')].map(button => button.getBoundingClientRect())
    return boxes.every((a, i) => boxes.slice(i + 1).every(b => a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top))
  }))).toBe(true)
  await panel.getByRole('button', { name: '按建议生成润色稿', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'stale')
  await expect(page.locator('.editor-status')).toContainText('第 3 版')
  expect(scopes).toEqual(['EXPRESSION_ONLY', 'SCENE_STRUCTURE'])
  expect(manuscript.status).toBe('DRAFT')
  expect(canonVersion).toBe(0)
  await panel.getByRole('button', { name: '复检正文', exact: true }).click()
  await expect(panel).toHaveAttribute('data-state', 'valid')
  await expect(panel.getByText('本次检查未提出修改建议。', { exact: true })).toBeVisible()
  await page.locator('.chapter-flow').getByRole('button', { name: /正史审稿/ }).click()
  await expect(page.getByRole('button', { name: '开始审稿', exact: true })).toBeDisabled()
  await page.locator('.chapter-flow').getByRole('button', { name: /作者确认/ }).click()
  await page.getByRole('button', { name: '作者确认', exact: true }).click()
  await page.locator('.chapter-flow').getByRole('button', { name: /正史审稿/ }).click()
  await page.getByRole('button', { name: '开始审稿', exact: true }).click()
  await expect(page.getByText('独立正史审稿', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '确认审稿', exact: true }).click()
  await page.getByRole('button', { name: '提交正史', exact: true }).click()
  await expect(page.getByText('已提交为正史 v1', { exact: false })).toBeVisible()
  await page.locator('.planning-tabs').getByRole('button', { name: '前三章连读', exact: true }).click()
  await expect(page.getByText('第二、三章缺少完整正文，无法进行三章通读。', { exact: true })).toBeVisible()
  await expect(page.locator('.opening-panel .budget')).toContainText('本次模型调用 1 次')
  await expect(page.getByRole('button', { name: '检查完整三章', exact: true })).toBeDisabled()
  expect(openingReads).toBeGreaterThan(0)
  expect(openingModelCalls).toBe(0)
  await capture(page, testInfo.outputPath('quality-opening-entry.png'))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

import { expect, test, type Page } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import { bible } from '../src/__tests__/character-blueprint-fixtures'

const wordBudget = { targetWords: 50000, acceptableMinWords: 40000, acceptableMaxWords: 60000,
  recommendedVolumeCount: 1, recommendedChapterCount: 20, averageChapterWords: 2500,
  recommendedChapterMinWords: 2000, recommendedChapterMaxWords: 3000 }
const chapters = [1, 2, 3].map(number => ({ number, title: ['未递出的纸条', '被撤回的署名', '把这件事说清'][number - 1],
  pov: '江澈', objective: '把遗漏的条件补齐', coreEvent: '在同学面前承担修改责任', reveal: '资料缺少条件',
  endingHook: '另一份资料仍在传阅', suggestedMinWords: 2000, suggestedMaxWords: 3000, status: 'PLANNED',
  sceneOutline: '教室：江澈先停止问题版本传阅，同学要求说明删改理由。\n对照原稿发现条件缺失，他收回版本并承担逐页返工；仍有一份未收回。',
  sceneOutlineNeedsUpdate: number === 2 }))
const outline = { id: 'o1', projectId: 'ux', generationNumber: 1, status: 'PUBLISHED', version: 0,
  generatorType: 'LOCAL_CODEX', sourceBibleVersionId: 'bible-1', wordBudget, changeSummary: [],
  content: { title: '未递出的纸条', premise: '在感情和责任之间学会选择', structureSummary: '误会逐步暴露',
    pacingStrategy: '行动与后果紧密衔接', suggestedMinWords: 40000, suggestedMaxWords: 60000,
    arcs: [{ ordinal: 1, title: '第一卷', objective: '承担选择', mainConflict: '愿望与事实', turningPoint: '资料缺失', outcome: '完成返工',
      suggestedMinWords: 40000, suggestedMaxWords: 60000, chapters }] } }

async function fixture(page: Page, entryMode = 'IDEA') {
  const models = createModelSettingsFixture()
  let writes = 0
  const contractChapters: number[] = []
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') { writes++; return route.fulfill({ status: 400, json: { detail: '此测试不应发起生成或保存' } }) }
    if (/\/projects\/[^/]+$/.test(path)) return route.fulfill({ json: {
      id: path.split('/').at(-1), name: '全班都知道她喜欢我，除了我', entryMode, currentCanonVersion: 0, version: 0,
      creativeIntent: { premise: '校园关系中的选择', genres: ['校园'], protagonistBrief: '江澈', centralConflict: '回避选择',
        targetAudience: '青年读者', tones: ['克制'], targetWords: 50000, mustHave: ['不改变已经发生的事实'], avoid: [], stylePreferences: [] },
    } })
    if (path.endsWith('/story-directions/latest')) return route.fulfill({ json: {
      id: 'd1', projectId: 'ux', generationNumber: 1, status: 'DRAFT', generatorType: 'LOCAL_CODEX', selectedCandidateId: null,
      changeSummary: [], questionsForAuthor: [], wordBudget, directions: [1, 2, 3].map(number => ({
        id: `d${number}`, title: `方向 ${number} · 谁应该先说清楚`, premise: '一张未递出的纸条，改变了三个人对彼此的判断。',
        centralConflict: '主角想维持平静，却需要承担选择', protagonistArc: '从回避到主动承担',
        structure: '小误会逐渐累积为公开的抉择', endingDirection: '完成责任，不强求全面谅解', strengths: ['行动推动关系变化'], risks: ['避免无依据推测'] })) } })
    if (path.endsWith('/story-bibles/latest')) return route.fulfill({ json: { ...bible(), projectId: 'ux' } })
    if (path.endsWith('/story-bibles/current')) return route.fulfill({ json: { ...bible(), projectId: 'ux', status: 'PUBLISHED' } })
    if (path.endsWith('/story-bibles')) return route.fulfill({ json: [] })
    if (path.endsWith('/outlines/latest') || path.endsWith('/outlines/current')) return route.fulfill({ json: outline })
    if (path.endsWith('/outlines')) return route.fulfill({ json: [] })
    if (path.endsWith('/planning-batches')) return route.fulfill({ json: [] })
    if (/\/chapters\/\d+\/contracts\/latest$/.test(path)) {
      const number = Number(path.match(/\/chapters\/(\d+)/)![1]); contractChapters.push(number)
      return route.fulfill({ json: { id: `c${number}`, versionNumber: 1, status: 'APPROVED', version: 0, sourceOutlineVersionId: 'o1',
        content: { chapterNumber: number, chapterTitle: chapters[number - 1]?.title, objective: '完成责任', pov: '江澈',
          locations: [], requiredBeats: [], requiredReveals: [], forbiddenFacts: [], foreshadowActions: [],
          minWords: 2000, maxWords: 3000, endingHook: '仍有人没有回应', entryState: '问题暴露', exitState: '完成返工' } } })
    }
    if (/\/manuscripts\/latest$/.test(path)) return route.fulfill({ json: { id: 'm1', versionNumber: 1, version: 0, status: 'DRAFT', changeSummary: [],
      content: { title: '被撤回的署名', body: '江澈把那份少了一行条件的资料按在桌上。\n“先别传了，是我删掉的。”\n他把笔递过去，又收了回来。这次要自己补齐。', continuityNotes: [] } } })
    if (path.endsWith('/canon-commit-status')) return route.fulfill({ json: { committed: false } })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: [{ id: 'running', stage: 'MANUSCRIPT', status: 'RUNNING',
      startedAt: new Date().toISOString(), chapterNumber: 2 }, { id: 'failed', stage: 'OUTLINE', status: 'FAILED',
      startedAt: new Date().toISOString(), completedAt: new Date().toISOString(), errorMessage: '测试超时' }] })
    if (/\/(characters|entities|timeline|profiles|imports|contracts|manuscripts)$/.test(path)) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  return { get writes() { return writes }, contractChapters }
}

test('automatic draft loop restores its round after refresh and can stop without author input', async ({ page }, info) => {
  const baseline = await fixture(page)
  let run: Record<string, unknown> | null = null
  let payload: Record<string, unknown> | null = null
  await page.route('**/chapters/1/draft-loops**', async route => {
    const request = route.request()
    if (request.method() === 'POST' && request.url().endsWith('/actions/stop')) {
      run = { ...run, status: 'CANCELLED', stopReason: 'CANCELLED' }
      return route.fulfill({ json: run })
    }
    if (request.method() === 'POST') {
      payload = request.postDataJSON()
      run = { id: 'loop-1', projectId: 'ux', chapterNumber: 1, provider: 'LOCAL_CODEX', writeFirst: true,
        maxRounds: 10, status: 'RUNNING', phase: 'A', stopReason: null, manuscriptId: 'm1', rounds: [],
        errorMessage: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
      return route.fulfill({ status: 202, json: run })
    }
    if (run?.phase === 'A') run = { ...run, phase: 'C', rounds: [{ number: 1, beforeManuscriptId: 'm1',
      before: { title: '纸条', body: '她递还纸条。', summary: '归还', continuityNotes: [] },
      check: { summary: '本轮检查', issues: [{ id: 'F1', category: 'FLUENCY', description: '指代不明', evidence: '她递还纸条。',
        existingBasis: '本章人物', gap: '', candidateDesign: '', impact: '', suggestion: '明确动作主体' }] }, judgment: null, afterManuscriptId: null }] }
    return route.fulfill({ json: run ? [run] : [] })
  })
  await page.goto('/projects/ux?section=writing&chapter=1&writing=manuscript')
  const panel = page.getByRole('region', { name: '自动写作与检查' })
  await panel.getByRole('radio', { name: '从大纲创作' }).check()
  await panel.getByRole('button', { name: '开始自动创作' }).click()
  await expect(panel.getByRole('status')).toContainText('C · 正在裁决与修订')
  expect(payload).toEqual({ provider: 'LOCAL_CODEX', writeFirst: true, maxRounds: 10 })
  await expect(page.getByLabel('正文', { exact: true })).toBeDisabled()
  await page.reload()
  await expect(panel.getByRole('status')).toContainText('第 1 / 10 轮')
  await panel.getByRole('button', { name: '停止', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('已停止')
  await expect(page.getByLabel('正文', { exact: true })).toBeEnabled()
  await page.setViewportSize({ width: 390, height: 850 })
  await panel.screenshot({ path: info.outputPath('draft-loop-stopped-mobile.png') })
  expect(baseline.writes).toBe(0)
})

test('automatic draft history distinguishes candidate patches, decisions, real diff and unverified cap', async ({ page }, info) => {
  const baseline = await fixture(page)
  const before = { title: '纸条', body: '她递还纸条。', summary: '归还纸条', continuityNotes: [] }
  await page.route('**/chapters/1/draft-loops', route => route.fulfill({ json: [{
    id: 'loop-1', projectId: 'ux', chapterNumber: 1, provider: 'LOCAL_CODEX', writeFirst: true,
    maxRounds: 1, status: 'STOPPED', phase: 'B', stopReason: 'ROUND_LIMIT', manuscriptId: 'm2',
    errorMessage: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), rounds: [{
      number: 1, beforeManuscriptId: 'm1', before, afterManuscriptId: 'm2',
      check: { summary: '发现一处指代不清', issues: [{ id: 'F1', category: 'FLUENCY', description: '动作主体不明', evidence: before.body,
        existingBasis: '本章视角为沈秋', gap: '', candidateDesign: '用姓名代替代词', impact: '不改变事件', suggestion: '明确主体' }] },
      judgment: { action: 'REVISED', decisions: [{ issueId: 'F1', verdict: 'ACCEPT', reason: '依据来自独立提供的章节计划' }],
        content: { ...before, body: '沈秋递还纸条。' }, changeSummary: ['F1 明确主体，保留归还纸条的动作'] },
    }],
  }] }))
  for (const width of [320, 390, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/projects/ux?section=writing&chapter=1&writing=manuscript')
    const panel = page.getByRole('region', { name: '自动写作与检查' })
    await expect(panel.getByRole('status')).toContainText('最后修订稿未复检')
    await panel.locator('.draft-loop-round > summary').click()
    await expect(panel.getByText('候选设计（非既定事实）：', { exact: true })).toBeVisible()
    await expect(panel.getByText('C · 接受并修订：', { exact: true })).toBeVisible()
    await panel.getByText('正文修改对比', { exact: true }).click()
    await expect(panel.locator('pre').nth(1)).toHaveText('沈秋递还纸条。')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await panel.evaluate(element => element.scrollIntoView({ block: 'start' }))
    await expect.poll(() => panel.boundingBox().then(box => box?.y ?? 0)).toBeGreaterThanOrEqual(60)
    await panel.screenshot({ path: info.outputPath(`draft-loop-history-${width}.png`) })
  }
  expect(baseline.writes).toBe(0)
})

test('displays saved and stale scene drafts without extra requests on mobile and desktop', async ({ page }, info) => {
  const state = await fixture(page)
  for (const width of [320, 390, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/projects/ux?section=outline&planning=outline')
    const chapter = page.locator('.chapter-list details').nth(1)
    await chapter.locator('summary').click()
    const field = chapter.getByLabel('场景清单与展开', { exact: false })
    await expect(field).toHaveValue(/逐页返工/)
    await expect(field).toBeDisabled()
    await expect(chapter.getByRole('status')).toContainText('场景底稿待核对')
    await expect(page.getByText(/Cannot read properties|NaN/)).toHaveCount(0)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await field.scrollIntoViewIfNeeded()
    const box = await field.boundingBox()
    expect(box?.x).toBeGreaterThanOrEqual(0)
    expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: info.outputPath(`outline-scenes-${width}.png`), fullPage: true })
  }
  expect(state.writes).toBe(0)
})

test('persists main and planning tabs, supports back/forward, and keeps visible running tasks when collapsed', async ({ page }, info) => {
  const state = await fixture(page)
  await page.goto('/projects/ux')
  await page.getByRole('button', { name: '故事圣经', exact: true }).click()
  await expect(page).toHaveURL(/planning=bible/)
  await page.reload()
  await expect(page.getByLabel('一句话故事', { exact: true })).toHaveValue('学生面对选择')
  await page.getByRole('button', { name: '分层大纲', exact: true }).click()
  await expect(page).toHaveURL(/planning=outline/)
  await page.goBack()
  await expect(page.getByLabel('一句话故事', { exact: true })).toBeVisible()
  await page.goForward()
  await expect(page.getByLabel('大纲标题', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '展开生成状态' }).click()
  await expect(page.getByRole('region', { name: '生成状态' }).locator('[data-stage="STORY_DIRECTION"]')).toBeVisible()
  await page.getByRole('button', { name: '收起生成状态' }).click()
  const status = page.getByRole('region', { name: '生成状态' })
  await expect(status.locator('[data-stage="MANUSCRIPT"]')).toBeVisible()
  await expect(status.locator('[data-stage="OUTLINE"]')).toBeVisible()
  await expect(status.locator('[data-stage="STORY_DIRECTION"]')).toBeHidden()
  await page.screenshot({ path: info.outputPath('outline-desktop.png'), fullPage: true })
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await expect(page).toHaveURL(/section=runs/)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Agent 任务与成本' })).toBeVisible()
  expect(state.writes).toBe(0)
})

test('restores manuscript chapter and materials sub-tabs without generating content', async ({ page }, info) => {
  const state = await fixture(page)
  await page.goto('/projects/ux?section=writing&chapter=2&writing=manuscript')
  await expect(page.getByLabel('正文', { exact: true })).toHaveValue(/江澈/)
  await expect(page.locator('.chapter-rail button.active')).toContainText('2')
  await page.reload()
  await expect(page.locator('.chapter-rail button.active')).toContainText('2')
  await expect(page.getByRole('radio', { name: '重新创作一版' })).toBeVisible()
  await page.getByRole('radio', { name: '重新创作一版' }).check()
  await expect(page.getByRole('button', { name: '重新创作', exact: true })).toBeVisible()
  await page.locator('.chapter-rail button').filter({ hasText: '把这件事说清' }).click()
  await expect(page).toHaveURL(/chapter=3/)
  await page.screenshot({ path: info.outputPath('writing-desktop.png'), fullPage: true })
  await page.getByRole('button', { name: '故事资料', exact: true }).click()
  await page.getByRole('button', { name: '事件时间线', exact: true }).click()
  await expect(page).toHaveURL(/materials=timeline/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '事件时间线', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '写作', exact: true }).click()
  await expect(page.locator('.chapter-rail button.active')).toContainText('3')
  expect(state.contractChapters).toEqual([])
  expect(state.writes).toBe(0)
})

test('guards dirty bible edits and follows the destination only after confirmation', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/projects/ux?section=outline&planning=bible')
  await page.getByLabel('一句话故事', { exact: true }).fill('尚未保存的新故事')
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '分层大纲', exact: true }).click()
  await expect(page).toHaveURL(/planning=bible/)
  await expect(page.getByLabel('一句话故事', { exact: true })).toHaveValue('尚未保存的新故事')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '分层大纲', exact: true }).click()
  await expect(page).toHaveURL(/planning=outline/)
  expect(state.writes).toBe(0)
})

for (const width of [320, 390, 768, 1440, 1920]) {
  test(`fits direction, bible and manuscript controls at ${width}px`, async ({ page }, info) => {
    const state = await fixture(page)
    await page.setViewportSize({ width, height: 900 })
    for (const [name, query] of [['directions', '?section=outline&planning=directions'], ['bible', '?section=outline&planning=bible'], ['writing', '?section=writing&chapter=2&writing=manuscript']]) {
      await page.goto(`/projects/ux${query}`)
      await expect(page.getByRole('heading', { name: '全班都知道她喜欢我，除了我' })).toBeVisible()
      await expect(page.locator('.generation-mode-control')).toBeVisible()
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      const controls = page.locator('.generation-mode-control label')
      for (const control of await controls.all()) {
        const box = await control.boundingBox()
        expect(box?.x).toBeGreaterThanOrEqual(0)
        expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual(width)
      }
      await page.screenshot({ path: info.outputPath(`${name}-${width}.png`), fullPage: true })
    }
    expect(state.writes).toBe(0)
  })
}

test('uses import default only when no explicit section is supplied', async ({ page }) => {
  await fixture(page, 'MANUSCRIPT')
  await page.goto('/projects/ux')
  await expect(page.getByRole('heading', { name: '导入与章节识别' })).toBeVisible()
  await page.goto('/projects/ux?section=outline&planning=bible')
  await expect(page.getByLabel('一句话故事', { exact: true })).toBeVisible()
})

test('navigates planning tabs and generation radios by keyboard', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/projects/ux?section=outline&planning=directions')
  await page.getByRole('button', { name: '故事方向', exact: true }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page).toHaveURL(/planning=bible/)
  await expect(page.getByRole('button', { name: '故事圣经', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('radio', { name: '基于当前版本调整' }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('radio', { name: '重新生成', exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: '重新生成', exact: true })).toBeVisible()
  expect(state.writes).toBe(0)
})

test('shows free-text snowflake stages and partial failures on desktop and mobile', async ({ page }, info) => {
  const state = await fixture(page)
  await page.route('**/snowflake-plans/latest', route => route.fulfill({ json: {
    id: 's1', projectId: 'ux', mode: 'NEW_STORY', provider: 'DEEPSEEK',
    status: 'FAILED', activeStage: 'WORLD', core: '面对被删去的条件，主角必须停止资料传阅。'.repeat(10),
    characters: '江澈想维持体面，却需要在没有全面谅解时完成责任。人物弧光是自由叙述。',
    world: null, plot: null, errorMessage: '世界构建请求超时，前面两步已保存。',
    createdAt: '', updatedAt: '',
  } }))
  await page.goto('/projects/ux?section=outline&planning=bible')
  const panel = page.getByRole('region', { name: '雪花渐进规划' })
  await expect(panel.getByRole('heading', { name: '雪花渐进规划' })).toBeVisible()
  await expect(panel.getByRole('alert')).toContainText('前面两步已保存')
  await panel.locator('summary').first().click()
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(panel.locator('.snowflake-text').first()).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const box = await panel.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1)
    await page.screenshot({ path: info.outputPath(`snowflake-${width}.png`), fullPage: true })
    await panel.screenshot({ path: info.outputPath(`snowflake-panel-${width}.png`) })
  }
  expect(state.writes).toBe(0)
})

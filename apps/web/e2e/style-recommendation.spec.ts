import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect } from '@playwright/test'
import type { WritingStyleProfile } from '../src/api/writingQuality'

test('recommends from a saved bible, loads an unapplied candidate and tries the first chapter', async ({ page }) => {
  const first: WritingStyleProfile = { name: '老舍参考：市井幽默', narrativeVoice: '温厚观察', sentenceRhythm: '自然变化',
    descriptionFocus: '人物日常处境', dialogueStyle: '保留身份差异', emotionalExpression: '克制', pacing: '关系推进', avoidPatterns: ['强加方言'] }
  const second = { ...first, name: '现实细腻', narrativeVoice: '贴近人物感知' }
  const bible = { id: 'b1', version: 2, generationNumber: 3, status: 'DRAFT', changeSummary: [],
    content: { logline: '街坊一起寻找失物', theme: '信任与尊严', worldSetting: '城市街巷', worldRules: [], protagonist: '林雨',
      protagonistArc: '学会信任', supportingCharacters: [], relationshipDynamics: [], centralConflict: '街坊的误会',
      stakes: '失去信任', narrativeStyle: '温暖观察', endingDirection: '和解', hardConstraints: [], openQuestions: [] } }
  const chapter = { number: 1, title: '旧信', pov: '林雨', objective: '找到旧信', coreEvent: '门口发现信', reveal: '笔迹', endingHook: '敲门', suggestedMinWords: 1000, suggestedMaxWords: 2000, status: 'PLANNED' }
  const outline = { id: 'o1', version: 1, generationNumber: 1, status: 'DRAFT', generatorType: 'LOCAL_TEMPLATE', changeSummary: [], sourceBibleVersionId: 'b1',
    content: { title: '旧信', premise: '街坊与误会', structureSummary: '寻找', pacingStrategy: '递进', suggestedMinWords: 1000, suggestedMaxWords: 10000,
      arcs: [{ ordinal: 1, title: '第一卷', objective: '寻找', mainConflict: '误会', turningPoint: '旧信', outcome: '理解', suggestedMinWords: 1000, suggestedMaxWords: 10000, chapters: [chapter] }] } }
  let applied: WritingStyleProfile | null = null
  let recommendations = 0
  let previews = 0
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async (route) => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/writing-style/presets')) return route.fulfill({ json: [first, second] })
    if (path.endsWith('/writing-style')) {
      if (request.method() === 'PUT') { expect(request.postDataJSON().expectedVersion).toBe(8); applied = request.postDataJSON().profile }
      return route.fulfill({ json: { profile: applied, version: applied ? 9 : 8 } })
    }
    if (path.endsWith('/story-bibles/latest')) return route.fulfill({ json: bible })
    if (path.endsWith('/outlines/latest')) return route.fulfill({ json: outline })
    if (path.endsWith('/writing-style/actions/recommend')) {
      expect(request.postDataJSON()).toEqual({ bibleVersionId: 'b1', expectedBibleVersion: 2, provider: 'DEEPSEEK', instruction: '保留生活温度' })
      recommendations++
      return route.fulfill({ json: { sourceBibleVersionId: 'b1', sourceBibleRowVersion: 2, bibleGenerationNumber: 3,
        provider: 'DEEPSEEK', recommendationMode: 'MODEL', summary: '人物关系与街巷生活适合温厚、具体的叙事。',
        recommendations: [first, second].map((profile) => ({ profile, reason: '从日常处境表现人物尊严，幽默来自误会而非嘲弄。',
          tradeoff: '不强加地域方言，不弱化核心冲突。', evidence: [{ field: 'theme', quote: '信任与尊严' }, { field: 'worldSetting', quote: '城市街巷' }] })) } })
    }
    if (path.endsWith('/writing-style/actions/preview')) {
      const input = request.postDataJSON()
      expect(input.profile).toEqual(first)
      expect(input.outlineVersionId).toBe('o1')
      previews++
      return route.fulfill({ json: { sourceOutlineVersionId: 'o1', sourceOutlineRowVersion: 1, outlineGenerationNumber: 1,
        sourceBibleVersionId: 'b1', profile: first, provider: input.provider, targetWords: 800, previewMode: 'MODEL',
        content: { title: '旧信', body: '她把信封按在桌上。隔壁的门开了半扇，有人问起昨天丢的那把钥匙。' } } })
    }
    if (path.endsWith('/writing-style/actions/check-preview')) return route.fulfill({ json: {
      id: 'review-1', provider: 'LOCAL_CODEX', reviewMode: 'MODEL', revisionAttempted: false,
      content: { summary: '本次检查未提出修改建议。', scores: [], issues: [] },
    } })
    if (path.endsWith('/recommend-test')) return route.fulfill({ json: { id: 'recommend-test', name: '圣经风格建议验证', entryMode: 'MATERIALS', version: 8, currentCanonVersion: 0, creativeIntent: null } })
    if (path.endsWith('/outlines') || path.endsWith('/story-bibles')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/recommend-test')
  await page.getByRole('button', { name: '风格试写', exact: true }).click()
  await expect(page.getByRole('heading', { name: '圣经风格建议', exact: true })).toBeVisible()
  await selectGlobalProvider(page, 'DEEPSEEK')
  await page.getByLabel('推荐偏好').fill('保留生活温度')
  await page.getByRole('button', { name: '根据圣经推荐风格', exact: true }).click()
  await expect(page.locator('.recommendation-list > li')).toHaveCount(2)
  await expect(page.locator('.recommendation-evidence').first()).toContainText('主题：信任与尊严')
  expect(applied).toBeNull()
  await page.locator('.recommendation-list > li').first().getByRole('button', { name: '载入此风格' }).click()
  await expect(page.getByLabel('风格名称')).toHaveValue(first.name)
  expect(applied).toBeNull()
  await page.getByRole('button', { name: '试写第一章开头' }).click()
  await expect(page.locator('.preview-result')).toHaveCount(1)
  expect(previews).toBe(1)
  expect(recommendations).toBe(1)
  expect(applied).toBeNull()
  await page.evaluate(() => window.scrollTo(0, 0))
expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '应用风格', exact: true }).click()
  await expect.poll(() => applied).toEqual(first)
})

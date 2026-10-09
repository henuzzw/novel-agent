import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect, type Page } from '@playwright/test'
import type { StylePreview, WritingStyleProfile } from '../src/api/writingQuality'

const profile: WritingStyleProfile = { name: '轻快口语', narrativeVoice: '亲切鲜活', sentenceRhythm: '偏短句',
  descriptionFocus: '鲜明细节', dialogueStyle: '自然口语', emotionalExpression: '动作反应', pacing: '轻快推进', avoidPatterns: ['刻意抖机灵'] }
const original = '教室后门开着。她攥着座位名单的边角，问我身边有没有人。我摇头，她放下书包。'
const revised = '她问我身边有没有人。我摇头，她放下书包。'
const chapter = { number: 1, title: '选座', pov: '我', objective: '入座', coreEvent: '她坐到旁边', reveal: '关系',
  endingHook: '铃声', suggestedMinWords: 1000, suggestedMaxWords: 2000, status: 'PLANNED' }
const outline = { id: 'o1', version: 1, generationNumber: 1, status: 'DRAFT', generatorType: 'LOCAL_TEMPLATE', changeSummary: [], sourceBibleVersionId: 'b1',
  content: { title: '选座', premise: '同学与误会', structureSummary: '成长', pacingStrategy: '递进', suggestedMinWords: 1000, suggestedMaxWords: 10000,
    arcs: [{ ordinal: 1, title: '校园', objective: '选座', mainConflict: '误会', turningPoint: '入座', outcome: '靠近', suggestedMinWords: 1000, suggestedMaxWords: 10000, chapters: [chapter] }] } }

function candidate(body: string, provider = 'LOCAL_CODEX'): StylePreview {
  return { sourceOutlineVersionId: 'o1', sourceOutlineRowVersion: 1, outlineGenerationNumber: 1, sourceBibleVersionId: 'b1',
    profile, provider: provider as StylePreview['provider'], targetWords: 800, previewMode: 'MODEL', content: { title: '选座', body } }
}

async function setup(page: Page, failFirstCheck = false) {
  const checks: string[] = []
  const revisions: unknown[] = []
  let applications = 0
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async (route) => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/writing-style/presets')) return route.fulfill({ json: [profile] })
    if (path.endsWith('/writing-style')) {
      if (request.method() === 'PUT') applications++
      return route.fulfill({ json: { profile: null, version: 0 } })
    }
    if (path.endsWith('/outlines/latest')) return route.fulfill({ json: outline })
    if (path.endsWith('/outlines')) return route.fulfill({ json: [] })
    if (path.endsWith('/editing-test')) return route.fulfill({ json: { id: 'editing-test', name: '试写编辑验证', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0, creativeIntent: null } })
    if (path.endsWith('/writing-style/actions/preview')) return route.fulfill({ json: candidate(original, request.postDataJSON().provider) })
    if (path.endsWith('/writing-style/actions/check-preview')) {
      const input = request.postDataJSON()
      expect(input.source.profile).toEqual(profile)
      expect(input.source.expectedOutlineVersion).toBe(1)
      checks.push(input.content.body)
      if (failFirstCheck && checks.length === 1) return route.fulfill({ status: 503, json: { detail: '检查暂不可用' } })
      return route.fulfill({ json: { id: `r${checks.length}`, preview: candidate(input.content.body), provider: input.source.provider,
        reviewMode: 'MODEL', revisionAttempted: false,
        content: { summary: input.content.body === original ? '需复核细节的功能与名单来源。' : '本次检查未提出修改建议。',
          scores: ['STYLE', 'FLUENCY', 'LOGIC', 'SCENE'].map((dimension) => ({ dimension, score: 80, rationale: '测试评分' })),
          issues: input.content.body === original ? [
            { id: 'E1', severity: 'INFO', category: 'SCENE', description: '开门细节未利用', evidence: '教室后门开着。', suggestion: '删除未承担功能的细节。', resolved: false },
            { id: 'E2', severity: 'WARNING', category: 'LOGIC', description: '名单来源未交代', evidence: '她攥着座位名单的边角', suggestion: '不要凭空编造来源。', resolved: false },
          ] : [] } } })
    }
    if (/\/preview-reviews\/r\d+\/actions\/revise$/.test(path)) {
      const input = request.postDataJSON()
      expect(input.issueIds).toEqual(['E1', 'E2'])
      expect(input).not.toHaveProperty('content')
      revisions.push(input)
      return route.fulfill({ json: candidate(revised, input.provider) })
    }
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/editing-test')
  await page.getByRole('button', { name: '风格试写', exact: true }).click()
  await page.getByLabel('风格预设').selectOption(profile.name)
  await page.getByRole('button', { name: '试写第一章开头' }).click()
  return { checks, revisions, applications: () => applications }
}

test('checks a preview, revises selected evidence and rechecks while preserving the original', async ({ page }) => {
  const activity = await setup(page)
  const old = page.locator('.preview-result').first()
  await expect(old).toContainText('名单来源未交代')
  const revisionButton = old.getByRole('button', { name: '按选中建议生成修订样例' })
  await expect(revisionButton).toBeDisabled()
  await old.getByRole('checkbox', { name: '细节与场景 · 开门细节未利用' }).check()
  await old.getByRole('checkbox', { name: '逻辑与依据 · 名单来源未交代' }).check()
  await revisionButton.click()
  await expect(page.locator('.preview-result')).toHaveCount(2)
  await expect(page.locator('.preview-result').first()).toContainText('本次检查未提出修改建议。')
  await expect(page.locator('.preview-result').last().locator('.preview-body')).toHaveText(original)
  await expect(page.locator('.preview-result').first().locator('.preview-body')).toHaveText(revised)
  await expect(page.locator('.preview-result').last()).toContainText('本报告已尝试修订')
  expect(activity.checks).toEqual([original, revised])
  expect(activity.revisions).toHaveLength(1)
  expect(activity.applications()).toBe(0)
expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('a failed automatic check keeps the sample and only retries when requested', async ({ page }) => {
  const activity = await setup(page, true)
  await expect(page.locator('.preview-editor').getByRole('alert')).toBeVisible()
  await expect(page.locator('.preview-body')).toHaveText(original)
  expect(activity.checks).toEqual([original])
  expect(activity.revisions).toHaveLength(0)
  await page.getByRole('button', { name: '检查试写', exact: true }).click()
  await expect(page.locator('.preview-editor')).toContainText('名单来源未交代')
  expect(activity.checks).toEqual([original, original])
  expect(activity.applications()).toBe(0)
})

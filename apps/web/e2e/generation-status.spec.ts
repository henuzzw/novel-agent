import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('shows every stage, immediate request state, failure, and restored background state', async ({ page }) => {
  const models = createModelSettingsFixture()
  const intent = { premise: '校园纸条引发误会', genres: ['校园'], targetAudience: '青年读者',
    protagonistBrief: '林安想说清误会', centralConflict: '朋友拒绝听解释', tones: ['克制'], targetWords: 50000,
    mustHave: [], avoid: [], stylePreferences: [], version: 0 }
  const project = { id: 'status-project', name: '纸条与座位', entryMode: 'IDEA', currentCanonVersion: 0, version: 0, creativeIntent: intent }
  const startedAt = new Date(Date.now() - 120_000).toISOString()
  let runs = [
    { id: 'b', stage: 'IMPORT_REVERSE_BIBLE', status: 'SUCCEEDED', startedAt, completedAt: new Date().toISOString(), durationMs: 120000 },
    { id: 'o', stage: 'IMPORT_REVERSE_OUTLINE', status: 'RUNNING', startedAt, completedAt: null, durationMs: null },
    { id: 'c', stage: 'CHAPTER_CONTRACT', status: 'SUCCEEDED', startedAt, completedAt: new Date().toISOString(), durationMs: 1000 },
    { id: 'cr', stage: 'CHAPTER_CONTRACT_REVIEW', status: 'FAILED', startedAt, completedAt: new Date().toISOString(), durationMs: 1000, errorMessage: '合同审阅超时' },
    { id: 'm', stage: 'MANUSCRIPT', status: 'RUNNING', startedAt, completedAt: null, durationMs: null },
    { id: 'r', stage: 'CHAPTER_REVIEW', status: 'SUCCEEDED', startedAt, completedAt: new Date().toISOString(), durationMs: 1000 },
  ]
  let release: (() => Promise<void>) | undefined
  let generationCalls = 0
  let generationRequestId = ''
  let cancelled = false
  let stopRequestMethod = ''
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/story-directions/actions/generate')) {
      generationCalls++
      generationRequestId = route.request().headers()['x-generation-request-id'] ?? ''
      await new Promise<void>(resolve => {
        release = async () => {
          await route.fulfill({ status: cancelled ? 409 : 403,
            json: cancelled ? { code: 'GENERATION_CANCELLED', detail: '生成已停止' } : { detail: '测试权限不足' } })
          resolve()
        }
      })
      return
    }
    if (path.endsWith(`/generation-requests/${generationRequestId}/actions/stop`)) {
      stopRequestMethod = route.request().method()
      cancelled = true
      await release!()
      return route.fulfill({ json: { status: 'STOP_REQUESTED' } })
    }
    if (path.endsWith('/agent-runs/m/actions/stop')) {
      runs = runs.map(run => run.id === 'm' ? { ...run, status: 'CANCELLED', completedAt: new Date().toISOString() } : run)
      return route.fulfill({ json: { status: 'STOP_REQUESTED' } })
    }
    if (path.endsWith('/status-project') || path.endsWith('/creative-intent')) return route.fulfill({ json: project })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: runs })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/status-project')
  const panel = page.getByRole('region', { name: '生成状态' })
  await expect(panel.locator('[data-stage="STORY_BIBLE"]')).toContainText('成功')
  await expect(panel.locator('[data-stage="STORY_BIBLE"]')).toContainText('模型任务')
  await expect(panel.locator('[data-stage="OUTLINE"]')).toContainText('请求中')
  await expect(panel.locator('[data-stage="CHAPTER_CONTRACT_REVIEW"]')).toContainText('失败')
  await expect(panel.locator('[data-stage="MANUSCRIPT"]')).toContainText('请求中')
  await expect(panel.locator('[data-stage="CHAPTER_REVIEW"]')).toContainText('成功')
  await page.getByRole('button', { name: '生成故事方向', exact: true }).click()
  await expect(panel.locator('[data-stage="STORY_DIRECTION"]')).toContainText('请求中')
  await expect.poll(() => !!release).toBe(true)
  await page.getByRole('button', { name: '故事圣经', exact: true }).click()
  await expect(panel.locator('[data-stage="STORY_DIRECTION"]')).toContainText('请求中')
  await page.evaluate(() => scrollTo(0, 0))
  await expect.poll(() => page.locator('.topbar').evaluate(element => element.getBoundingClientRect().top)).toBe(0)
  await release!()
  await expect(panel.locator('[data-stage="STORY_DIRECTION"]')).toContainText('失败')
  await expect(panel.locator('[data-stage="STORY_DIRECTION"]')).toContainText('测试权限不足')
  await page.getByRole('button', { name: '故事方向', exact: true }).click()
  release = undefined
  await page.getByRole('button', { name: '生成故事方向', exact: true }).click()
  await expect.poll(() => !!release).toBe(true)
  expect(generationRequestId).toMatch(/^[0-9a-f-]{36}$/)
  await panel.getByRole('button', { name: '停止故事方向', exact: true }).click()
  await expect(panel.locator('[data-stage="STORY_DIRECTION"]')).toContainText('已停止')
  expect(stopRequestMethod).toBe('POST')
  await expect(panel.getByRole('button', { name: '停止故事方向', exact: true })).toHaveCount(0)
  runs = runs.map(run => run.stage === 'IMPORT_REVERSE_OUTLINE' ? { ...run, status: 'SUCCEEDED', completedAt: new Date().toISOString() } : run)
  await expect(panel.locator('[data-stage="OUTLINE"]')).toContainText('成功', { timeout: 8000 })
  await page.reload()
  await expect(panel.locator('[data-stage="OUTLINE"]')).toContainText('成功')
  await expect(panel.locator('[data-stage="MANUSCRIPT"]')).toContainText('请求中')
  await panel.getByRole('button', { name: '停止正文', exact: true }).click()
  await expect(panel.locator('[data-stage="MANUSCRIPT"]')).toContainText('已停止')
  expect(generationCalls).toBe(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

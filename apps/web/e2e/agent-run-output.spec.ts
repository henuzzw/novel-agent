import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('shows SSE response and timeout details without issuing another generation request', async ({ page }) => {
  const models = createModelSettingsFixture()
  const run = { id: 'run-1', stage: 'IMPORT_REVERSE_BIBLE', provider: 'LOCAL_CODEX', status: 'RUNNING',
    inputTokens: 4541, outputTokens: 0, tokenSource: 'ESTIMATED', estimatedCost: 0, durationMs: null,
    errorMessage: null, startedAt: new Date().toISOString(), completedAt: null }
  const initial = { id: 'run-1', status: 'RUNNING', responseText: '', truncated: false,
    errorType: null, errorCategory: null, errorDetail: null, durationMs: null }
  const failed = { ...initial, status: 'FAILED', responseText: '{"logline":"未完成响应',
    errorType: 'CodexAppServerException', errorCategory: 'TIMEOUT', errorDetail: '等待 Codex 完成生成超时（等待上限 600 秒）', durationMs: 602_782 }
  let generationRequests = 0
  let streams = 0
  let completed = false
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'POST') generationRequests++
    if (path.endsWith('/events')) {
      streams++
      completed = true
      return route.fulfill({ contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' },
        body: `event: output\ndata: ${JSON.stringify(initial)}\n\nevent: output\ndata: ${JSON.stringify(failed)}\n\n` })
    }
    if (path.endsWith('/response')) return route.fulfill({ json: initial })
    if (path.endsWith('/agent-runs/summary')) return route.fulfill({ json: { calls: 1, failures: completed ? 1 : 0, inputTokens: 4541, outputTokens: 0, estimatedCost: 0 } })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: [completed ? { ...run, status: 'FAILED', durationMs: failed.durationMs,
      errorMessage: '模型生成等待超时', completedAt: new Date().toISOString() } : run] })
    if (path.endsWith('/automation-runs')) return route.fulfill({ json: [] })
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '实时任务响应验证',
      entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await expect(page.getByText('导入反推故事圣经', { exact: true })).toBeVisible()
  await expect(page.locator('.output-error-detail')).toContainText('等待上限 600 秒')
  await expect(page.locator('.model-response')).toContainText('未完成响应')
  await expect(page.getByText('未完成响应，不作为有效规划或正文。', { exact: true })).toBeVisible()
  await expect(page.locator('.run-row > svg.failed')).toBeVisible()
  expect(streams).toBe(1)
  expect(generationRequests).toBe(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.locator('.run-output').scrollIntoViewIfNeeded()
})

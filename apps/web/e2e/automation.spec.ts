import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect } from '@playwright/test'
import type { AutomationRun } from '../src/api/automation'

test('creates, resumes and cancels a chapter-range task without overflowing', async ({ page }, testInfo) => {
  let run: AutomationRun | null = null
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async route => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/automation-runs') && request.method() === 'POST') {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      const input = request.postDataJSON()
      run = {
        id: 'run-1', projectId: 'test-project', outlineId: 'outline-1', firstChapter: input.firstChapter,
        qualityReviewEnabled: input.qualityReviewEnabled,
        maxAutoRevisionRounds: input.maxAutoRevisionRounds, maxGenerationSteps: input.maxGenerationSteps,
        usedGenerationSteps: 1, usedAutoRevisionRounds: 0,
        lastChapter: input.lastChapter, currentChapter: input.firstChapter, provider: input.provider,
        status: 'WAITING_FOR_USER', cancelRequested: false, attempt: 1,
        waitingReason: '请处理合同审阅问题、确认审阅并确认章节合同', errorCode: null,
        steps: [{ chapterNumber: 1, stage: 'CONTRACT', status: 'SUCCEEDED', artifactId: 'contract-1',
          startedAt: new Date().toISOString(), completedAt: new Date().toISOString(), errorCode: null }],
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      }
      return route.fulfill({ status: 202, json: run })
    }
    if (path.endsWith('/actions/resume') && run) {
      run.waitingReason = '请由作者确认正文'
      run.attempt++
      return route.fulfill({ status: 202, json: run })
    }
    if (path.endsWith('/actions/cancel') && run) {
      run.status = 'CANCELLED'
      run.cancelRequested = true
      return route.fulfill({ json: run })
    }
    if (path.endsWith('/automation-runs')) return route.fulfill({ json: run ? [run] : [] })
    if (path.endsWith('/agent-runs/summary')) return route.fulfill({ json: { calls: 0, failures: 0, inputTokens: 0, outputTokens: 0, estimatedCost: 0 } })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: [] })
    if (path.endsWith('/test-project')) return route.fulfill({ json: {
      id: 'test-project', name: '自动创作验证', entryMode: 'MATERIALS', status: 'ACTIVE',
      currentCanonVersion: 0, version: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), creativeIntent: null,
    } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await selectGlobalProvider(page, 'LOCAL_TEMPLATE')
  await page.getByRole('button', { name: '开始自动创作' }).click()
  await expect(page.getByText('请处理合同审阅问题、确认审阅并确认章节合同')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始自动创作' })).toBeDisabled()
  await page.getByRole('button', { name: '继续任务' }).click()
  await expect(page.getByText('请由作者确认正文')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('automation.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: '取消任务' }).click()
  await expect(page.getByText('已取消 · 当前第 1 章')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始自动创作' })).toBeEnabled()
})

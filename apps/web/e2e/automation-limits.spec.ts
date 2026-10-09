import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect } from '@playwright/test'
import type { AutomationRun } from '../src/api/automation'

test('keeps revision opt-in and generation limits when returning to a waiting task', async ({ page }) => {
  let run: AutomationRun | null = null
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async route => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/automation-runs') && request.method() === 'POST') {
      const input = request.postDataJSON()
      expect(input.maxAutoRevisionRounds).toBe(2)
      expect(input.maxGenerationSteps).toBe(12)
      expect(input.qualityReviewEnabled).toBe(true)
      expect(input.provider).toBe('DEEPSEEK')
      const now = new Date().toISOString()
      run = { id: 'r1', projectId: 'test-project', outlineId: 'o1', firstChapter: 1, lastChapter: 3, currentChapter: 1,
        provider: 'DEEPSEEK', qualityReviewEnabled: true, maxAutoRevisionRounds: 2, maxGenerationSteps: 12,
        usedGenerationSteps: 7, usedAutoRevisionRounds: 2, status: 'WAITING_FOR_USER', attempt: 1, cancelRequested: false,
        waitingReason: '已达到本章自动润色轮数上限，请由作者处理剩余建议并确认正文', errorCode: null,
        steps: [{ chapterNumber: 1, stage: 'QUALITY_REVISION', status: 'SUCCEEDED', artifactId: 'm2',
          startedAt: now, completedAt: now, errorCode: null }], createdAt: now, updatedAt: now }
      return route.fulfill({ status: 202, json: run })
    }
    if (path.endsWith('/actions/resume') && run) {
      run.attempt++
      return route.fulfill({ status: 202, json: run })
    }
    if (path.endsWith('/automation-runs')) return route.fulfill({ json: run ? [run] : [] })
    if (path.endsWith('/agent-runs/summary')) return route.fulfill({ json: { calls: 0, failures: 0, inputTokens: 0, outputTokens: 0, estimatedCost: 0 } })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: [] })
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '有限润色验证', entryMode: 'MATERIALS', status: 'ACTIVE', currentCanonVersion: 0, version: 0, creativeIntent: null } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '任务', exact: true }).click()
  const rounds = page.getByRole('combobox', { name: '自动语句润色', exact: true })
  await expect(rounds).toHaveValue('0')
  await rounds.selectOption('2')
  await page.getByRole('checkbox', { name: '正文质量检查' }).uncheck()
  await expect(rounds).toHaveValue('0')
  await expect(rounds).toBeDisabled()
  await page.getByRole('checkbox', { name: '正文质量检查' }).check()
  await selectGlobalProvider(page, 'LOCAL_TEMPLATE')
  await expect(rounds).toBeDisabled()
  await selectGlobalProvider(page, 'DEEPSEEK')
  await rounds.selectOption('2')
  await page.getByLabel('生成次数上限').fill('12')
  await page.getByRole('button', { name: '开始自动创作' }).click()
  await expect(page.getByText('生成额度已用 7 / 12 次', { exact: true })).toBeVisible()
  await expect(page.getByText('本章润色 2 / 2 轮', { exact: true })).toBeVisible()
  await expect(rounds).toBeDisabled()
  await page.getByRole('button', { name: '继续任务' }).click()
  await expect(page.getByText('生成额度已用 7 / 12 次', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '大纲', exact: true }).click()
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await expect(rounds).toHaveValue('2')
  await expect(page.getByLabel('生成次数上限')).toHaveValue('12')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

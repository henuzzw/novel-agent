import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect } from '@playwright/test'
import type { AutomationRun } from '../src/api/automation'

test('persists quality choice and opens the waiting chapter with its report', async ({ page }) => {
  let run: AutomationRun | null = null
  let creations = 0
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async route => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/automation-runs') && request.method() === 'POST') {
      const input = request.postDataJSON()
      expect(input.qualityReviewEnabled).toBe(creations === 0)
      expect(input.firstChapter).toBe(2)
      expect(input.lastChapter).toBe(2)
      expect(input.provider).toBe('LOCAL_TEMPLATE')
      creations++
      const now = new Date().toISOString()
      run = { id: `run-${creations}`, projectId: 'test-project', outlineId: 'o1', firstChapter: 2, lastChapter: 2, currentChapter: 2,
        provider: input.provider, qualityReviewEnabled: input.qualityReviewEnabled, status: 'WAITING_FOR_USER', cancelRequested: false,
        maxAutoRevisionRounds: input.maxAutoRevisionRounds, maxGenerationSteps: input.maxGenerationSteps,
        usedGenerationSteps: 1, usedAutoRevisionRounds: 0,
        attempt: 1, waitingReason: input.qualityReviewEnabled ? '质量检查提出 1 条建议，请选择是否润色，再由作者确认正文' : '请由作者确认正文',
        errorCode: null, steps: [{ chapterNumber: 2, stage: input.qualityReviewEnabled ? 'QUALITY_REVIEW' : 'MANUSCRIPT',
          status: 'SUCCEEDED', artifactId: 'q1', startedAt: now, completedAt: now, errorCode: null }], createdAt: now, updatedAt: now }
      return route.fulfill({ status: 202, json: run })
    }
    if (path.endsWith('/actions/cancel') && run) {
      run.status = 'CANCELLED'; run.cancelRequested = true
      return route.fulfill({ json: run })
    }
    if (path.endsWith('/automation-runs')) return route.fulfill({ json: run ? [run] : [] })
    if (path.endsWith('/agent-runs/summary')) return route.fulfill({ json: { calls: 0, failures: 0, inputTokens: 0, outputTokens: 0, estimatedCost: 0 } })
    if (path.endsWith('/agent-runs') || path.endsWith('/manuscripts')) return route.fulfill({ json: [] })
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '自动质量检查验证', entryMode: 'MATERIALS', status: 'ACTIVE', currentCanonVersion: 0, version: 0, creativeIntent: null } })
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: { id: 'o1', status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '开端' }, { number: 2, title: '纸条', objective: '寻找线索' }] }] } } })
    if (path.endsWith('/chapters/2/manuscripts/latest')) return route.fulfill({ json: { id: 'm2', sourceContractVersionId: null, chapterNumber: 2, versionNumber: 1, version: 0, status: 'DRAFT', baseManuscriptVersionId: null, sourceReviewVersionId: null, changeSummary: [], content: { title: '纸条', body: '然后他走到门口，接着看见纸条，随后停下。。', summary: '发现纸条', continuityNotes: [] } } })
    if (path.endsWith('/chapters/2/quality-reviews/latest')) return route.fulfill({ json: { id: 'q1', sourceManuscriptId: 'm2', sourceManuscriptRowVersion: 0, current: true, content: { summary: '第二章质量检查已保存', scores: ['STYLE', 'FLUENCY', 'LOGIC', 'SCENE'].map(dimension => ({ dimension, score: null, rationale: '本地规则不提供文学评分' })), issues: [{ id: 'Q1', severity: 'INFO', category: 'FLUENCY', description: '连续标点', evidence: '。。', suggestion: '复核标点', resolved: false }] } } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '正文质量检查' })).toBeChecked()
  await page.getByLabel('起始章').fill('2')
  await page.getByLabel('结束章').fill('2')
  await selectGlobalProvider(page, 'LOCAL_TEMPLATE')
  await page.getByRole('button', { name: '开始自动创作' }).click()
  await expect(page.getByText('含质量检查', { exact: true })).toBeVisible()
  await expect(page.getByRole('checkbox', { name: '正文质量检查' })).toBeDisabled()
  await page.getByRole('button', { name: '处理当前章节' }).click()
  await expect(page.locator('.chapter-workflow-heading')).toContainText('第 2 章写作流程')
  await page.locator('.chapter-flow').getByRole('button', { name: /检查与润色/ }).click()
  await expect(page.getByText('第二章质量检查已保存')).toBeVisible()
  await expect(page.locator('.global-model-badge').first()).toContainText('本地模板')
  await expect(page.getByRole('button', { name: '按建议生成润色稿' })).toBeDisabled()
  await page.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: 'instant' }))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '任务', exact: true }).click()
  await expect(page.getByLabel('起始章')).toHaveValue('2')
  await expect(page.getByLabel('结束章')).toHaveValue('2')
  await expect(page.locator('.global-model-badge').first()).toContainText('本地模板')
  await expect(page.getByRole('checkbox', { name: '正文质量检查' })).toBeChecked()
  await page.getByRole('button', { name: '取消任务' }).click()
  await expect(page.getByRole('button', { name: '开始自动创作' })).toBeEnabled()
  await page.getByRole('checkbox', { name: '正文质量检查' }).uncheck()
  await page.getByRole('button', { name: '开始自动创作' }).click()
  await expect(page.getByText('请由作者确认正文', { exact: true })).toBeVisible()
  await expect(page.getByText('含质量检查', { exact: true })).toHaveCount(0)
  await page.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: 'instant' }))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

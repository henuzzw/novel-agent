import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import type { AnalysisView } from '../src/api/importAnalyses'

for (const mode of ['ADAPT_SOURCE', 'CONTINUE_MANUSCRIPT'] as const) {
  test(`reviews evidence and confirms ${mode} before generating planning drafts`, async ({ page }, info) => {
    const models = createModelSettingsFixture()
    const chapters = [{ id: 'c1', ordinal: 1, title: '纸条', content: '林安把纸条夹进数学书。', characterCount: 12, contentType: 'MANUSCRIPT', selected: true }, { id: 'c2', ordinal: 2, title: '字迹', content: '林安发现纸条的字迹很熟悉。', characterCount: 13, contentType: 'MANUSCRIPT', selected: true }]
    const work = { id: 'import', projectId: 'test-project', originalFilename: '纸条.txt', mediaType: 'text/plain', sizeBytes: 80, sha256: '', parserVersion: '', detectedContentType: 'MANUSCRIPT', status: 'PARSED', planningStatus: 'NOT_STARTED', planningMode: null, generatedBibleVersionId: null, generatedOutlineVersionId: null, planningError: null, warnings: [], chapters, createdAt: '', confirmedAt: null }
    let report: AnalysisView | null = null, calls = 0
    const confirms: { authorConfirmed: boolean; mode: string; decisions: { key: string; action: string; note: string }[] }[] = []
    const planning: { analysisId: string; analysisVersion: number; mode: string }[] = []
    const versions: { expected: number; actual: number }[] = []
    await page.route('**/api/v1/**', async route => {
      if (await models(route)) return
      const path = new URL(route.request().url()).pathname, method = route.request().method()
      if (path.endsWith('/imports')) return route.fulfill({ json: [work] })
      if (path.endsWith('/analyses')) {
        if (method === 'GET') return route.fulfill({ json: report ? [report] : [] })
        report = { stale: false, report: { id: 'analysis', projectId: 'test-project', importId: 'import', provider: 'DEEPSEEK', sourceHash: '', slices: chapters.map(c => ({ chapterId: c.id, ordinal: c.ordinal, title: c.title, start: 0, end: c.content.length })), nextSlice: 0, status: 'READY', content: { summaries: [], items: [] }, decisions: [], confirmedMode: null, errorMessage: null, version: 0, updatedAt: '2026-10-05T00:00:00Z' } }
        return route.fulfill({ json: report })
      }
      if (path.includes('/analyses/analysis')) {
        if (path.endsWith('/actions/run-next')) {
          versions.push({ expected: route.request().postDataJSON().version, actual: report!.report.version }); calls++
          const c = chapters[report!.report.nextSlice]!
          report!.report.content.items.push({ key: `b${calls}_note`, category: calls === 1 ? 'EVENT' : 'FORESHADOW', certainty: calls === 1 ? 'FACT' : 'INFERENCE', title: calls === 1 ? '纸条动作' : '纸条主人', description: calls === 1 ? '纸条已经夹入书中' : '字迹可能是后续识别依据，作者意图尚不明确', subjects: ['林安'], progress: calls === 1 ? 'NOT_APPLICABLE' : 'UNRESOLVED', evidence: [{ chapterId: c.id, quote: c.content, occurrence: 0 }] })
          report!.report.content.summaries.push('仅分析当前原文片段'); report!.report.nextSlice++; report!.report.version += 2; report!.report.status = calls === 2 ? 'REVIEW' : 'READY'
        }
        if (path.endsWith('/actions/confirm')) { const input = route.request().postDataJSON(); confirms.push(input); report!.report.status = 'CONFIRMED'; report!.report.confirmedMode = input.mode; report!.report.decisions = input.decisions; report!.report.version++ }
        return route.fulfill({ json: report })
      }
      if (path.endsWith('/actions/reverse-plan')) { planning.push(route.request().postDataJSON()); return route.fulfill({ status: 400, json: { detail: '测试停在规划调用边界，不生成真实内容' } }) }
      if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '导入原文解析', entryMode: 'MANUSCRIPT', version: 0, currentCanonVersion: 0 } })
      if (path.endsWith('/writing-style/presets') || path.endsWith('/characters')) return route.fulfill({ json: [] })
      return route.fulfill({ status: 204 })
    })
    await page.goto('/projects/test-project')
    await page.getByRole('radio', { name: mode === 'CONTINUE_MANUSCRIPT' ? /作为已有正文续写/ : /作为故事素材改编/ }).check()
    await expect(page.getByRole('button', { name: '生成小说规划', exact: true })).toBeDisabled()
    await page.getByRole('button', { name: '解析原文', exact: true }).click()
    await expect(page.getByText('待审核 · 已覆盖 2 / 2 段 · 2 项')).toBeVisible()
    expect(calls).toBe(2); expect(confirms).toHaveLength(0); expect(planning).toHaveLength(0)
    await page.getByText('第 2 章 · 字迹 · 原文依据 1', { exact: true }).click()
    await expect(page.locator('blockquote').filter({ hasText: chapters[1]!.content })).toBeVisible()
    await page.getByLabel('纸条动作处理').selectOption('KEEP')
    await expect(page.getByLabel('纸条主人处理').locator('option[value=REWORK]')).toHaveCount(mode === 'ADAPT_SOURCE' ? 1 : 0)
    await page.getByLabel('纸条主人处理').selectOption(mode === 'ADAPT_SOURCE' ? 'REWORK' : 'KEEP')
    await page.getByLabel('纸条主人备注').fill(mode === 'ADAPT_SOURCE' ? '保留纸条但提前安排字迹辨认' : '')
    await expect(page.getByRole('button', { name: '确认解析报告', exact: true })).toBeDisabled()
    await page.getByLabel('确认原文解析与逐项处理').check(); await page.getByRole('button', { name: '确认解析报告', exact: true }).click()
    await expect(page.getByRole('button', { name: '生成小说规划', exact: true })).toBeEnabled()
    await expect(page.getByText('分析推测', { exact: true })).toBeVisible()
    expect(confirms).toHaveLength(1); expect(confirms[0]!.mode).toBe(mode); expect(confirms[0]!.authorConfirmed).toBe(true)
    expect(versions.every(v => v.expected === v.actual)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('import-analysis-confirmed.png'), fullPage: true })
    await page.getByRole('button', { name: '生成小说规划', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('测试停在规划调用边界')
    expect(planning).toEqual([{ provider: expect.any(String), instruction: null, mode, analysisId: 'analysis', analysisVersion: 5 }])
  })
}

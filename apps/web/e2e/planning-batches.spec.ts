import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('resumes a failed planning chunk and assembles a draft without publishing', async ({ page }, testInfo) => {
  const models = createModelSettingsFixture()
  const bible = { id: 'b1', projectId: 'test-project', status: 'PUBLISHED', version: 1, generationNumber: 1,
    content: { logline: '寻找失物', theme: '信任', worldSetting: '校园', protagonist: '林安',
      worldRules: [], supportingCharacters: [], relationshipDynamics: [], hardConstraints: [], openQuestions: [] } }
  const chapters = Array.from({ length: 4 }, (_, i) => ({ number: i + 1, title: `线索${i + 1}`, pov: '林安',
    objective: '核对线索', coreEvent: `行动${i + 1}`, reveal: '新的发现', endingHook: '继续调查',
    status: 'PLANNED', suggestedMinWords: 1000, suggestedMaxWords: 2000 }))
  const outline = { id: 'o1', projectId: 'test-project', status: 'PUBLISHED', version: 1, generationNumber: 1,
    sourceBibleVersionId: 'b1', baseOutlineVersionId: null, changeSummary: [],
    wordBudget: { targetWords: 6000, acceptableMinWords: 1000, acceptableMaxWords: 16000,
      recommendedVolumeCount: 1, recommendedChapterCount: 2, recommendedChapterWords: 3000,
      recommendedChapterMinWords: 2000, recommendedChapterMaxWords: 4000 },
    content: { title: '失物', premise: '寻找失物', structureSummary: '调查', pacingStrategy: '推进',
      suggestedMinWords: 4000, suggestedMaxWords: 8000, arcs: [{ ordinal: 1, title: '寻找', objective: '调查',
        mainConflict: '误会', turningPoint: '线索', outcome: '信任', suggestedMinWords: 4000, suggestedMaxWords: 8000, chapters }] } }
  const draft = { ...outline, id: 'o2', status: 'DRAFT', version: 0, generationNumber: 2 }
  type Chunk = { id: string; chapterFrom: number; chapterTo: number; status: string; attempt: number;
    version: number; failure: string | null; result: { arcs: typeof outline.content.arcs } | null }
  type Batch = { id: string; projectId: string; bibleId: string; bibleRowVersion: number; chapterTo: number;
    chunkSize: number; provider: string; instruction: string; version: number; status: string;
    outlineVersionId: string | null; checkpoints: Chunk[]; createdAt: string }
  let batch: Batch | null = null
  let generationCalls = 0
  let publishCalls = 0
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path.endsWith('/story-bibles/current') || path.endsWith('/story-bibles/latest')) return route.fulfill({ json: bible })
    if (path.endsWith('/outlines/current') || path.endsWith('/outlines/latest')) return route.fulfill({ json: outline })
    if (path.endsWith('/outlines')) return route.fulfill({ json: [{ ...outline, title: outline.content.title, chapterCount: 4 }] })
    if (path.endsWith('/planning-batches') && method === 'GET') return route.fulfill({ json: batch ? [batch] : [] })
    if (path.endsWith('/planning-batches') && method === 'POST') {
      const input = route.request().postDataJSON()
      expect(input).toMatchObject({ chapterTo: 4, chunkSize: 2, expectedBibleId: 'b1', expectedBibleVersion: 1 })
      expect(input.requestId).toBeTruthy()
      batch = { id: 'batch-1', projectId: 'test-project', bibleId: 'b1', bibleRowVersion: 1,
        chapterTo: 4, chunkSize: 2, provider: input.provider, instruction: input.instruction, version: 0,
        status: 'READY', outlineVersionId: null, checkpoints: [], createdAt: '2026-10-05T08:00:00Z' }
      return route.fulfill({ status: 201, json: batch })
    }
    if (path.endsWith('/planning-batches/batch-1')) return route.fulfill({ json: batch })
    if (path.endsWith('/actions/run-next') && batch) {
      expect(route.request().postDataJSON().version).toBe(batch.version)
      generationCalls++
      if (generationCalls === 2) {
        batch = { ...batch, status: 'FAILED', version: batch.version + 2, checkpoints: [batch.checkpoints[0]!,
          { id: 'c2', chapterFrom: 3, chapterTo: 4, status: 'FAILED', attempt: 1, version: 2,
            result: null, failure: '模拟供应商失败' }] }
        return route.fulfill({ status: 502, json: { title: '模型服务暂时不可用', detail: '模拟供应商失败' } })
      }
      const from = generationCalls === 1 ? 1 : 3
      const complete = { id: from === 1 ? 'c1' : 'c2', chapterFrom: from, chapterTo: from + 1,
        status: 'SUCCEEDED', attempt: generationCalls === 1 ? 1 : 2, version: 3, failure: null,
        result: { arcs: [{ ...outline.content.arcs[0]!, chapters: chapters.slice(from - 1, from + 1) }] } }
      batch = { ...batch, status: 'READY', version: batch.version + 2,
        checkpoints: from === 1 ? [complete] : [batch.checkpoints[0]!, complete] }
      return route.fulfill({ json: batch })
    }
    if (path.endsWith('/actions/resume') && batch) {
      batch = { ...batch, status: 'READY', version: batch.version + 1,
        checkpoints: batch.checkpoints.map(c => c.status === 'FAILED' ? { ...c, status: 'PENDING' } : c) }
      return route.fulfill({ json: batch })
    }
    if (path.endsWith('/actions/assemble') && batch) {
      batch = { ...batch, status: 'SUCCEEDED', version: batch.version + 1, outlineVersionId: 'o2' }
      return route.fulfill({ status: 201, json: draft })
    }
    if (path.endsWith('/actions/publish')) { publishCalls++; return route.fulfill({ json: draft }) }
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '规划恢复验证',
      entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '分层大纲', exact: true }).click()
  const panel = page.getByRole('region', { name: '分块规划' })
  await panel.getByLabel('规划至第几章', { exact: true }).fill('4')
  await panel.getByLabel('每块章节数', { exact: true }).fill('2')
  await panel.getByRole('button', { name: '创建规划批次', exact: true }).click()
  await expect(panel).toContainText('已完成 0 / 2 块')
  await panel.getByRole('button', { name: '生成下一块', exact: true }).click()
  await expect(panel).toContainText('已完成 1 / 2 块')
  await expect(panel.getByRole('button', { name: '拼装为大纲草稿', exact: true })).toBeDisabled()
  expect(generationCalls).toBe(1)
  await panel.getByRole('button', { name: '生成下一块', exact: true }).click()
  await expect(panel).toContainText('模拟供应商失败')
  await expect(panel.getByRole('button', { name: '恢复批次', exact: true })).toBeEnabled()
  expect(generationCalls).toBe(2)
  await panel.getByRole('button', { name: '恢复批次', exact: true }).click()
  await expect(panel.getByRole('button', { name: '生成下一块', exact: true })).toBeEnabled()
  expect(generationCalls).toBe(2)
  await panel.getByRole('button', { name: '生成下一块', exact: true }).click()
  await expect(panel).toContainText('已完成 2 / 2 块')
  await panel.getByRole('button', { name: '拼装为大纲草稿', exact: true }).click()
  await expect(panel).toContainText('已拼装草稿')
  await expect(page.getByText('最新生成：第 2 版 · 草稿')).toBeVisible()
  expect(generationCalls).toBe(3)
  expect(publishCalls).toBe(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('planning-batches.png'), fullPage: true })
})

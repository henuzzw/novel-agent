import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('memory evidence uses the full row on desktop and narrow screens without writes', async ({ page }) => {
  const models = createModelSettingsFixture()
  const projectId = 'memory-layout'
  const evidence = '重新选座以后，我在第二排中间，许宁和周悦坐在两侧。\n我没有替她们回答。'
  const fact = { id: 'position', factType: 'STATE_CHANGE', subject: '陈屿', predicate: '重新选座后位于',
    object: '第二排中间', evidence, confidence: null, payload: null, decision: 'ACCEPTED' }
  let writes = 0
  await page.route('**/api/v1/**', async route => {
    if (route.request().method() !== 'GET') { writes++; return route.fulfill({ status: 400 }) }
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path === `/api/v1/projects/${projectId}`) return route.fulfill({ json: { id: projectId, name: '这句话我自己说', entryMode: 'MATERIALS', currentCanonVersion: 1, version: 0 } })
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: {
      id: 'outline', projectId, status: 'PUBLISHED', version: 0,
      content: { arcs: [{ ordinal: 1, title: '第一阶段', chapters: [{ number: 1, title: '这句话我自己说', status: 'PLANNED', content: '主动说明自己的选择。' }] }] },
    } })
    if (path.endsWith('/manuscripts/latest')) return route.fulfill({ json: {
      id: 'm1', projectId, chapterNumber: 1, versionNumber: 1, version: 0, status: 'AUTHOR_ACCEPTED', generatorType: 'LOCAL_CODEX',
      sourceContractVersionId: null, baseManuscriptVersionId: null, sourceReviewVersionId: null, schemaVersion: 'manuscript/1',
      authorInstruction: null, changeSummary: [], createdAt: '2026-10-12T08:00:00Z', updatedAt: '2026-10-12T08:00:00Z',
      content: { title: '这句话我自己说', body: evidence, summary: '确认座位', continuityNotes: [] },
    } })
    if (path.endsWith('/canon-commits/status')) return route.fulfill({ json: { committed: true, activeManuscriptVersionId: 'm1' } })
    if (path.endsWith('/canon-commits/memory')) return route.fulfill({ json: {
      commitId: 'commit', manuscriptVersionId: 'm1', status: 'NEEDS_CONFIRMATION', version: 3, error: null,
      candidates: [fact, { ...fact, id: 'ambiguous', subject: '她', decision: 'PENDING',
        payload: { entityId: null, entityName: '她', stateEntityType: 'CHARACTER' } }],
    } })
    if (['/canon/entities', '/manuscripts', '/agent-runs', '/draft-loops'].some(suffix => path.endsWith(suffix))) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/projects/${projectId}?section=writing&writing=review&chapter=1`)
  const panel = page.getByRole('region', { name: '发布后记忆整理', exact: true })
  await expect(panel.locator('.memory-item')).toHaveCount(2)
  await expect(panel.getByLabel('她记忆处理')).toHaveValue('ACCEPTED')
  await expect(panel.locator('blockquote').first()).toHaveText(evidence)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const item = panel.locator('.memory-item').first()
    const bounds = await item.boundingBox()
    const quote = await item.locator('blockquote').boundingBox()
    const object = await item.locator('p').boundingBox()
    expect(quote!.width).toBeGreaterThan(bounds!.width * 0.8)
    expect(quote!.y).toBeGreaterThanOrEqual(object!.y + object!.height)
    expect(bounds!.height).toBeLessThan(300)
    for (const select of await panel.locator('select').all()) {
      const box = await select.boundingBox()
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/published-memory-layout.png', fullPage: true })
  expect(writes).toBe(0)
})

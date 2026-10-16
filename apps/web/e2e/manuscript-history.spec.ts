import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('compares saved drafts read-only, retains route choices and fits narrow screens', async ({ page }) => {
  const models = createModelSettingsFixture()
  const context = Array.from({ length: 12 }, (_, index) => `第${index + 1}段：走廊里的人还在等他回答。`)
  const body = ['她收下纸条。', ...context, '他没有解释。'].join('\n')
  const versions = [3, 2, 1].map(number => ({
    id: `m${number}`, projectId: 'history-ux', chapterNumber: 1, versionNumber: number, version: 0,
    status: number === 1 ? 'AUTHOR_ACCEPTED' : 'DRAFT', generatorType: 'LOCAL_EDIT_LOCAL_CODEX',
    sourceContractVersionId: null, baseManuscriptVersionId: number === 3 ? 'm1' : null, sourceReviewVersionId: null,
    schemaVersion: 'manuscript/1', authorInstruction: '把选择落实到行动', changeSummary: ['退回纸条，补上解释'],
    createdAt: '2026-10-12T08:00:00Z', updatedAt: '2026-10-12T08:00:00Z',
    content: { title: '未递出的纸条', body: number === 1 ? body : body.replace('收下', '退回').replace('没有解释', '主动解释'), summary: `摘要${number}`, continuityNotes: [] },
  }))
  let writes = 0
  await page.route('**/api/v1/**', async route => {
    if (route.request().method() !== 'GET') { writes++; return route.fulfill({ status: 400 }) }
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/projects/history-ux') return route.fulfill({ json: { id: 'history-ux', name: '未递出的纸条', entryMode: 'MATERIALS', currentCanonVersion: 1, version: 0 } })
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: {
      id: 'outline', projectId: 'history-ux', status: 'PUBLISHED', version: 0,
      content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '未递出的纸条', status: 'PLANNED', content: '面对误解，主动说明自己的选择。' }] }] },
    } })
    if (path.endsWith('/manuscripts')) return route.fulfill({ json: versions.map(v => ({ ...v, title: v.content.title, bodyLength: v.content.body.length })) })
    if (path.endsWith('/manuscripts/latest')) return route.fulfill({ json: versions[0] })
    const id = path.match(/\/manuscripts\/(m\d)$/)?.[1]
    if (id) return route.fulfill({ json: versions.find(v => v.id === id) })
    if (path.endsWith('/canon-commits/status')) return route.fulfill({ json: { committed: true, activeManuscriptVersionId: 'm1' } })
    if (path.endsWith('/agent-runs') || path.endsWith('/draft-loops')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/projects/history-ux?section=writing&writing=history&chapter=1')
  const panel = page.getByRole('region', { name: '正文版本对比', exact: true })
  await expect(panel.getByLabel('基准版本')).toHaveValue('m1')
  await expect(panel.getByLabel('对比版本')).toHaveValue('m3')
  await expect(panel.locator('.version-metadata')).toContainText('当前已发布')
  await expect(panel.locator('.changed').first()).toHaveText('收下')
  await expect(panel.locator('.diff-count')).toContainText('2 处变化')
  await panel.getByRole('button', { name: '下一处差异', exact: true }).click()
  await expect(panel.locator('.jump-tools')).toContainText('2 / 2')
  await expect(panel.getByRole('button', { name: /展开 \d+ 行未变内容/ })).toBeVisible()
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/manuscript-history.png', fullPage: true })
  await panel.getByLabel('基准版本').selectOption('m2')
  await expect(panel.getByText('两版内容一致', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '交换两个版本', exact: true }).click()
  await panel.getByRole('navigation', { name: '对比内容' }).getByRole('button', { name: '摘要' }).click()
  await panel.getByRole('button', { name: '合并', exact: true }).click()
  await expect(page).toHaveURL(/compareFrom=m3/)
  await expect(page).toHaveURL(/compareTo=m2/)
  await expect(page).toHaveURL(/compareField=summary/)
  await page.reload()
  await expect(panel.getByLabel('基准版本')).toHaveValue('m3')
  await expect(panel.getByLabel('对比版本')).toHaveValue('m2')
  await expect(panel.locator('.diff-surface.unified')).toBeVisible()
  await expect(panel.locator('.line-text').first()).toContainText('摘要3')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  for (const selector of ['基准版本', '对比版本']) {
    const box = await panel.getByLabel(selector).boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  }
  expect(writes).toBe(0)
})

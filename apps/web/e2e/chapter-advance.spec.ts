import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('chapter advance exposes independent switches without model calls', async ({ page }) => {
  const models = createModelSettingsFixture()
  const projectId = 'advance-layout'
  let writes = 0
  await page.route('**/api/v1/**', async route => {
    if (route.request().method() !== 'GET') { writes++; return route.fulfill({ status: 400 }) }
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path === `/api/v1/projects/${projectId}`) return route.fulfill({ json: {
      id: projectId, name: '章节推进', entryMode: 'MATERIALS', currentCanonVersion: 0, version: 0,
    } })
    if (['/automation-runs', '/agent-runs'].some(suffix => path.endsWith(suffix))) return route.fulfill({ json: [] })
    if (path.endsWith('/agent-runs/summary')) return route.fulfill({ json: {
      calls: 0, inputTokens: 0, outputTokens: 0, estimatedCost: 0, failures: 0,
    } })
    return route.fulfill({ status: 204 })
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/projects/${projectId}?section=runs`)
  const panel = page.getByRole('region', { name: '自动创作任务', exact: true })
  await expect(panel.getByLabel('自动发布', { exact: true })).not.toBeChecked()
  await expect(panel.getByLabel('自动整理记忆', { exact: true })).toBeChecked()
  await panel.getByLabel('自动发布', { exact: true }).check()
  await panel.getByLabel('自动整理记忆', { exact: true }).uncheck()
  await expect(panel.getByLabel('每章检查轮次上限')).toHaveValue('3')
  await expect(panel.getByRole('button', { name: '开始自动创作' })).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/chapter-advance.png', fullPage: true })
  expect(writes).toBe(0)
})

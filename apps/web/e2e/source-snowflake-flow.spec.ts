import { expect, test, type Page } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

async function fixture(page: Page) {
  const models = createModelSettingsFixture()
  let createdBody = ''
  let title = '待生成书名'
  let report: Record<string, unknown> | null = null
  const imported = { id: 'import-1', projectId: 'source-test', originalFilename: '粘贴故事.txt', mediaType: 'text/plain',
    status: 'PARSED', planningStatus: 'NOT_STARTED', planningMode: null, planningError: null, warnings: [], sizeBytes: 80,
    chapters: [{ id: 'chapter-1', ordinal: 1, title: '故事', content: '她递还纸条，他第一次没能用笑话带过去。', characterCount: 23, selected: true }] }
  const project = () => ({ id: 'source-test', name: title, entryMode: 'MATERIALS', creativeStrategy: 'FANQIE_GRIPPING',
    creativeIntent: null, currentCanonVersion: 0, version: 0 })
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const req = route.request(), path = new URL(req.url()).pathname
    if (path.endsWith('/projects/from-story')) {
      createdBody = req.postData() ?? ''
      return route.fulfill({ status: 201, json: project() })
    }
    if (/\/analyses\/[^/]+\/actions\/run-next$/.test(path)) {
      title = '全班都知道她喜欢我'
      report = { ...report, status: 'REVIEW', version: 2, nextSlice: 1, content: { summaries: ['片段中只有关系互动。'], items: [] } }
      return route.fulfill({ json: { report, stale: false } })
    }
    if (path.endsWith('/analyses') && req.method() === 'POST') {
      report = { id: 'analysis-1', provider: 'LOCAL_CODEX', status: 'READY', version: 0, nextSlice: 0,
        slices: [{ chapterId: 'chapter-1', start: 0, end: 23 }], content: { summaries: [], items: [] }, decisions: [] }
      return route.fulfill({ status: 201, json: { report, stale: false } })
    }
    if (path.endsWith('/analyses')) return route.fulfill({ json: report ? [{ report, stale: false }] : [] })
    if (/\/projects\/source-test$/.test(path)) return route.fulfill({ json: project() })
    if (path.endsWith('/imports')) return route.fulfill({ json: [imported] })
    if (path.endsWith('/snowflake-plans/latest')) return route.fulfill({ json: { id: 'snowflake-1', projectId: 'source-test',
      status: 'SUCCEEDED', activeStage: 'SCENE_LIST', steps: { CORE: '他必须澄清误会，否则失去信任。', SYNOPSIS: '从误会走向承担。',
        CHARACTER_SETTINGS: '许言川习惯用笑话回避选择。', SCENE_LIST: '第一章：纸条递还，引发选择。' } } })
    if (/\/(agent-runs|generation-requests|projects)$/.test(path)) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  return { get body() { return createdBody } }
}

test('creates from text with optional name, then automatically analyzes in import', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/projects/new')
  await page.getByRole('radio', { name: '粘贴故事文字' }).check()
  await page.getByLabel('故事文字', { exact: true }).fill('她递还纸条，他第一次没能用笑话带过去。')
  await page.getByRole('button', { name: '创建并解析' }).click()
  await expect(page).toHaveURL(/section=imports/)
  await expect(page.locator('.analysis-status')).toContainText('已覆盖 1 / 1 段')
  await page.locator('.analysis-summary > summary').click()
  await expect(page.locator('.analysis-summary')).toContainText('片段中只有关系互动。')
  expect(state.body).toContain('她递还纸条')
})

test('eight primary menu items retain their route and snowflake prose after reload', async ({ page }) => {
  await fixture(page)
  await page.goto('/projects/source-test')
  const nav = page.getByRole('complementary', { name: '项目导航' })
  await expect(nav.locator('button')).toHaveText(['导入','故事方向','圣经','大纲','资料','伏笔和承诺','任务','设置'])
  await expect(page.getByRole('heading', { name: '导入与章节识别' })).toBeVisible()
  await expect(page.getByText('创作准备', { exact: true })).toHaveCount(0)
  await page.getByText('1 · 一句话故事核心', { exact: true }).click()
  await expect(page.getByText('他必须澄清误会，否则失去信任。', { exact: true })).toBeVisible()
  await nav.getByRole('button', { name: '任务', exact: true }).click()
  await expect(page).toHaveURL(/section=runs/)
  await page.reload()
  await expect(nav.getByRole('button', { name: '任务', exact: true })).toHaveAttribute('aria-current', 'page')
  await nav.getByRole('button', { name: '导入', exact: true }).click()
  await expect(page).toHaveURL(/section=imports/)
})

test('source form remains readable on narrow screens', async ({ page }) => {
  await fixture(page)
  for (const width of [320]) {
    await page.setViewportSize({ width, height: 850 })
    await page.goto('/projects/new')
    await page.getByRole('radio', { name: '粘贴故事文字' }).check()
    await page.getByLabel('故事文字', { exact: true }).fill('她递还纸条，他第一次没能用笑话带过去。')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await expect(page.getByRole('button', { name: '创建并解析' })).toBeVisible()
}
})

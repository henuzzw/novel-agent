import { test, expect } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('creates a planned promise without inventing actual progress', async ({ page }) => {
  const models = createModelSettingsFixture()
  const entries: object[] = []
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/reader-experiences/sources')) return route.fulfill({ json: [] })
    if (path.endsWith('/reader-experiences/memory')) return route.fulfill({ json: {
      schemaVersion: 'reader-experience-memory/1', mode: 'EXISTING_CANON_SUMMARIES', outlineId: null,
      outlineRowVersion: null, arcs: [], unassignedChapters: [],
    } })
    if (path.endsWith('/reader-experiences')) {
      if (route.request().method() === 'POST') {
        const input = route.request().postDataJSON()
        expect(input.requestId).toBeTruthy()
        expect(input.expectedVersion).toBeNull()
        const entry = { plan: { ...input, id: 'plan-1', projectId: 'test-project', version: 0 },
          state: 'PLANNED', stale: false, history: [] }
        entries.push(entry)
        return route.fulfill({ status: 201, json: entry })
      }
      return route.fulfill({ json: entries })
    }
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '台账验证', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '伏笔与承诺', exact: true }).click()
  await expect(page.getByText('暂无承诺或伏笔记录')).toBeVisible()
  await page.getByRole('button', { name: '新增计划', exact: true }).click()
  const editor = page.getByRole('form', { name: '计划编辑' })
  await editor.getByLabel('标题', { exact: true }).fill('纸条上的约定')
  await editor.getByLabel('承诺', { exact: true }).fill('纸条的来源将在第三章解释。')
  await editor.getByLabel('计划兑现章', { exact: true }).fill('3')
  await editor.getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(page.locator('.entry-list')).toContainText('计划中')
  await expect(page.locator('.entry-list')).toContainText('纸条上的约定')
  expect(entries).toHaveLength(1)
  await expect(page.locator('.history')).not.toContainText('已兑现')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('local editing adopts a new draft and keeps the exact segment diff', async ({ page }) => {
  const models = createModelSettingsFixture()
  const body = '门口传来脚步声。她把纸条递过来。我等她说完。'
  let manuscript = { id: 'm1', projectId: 'test-project', chapterNumber: 1, version: 0, versionNumber: 1,
    status: 'DRAFT', sourceContractVersionId: null, baseManuscriptVersionId: null as string | null,
    sourceReviewVersionId: null, changeSummary: [] as string[], content: { title: '纸条', body, summary: '交接纸条', continuityNotes: [] } }
  let calls = 0
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/outlines/current')) return route.fulfill({ json: { id: 'o1', status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '纸条', objective: '交接' }] }] } } })
    if (path.endsWith('/manuscripts/latest')) return route.fulfill({ json: manuscript })
    if (path.endsWith('/manuscripts') || path.endsWith('/entities')) return route.fulfill({ json: [] })
    if (path.endsWith('/manuscripts/actions/local-edit')) {
      const input = route.request().postDataJSON()
      expect(input.sourceManuscriptId).toBe('m1')
      expect(input.selection).toBe('她把纸条递过来。')
      expect(input.offset).toBe(body.indexOf(input.selection))
      expect(input.authorized).toBe(true)
      calls++
      manuscript = { ...manuscript, id: 'm2', versionNumber: 2, baseManuscriptVersionId: 'm1',
        changeSummary: ['局部修改'], content: { ...manuscript.content, body: body.replace(input.selection, '她递来纸条。') } }
      return route.fulfill({ status: 201, json: { assessment: 'DRAFT_CREATED', message: '局部编辑草稿已保存',
        selection: input.selection, replacement: '她递来纸条。', manuscript } })
    }
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '局部编辑验证', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '写作', exact: true }).click()
  await page.getByRole('button', { name: '正文草稿', exact: true }).click()
  await page.locator('.local-edit-entry > summary').click()
  const panel = page.getByRole('region', { name: '正文局部编辑' })
  await panel.getByLabel('精确选区', { exact: true }).fill('她把纸条递过来。')
  await panel.getByLabel('修改要求', { exact: true }).fill('更简洁，保持交接事实。')
  await panel.getByLabel('授权局部编辑并创建新草稿', { exact: true }).check()
  await panel.getByRole('button', { name: '生成局部编辑草稿', exact: true }).click()
  await expect(page.locator('.editor-status')).toContainText('第 2 版')
  await expect(panel.locator('del')).toHaveText('她把纸条递过来。')
  await expect(panel.locator('ins')).toHaveText('她递来纸条。')
  await expect(panel.getByLabel('原稿正文')).toHaveValue('门口传来脚步声。她递来纸条。我等她说完。')
  expect(calls).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

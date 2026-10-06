import { test, expect, type Page, type TestInfo } from '@playwright/test'
import type { CreativeStrategySettings, CreateProjectInput, UpdateCreativeStrategyInput } from '../src/api/projects'
import { createModelSettingsFixture } from './model-settings-fixture'

async function fixture(page: Page, options: { readFailures?: number; conflict?: boolean; forbidden?: boolean; holdSave?: boolean } = {}) {
  const models = createModelSettingsFixture()
  const settings: Record<string, CreativeStrategySettings> = {
    'project-a': { strategy: 'STANDARD', policyVersion: 1, version: 7 },
    'project-b': { strategy: 'STANDARD', policyVersion: 1, version: 20 },
  }
  const saves: Array<{ projectId: string; input: UpdateCreativeStrategyInput }> = []
  const creates: CreateProjectInput[] = []
  const unrelatedWrites: string[] = []
  const failures: string[] = []
  let readFailures = options.readFailures ?? 0
  let conflict = options.conflict ?? false
  let forbidden = options.forbidden ?? false
  let releaseSave: (() => void) | undefined
  page.on('pageerror', error => failures.push(error.message))
  function project(id: string) {
    return { id, name: id === 'project-a' ? '旧项目' : '另一个项目', entryMode: 'IDEA', status: 'ACTIVE', version: settings[id]!.version,
      currentCanonVersion: 0, creativeIntent: null, createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z' }
  }
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET' && !path.endsWith('/creative-strategy') && path !== '/api/v1/projects') unrelatedWrites.push(path)
    if (await models(route)) return
    const strategyMatch = path.match(/\/projects\/([^/]+)\/settings\/creative-strategy$/)
    if (strategyMatch) {
      const id = strategyMatch[1]!
      if (request.method() === 'GET') {
        if (readFailures-- > 0) return route.fulfill({ status: 503, json: { detail: '创作策略读取失败' } })
        return route.fulfill({ json: settings[id] })
      }
      const input = request.postDataJSON() as UpdateCreativeStrategyInput
      saves.push({ projectId: id, input })
      if (options.holdSave && id === 'project-a') await new Promise<void>(resolve => { releaseSave = resolve })
      if (forbidden) {
        forbidden = false
        return route.fulfill({ status: 403, json: { detail: '无权修改项目创作策略' } })
      }
      if (conflict) {
        conflict = false
        settings[id] = { ...settings[id]!, version: settings[id]!.version + 2 }
        return route.fulfill({ status: 409, json: { detail: '项目版本冲突' } })
      }
      if (input.version !== settings[id]!.version) return route.fulfill({ status: 409, json: { detail: '项目版本冲突' } })
      settings[id] = { strategy: input.strategy, policyVersion: 1, version: input.version + 1 }
      return route.fulfill({ json: settings[id] })
    }
    if (path === '/api/v1/projects') {
      if (request.method() === 'POST') {
        const input = request.postDataJSON() as CreateProjectInput
        creates.push(input)
        settings['created'] = { strategy: input.creativeStrategy ?? 'STANDARD', policyVersion: 1, version: 0 }
        return route.fulfill({ status: 201, json: { ...project('created'), ...input } })
      }
      return route.fulfill({ json: [project('project-a'), project('project-b')] })
    }
    if (/\/projects\/[^/]+$/.test(path)) {
      const id = path.split('/').pop()!
      // Older project responses need not contain the new summary field.
      return route.fulfill({ json: project(id) })
    }
    return route.fulfill({ json: path.endsWith('/latest') || path.endsWith('/current') ? null : [] })
  })
  return { settings, saves, creates, unrelatedWrites, failures, releaseSave: () => releaseSave?.() }
}

async function openSettings(page: Page, id = 'project-a') {
  await page.goto(`/projects/${id}`)
  await page.getByRole('button', { name: '设置', exact: true }).click()
  return page.getByRole('region', { name: '项目创作策略' })
}

async function screenshotAndLayout(page: Page, testInfo: TestInfo, name: string) {
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const controls = page.getByRole('radiogroup', { name: '创作策略' })
  const boxes = await controls.locator('label').evaluateAll(labels => labels.map(label => {
    const rect = label.getBoundingClientRect()
    const text = label.querySelector('span')!.getBoundingClientRect()
    return { x: rect.x, right: rect.right, y: rect.y, bottom: rect.bottom, textX: text.x, textRight: text.right, textY: text.y, textBottom: text.bottom }
  }))
  for (const box of boxes) {
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.right).toBeLessThanOrEqual(page.viewportSize()!.width)
    expect(box.textX).toBeGreaterThanOrEqual(box.x)
    expect(box.textRight).toBeLessThanOrEqual(box.right)
    expect(box.textY).toBeGreaterThanOrEqual(box.y)
    expect(box.textBottom).toBeLessThanOrEqual(box.bottom)
  }
  const [first, second] = boxes
  expect(first!.right <= second!.x || first!.bottom <= second!.y).toBe(true)
}

for (const [strategy, label] of [['STANDARD', '标准创作'], ['FANQIE_GRIPPING', '番茄强开篇']] as const) {
  test(`creates an IDEA project with explicit ${strategy}`, async ({ page }, testInfo) => {
    const state = await fixture(page)
    await page.goto('/projects/new')
    await expect(page.getByRole('radio', { name: '标准创作' })).toBeChecked()
    await page.getByRole('radio', { name: label }).check()
    await page.getByLabel('项目名称', { exact: true }).fill('开篇策略测试')
    await page.getByLabel('一句话创意', { exact: true }).fill('少年在新学期寻找一封遗失的信')
    await page.getByLabel('主角简述', { exact: true }).fill('少年想澄清同学之间的误会')
    await page.getByLabel('核心冲突', { exact: true }).fill('每个人给出的线索都不一样')
    await screenshotAndLayout(page, testInfo, `create-${strategy.toLowerCase()}`)
    await page.getByRole('button', { name: '创建项目', exact: true }).click()
    await expect(page).toHaveURL(/\/projects\/created$/)
    expect(state.creates).toHaveLength(1)
    expect(state.creates[0]).toMatchObject({ creativeStrategy: strategy, creativeIntent: { stylePreferences: [] } })
    expect(state.unrelatedWrites).toEqual([])
    expect(state.failures).toEqual([])
  })
}

test('saves independently of global models and styles, persists on reload and isolates projects', async ({ page }, testInfo) => {
  const state = await fixture(page)
  let panel = await openSettings(page)
  await expect(panel.getByRole('radio', { name: '标准创作' })).toBeChecked()
  await expect(page.getByRole('heading', { name: '全局模型设置' })).toBeVisible()
  await expect(panel.getByRole('button', { name: '保存创作策略' })).toBeDisabled()
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('status')).toContainText('已保存')
  expect(state.saves).toEqual([{ projectId: 'project-a', input: { strategy: 'FANQIE_GRIPPING', version: 7 } }])
  await expect(page.getByLabel('ChatGPT 模型', { exact: true })).toHaveValue('gpt-6.1-sol')
  await screenshotAndLayout(page, testInfo, 'settings-saved')
  await page.reload()
  await page.getByRole('button', { name: '设置', exact: true }).click()
  panel = page.getByRole('region', { name: '项目创作策略' })
  await expect(panel.getByRole('radio', { name: '番茄强开篇' })).toBeChecked()
  panel = await openSettings(page, 'project-b')
  await expect(panel.getByRole('radio', { name: '标准创作' })).toBeChecked()
  expect(state.settings['project-b']!.strategy).toBe('STANDARD')
  expect(state.unrelatedWrites).toEqual([])
  expect(state.failures).toEqual([])
})

test('retries a failed read and recovers from 409 using a freshly read row version', async ({ page }, testInfo) => {
  const state = await fixture(page, { readFailures: 1, conflict: true })
  const panel = await openSettings(page)
  await expect(panel.getByRole('alert')).toHaveText('创作策略读取失败')
  await expect(panel.getByRole('radio')).toHaveCount(0)
  await panel.getByRole('button', { name: '重试读取创作策略' }).click()
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('alert')).toContainText('重新读取后再保存')
  await expect(panel.getByRole('button', { name: '保存创作策略' })).toBeDisabled()
  await screenshotAndLayout(page, testInfo, 'settings-conflict')
  await panel.getByRole('button', { name: '重新读取创作策略', exact: true }).click()
  await expect(panel.getByRole('radio', { name: '标准创作' })).toBeChecked()
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('status')).toContainText('已保存')
  expect(state.saves.map(save => save.input.version)).toEqual([7, 9])
  expect(state.failures).toEqual([])
})

test('preserves an unsaved selection after permission failure', async ({ page }) => {
  const state = await fixture(page, { forbidden: true })
  const panel = await openSettings(page)
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('alert')).toHaveText('无权修改项目创作策略')
  await expect(panel.getByRole('radio', { name: '番茄强开篇' })).toBeChecked()
  await expect(panel.getByRole('button', { name: '保存创作策略' })).toBeEnabled()
  expect(state.settings['project-a']!.strategy).toBe('STANDARD')
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('status')).toContainText('已保存')
  expect(state.failures).toEqual([])
})

test('a delayed save cannot overwrite another project after SPA navigation', async ({ page }, testInfo) => {
  const state = await fixture(page, { holdSave: true })
  let panel = await openSettings(page)
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('button', { name: '正在保存' })).toBeDisabled()
  await expect.poll(() => state.saves.length).toBe(1)
  await page.getByRole('link', { name: '小说 Agent 项目列表' }).click()
  await page.getByRole('link').filter({ hasText: '另一个项目' }).click()
  await page.getByRole('button', { name: '设置', exact: true }).click()
  panel = page.getByRole('region', { name: '项目创作策略' })
  await expect(panel.getByRole('radio', { name: '标准创作' })).toBeChecked()
  state.releaseSave()
  await expect.poll(() => state.settings['project-a']!.strategy).toBe('FANQIE_GRIPPING')
  await expect(panel.getByRole('radio', { name: '标准创作' })).toBeChecked()
  await expect(panel.getByRole('status')).toHaveCount(0)
  await panel.getByRole('radio', { name: '番茄强开篇' }).check()
  await panel.getByRole('button', { name: '保存创作策略' }).click()
  await expect(panel.getByRole('status')).toContainText('已保存')
  expect(state.saves[1]).toEqual({ projectId: 'project-b', input: { strategy: 'FANQIE_GRIPPING', version: 20 } })
  await screenshotAndLayout(page, testInfo, 'settings-project-switch')
  expect(state.unrelatedWrites).toEqual([])
  expect(state.failures).toEqual([])
})

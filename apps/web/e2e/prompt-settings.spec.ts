import { test, expect, type Page } from '@playwright/test'
import type { AgentPrompt, PromptRevision } from '../src/api/prompts'

async function fixture(page: Page) {
  const definitions = [
    ['OUTLINE', '分层大纲', '规划'], ['MANUSCRIPT', '正文创作与润色', '写作'],
    ['IMPORT_REVERSE_BIBLE_ADAPT', '导入圣经 · 改编', '导入'], ['IMPORT_REVERSE_BIBLE_CONTINUE', '导入圣经 · 续写', '导入'],
  ]
  let prompts: AgentPrompt[] = definitions.map(([key, name, group]) => ({ key: key!, workflow: key!.replace(/_(ADAPT|CONTINUE)$/, ''), name: name!, group: group!, systemPrompt: `你是${name} Agent。保留有效事实和作者授权边界。`, guidance: '', defaultSystemPrompt: `你是${name} Agent。保留有效事实和作者授权边界。`, protectedRules: '作者要求优先于通用建议。输出只作为候选，不提交正史。', customized: false, version: 0, updatedAt: null }))
  const history = new Map<string, PromptRevision[]>()
  const errors: string[] = []
  let conflict = false
  let failed = false
  page.on('pageerror', value => errors.push(value.message))
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/settings/model') return route.fulfill({ json: { provider: 'LOCAL_CODEX', codexModel: 'gpt-6.1-sol', codexEffort: 'xhigh', deepSeekModel: 'deepseek-flash', version: 0 } })
    if (path === '/api/v1/projects') return route.fulfill({ json: [] })
    if (path === '/api/v1/settings/prompts') {
      if (failed) return route.fulfill({ status: 503, json: { detail: '提示词查询暂时不可用' } })
      return route.fulfill({ json: prompts })
    }
    const parts = path.match(/^\/api\/v1\/settings\/prompts\/([^/]+)(?:\/(reset|history))?$/)
    if (!parts) return route.fulfill({ json: [] })
    const current = prompts.find(item => item.key === parts[1])!
    if (parts[2] === 'history') return route.fulfill({ json: history.get(current.key) ?? [] })
    const payload = request.postDataJSON()
    if (conflict || payload.version !== current.version) return route.fulfill({ status: 409, json: { detail: '版本冲突', code: 'RESOURCE_VERSION_CONFLICT' } })
    const reset = parts[2] === 'reset'
    const systemPrompt = reset ? current.defaultSystemPrompt : payload.systemPrompt
    const guidance = reset ? '' : payload.guidance
    const version = current.version + 1
    const updatedAt = new Date().toISOString()
    const next = { ...current, systemPrompt, guidance, version, updatedAt, customized: systemPrompt !== current.defaultSystemPrompt || !!guidance }
    prompts = prompts.map(item => item.key === current.key ? next : item)
    history.set(current.key, [{ version, systemPrompt: reset ? null : systemPrompt, guidance, operation: reset ? 'RESET' : 'SAVE', createdAt: updatedAt }, ...(history.get(current.key) ?? [])])
    return route.fulfill({ json: next })
  })
  return { errors, conflict: () => { conflict = true }, fail: (value: boolean) => { failed = value } }
}

test('edits persist across refresh, import modes stay independent, and history/reset work', async ({ page }) => {
  const { errors } = await fixture(page)
  await page.goto('/projects')
  await page.getByRole('link', { name: '提示词管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '提示词管理', exact: true })).toBeVisible()
  await page.getByLabel('系统指令', { exact: false }).fill('根据当前目标、行动、阻力与具体后果设计大纲，保留作者指定开场。')
  await page.getByLabel('阶段执行规则', { exact: false }).fill('前三章不要登记日常流程。第一段从有因果作用的当前矛盾切入。')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('已保存 · 版本 1')
  await page.reload()
  await expect(page.getByLabel('阶段执行规则', { exact: false })).toHaveValue('前三章不要登记日常流程。第一段从有因果作用的当前矛盾切入。')
  await page.getByRole('button', { name: /导入圣经 · 改编/ }).click()
  await page.getByLabel('阶段执行规则', { exact: false }).fill('改编规则')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('已保存')
  await page.getByRole('button', { name: /导入圣经 · 续写/ }).click()
  await expect(page.getByLabel('阶段执行规则', { exact: false })).toHaveValue('')
  await page.getByRole('button', { name: /分层大纲/ }).click()
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  await page.getByText(/版本 1 · 保存/).click()
  await expect(page.getByRole('button', { name: '载入此版本' })).toBeVisible()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '恢复默认', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('已恢复默认 · 版本 2')
  await page.reload()
  await expect(page.getByLabel('阶段执行规则', { exact: false })).toHaveValue('')
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  await page.getByText(/版本 1 · 保存/).click()
  await page.getByRole('button', { name: '载入此版本', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('尚未保存')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('版本 3')
  expect(errors).toEqual([])
})

test('guards unsaved changes, keeps a conflict draft, and retries a failed query', async ({ page }) => {
  const state = await fixture(page)
  state.fail(true)
  await page.goto('/settings/prompts?template=MANUSCRIPT')
  await expect(page.getByRole('alert')).toContainText('提示词查询暂时不可用')
  state.fail(false)
  await page.getByRole('button', { name: '重新读取提示词', exact: true }).click()
  await expect(page.getByLabel('系统指令', { exact: false })).toHaveValue(/正文创作与润色/)
  await page.getByLabel('阶段执行规则', { exact: false }).fill('暂存的规则')
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: /分层大纲/ }).click()
  await expect(page).toHaveURL(/template=MANUSCRIPT/)
  await expect(page.getByLabel('阶段执行规则', { exact: false })).toHaveValue('暂存的规则')
  state.conflict()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('本次编辑仍保留')
  await expect(page.getByLabel('阶段执行规则', { exact: false })).toHaveValue('暂存的规则')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: /分层大纲/ }).click()
  await expect(page).toHaveURL(/template=OUTLINE/)
  await page.reload()
  await expect(page.getByLabel('系统指令', { exact: false })).toHaveValue(/分层大纲/)
  expect(state.errors).toEqual([])
})

for (const width of [320, 390, 768, 1440]) {
  test(`prompt editor fits ${width}px without overlapping the toolbar`, async ({ page }, info) => {
    const { errors } = await fixture(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/settings/prompts?template=MANUSCRIPT')
    await expect(page.getByLabel('系统指令', { exact: false })).toBeVisible()
    await page.screenshot({ path: info.outputPath(`prompts-${width}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const brand = await page.locator('.brand').boundingBox()
    const model = await page.locator('.global-model-button').boundingBox()
    expect(brand!.x + brand!.width).toBeLessThanOrEqual(model!.x)
    const input = await page.locator('#prompt-system').boundingBox()
    const heading = await page.locator('.prompt-editor-heading').boundingBox()
    expect(heading!.y + heading!.height).toBeLessThanOrEqual(input!.y)
    expect(errors).toEqual([])
  })
}

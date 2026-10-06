import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import { character } from '../src/__tests__/character-blueprint-fixtures'
import type { PreparationTask, PreparationView } from '../src/api/creationPreparations'

test('prepares serially, requires confirmation, edits and creates only a future draft', async ({ page }, info) => {
  const models = createModelSettingsFixture()
  let calls = 0, confirmations = 0, edits = 0
  const versions: { requested: number; actual: number }[] = []
  const confirmationsReceived: { authorConfirmed: boolean; selectedChapters: number[] }[] = []
  const tasks: PreparationView[] = []
  const world = { characters: [character()], entities: [{ key: 'note', type: 'ITEM' as const, name: '未送出的纸条', description: '写给沈秋', initialState: '仍在书里', owner: '江澈' }] }
  const plot = { units: [{ key: 'opening', title: '选座与误会', startChapter: 1, endChapter: 20, objective: '直面选择', conflict: '不愿表态', turningPoint: '纸条曝光', endCondition: '亲口说明选择', characters: ['江澈'], planKeys: [] }], relationships: [], knowledge: [{ character: '江澈', information: '许冬将转学', knownFromChapter: 10, source: '老师告知，不得提前知道' }], timeline: [], readerExperiencePlans: [] }
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path.endsWith('/creation-preparations/checkpoints') || path.endsWith('/creation-preparations/plan-links')) return route.fulfill({ json: [] })
    if (path.endsWith('/creation-preparations')) {
      if (method === 'GET') return route.fulfill({ json: tasks })
      const input = route.request().postDataJSON()
      const task: PreparationTask = { id: `task-${tasks.length + 1}`, projectId: 'test-project', mode: input.mode, provider: input.provider, instruction: input.instruction, sourceBibleId: 'bible', sourceOutlineId: 'outline', sourceHash: '', sourceSnapshot: {}, startChapter: 1, endChapter: 20, status: 'READY', nextStep: input.mode === 'REVIEW' ? 2 : 0, worldDesign: null, plotDesign: null, reviewReport: null, resultOutlineId: null, errorMessage: null, version: 0, updatedAt: '' }
      tasks.unshift({ task, stale: false, ruleWarnings: [] }); return route.fulfill({ json: tasks[0] })
    }
    if (path.includes('/creation-preparations/task-')) {
      const view = tasks.find(item => path.includes(`/${item.task.id}`))!, task = view.task
      if (path.endsWith('/actions/run-next')) {
        versions.push({ requested: route.request().postDataJSON().version, actual: task.version }); calls++
        if (task.nextStep === 0) task.worldDesign = structuredClone(world)
        if (task.nextStep === 1) task.plotDesign = structuredClone(plot)
        if (task.nextStep === 2) task.reviewReport = { summary: '已核对规划与现有正史摘要；未逐字检查全部正文。', issues: [], planLinks: [], adjustments: task.mode === 'REVIEW' ? [{ chapterNumber: 12, objective: '主动追问', coreEvent: '当面核对纸条', reveal: '认出笔迹', endingHook: '决定表态', reason: '让转折由人物行动推动' }] : [] }
        task.nextStep++; task.version += 2; task.status = task.nextStep === 3 ? 'AWAITING_CONFIRMATION' : 'READY'
      } else if (method === 'PUT') {
        const input = route.request().postDataJSON(); versions.push({ requested: input.version, actual: task.version }); edits++
        task.worldDesign = input.world; task.plotDesign = input.plot; task.reviewReport = null; task.nextStep = 2; task.status = 'READY'; task.version++
      } else if (path.endsWith('/actions/confirm')) {
        const input = route.request().postDataJSON(); confirmationsReceived.push(input); confirmations++
        if (task.mode === 'REVIEW') task.resultOutlineId = 'future-draft'
        task.status = 'CONFIRMED'; task.version++
      }
      return route.fulfill({ json: view })
    }
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '五万字创作准备', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    if (path.endsWith('/writing-style/presets') || path.endsWith('/characters')) return route.fulfill({ json: [] })
    if (path.endsWith('/writing-style')) return route.fulfill({ json: { profile: null, version: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '创作准备', exact: true }).first().click()
  await page.getByRole('button', { name: '准备并检查', exact: true }).click()
  await expect(page.getByText('已核对规划与现有正史摘要；未逐字检查全部正文。')).toBeVisible()
  expect(calls).toBe(3); expect(confirmations).toBe(0)
  await expect(page.getByRole('button', { name: '应用规划资料' })).toBeDisabled()
  await page.getByRole('button', { name: '剧情单元', exact: true }).click()
  await page.getByLabel('核心目标', { exact: true }).fill('主动面对自己的偏爱')
  await page.getByRole('button', { name: '保存修改并重新检查' }).click()
  await page.getByRole('button', { name: '继续生成', exact: true }).click()
  await expect(page.getByText('已核对规划与现有正史摘要；未逐字检查全部正文。')).toBeVisible()
  expect(edits).toBe(1); expect(calls).toBe(4)
  await page.getByLabel('确认本次创作规划', { exact: true }).check()
  await page.getByRole('button', { name: '应用规划资料' }).click()
  await expect(page.locator('.preparation .status')).toContainText('已确认')
  expect(confirmations).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath('preparation-confirmed.png'), fullPage: true })
  await page.getByRole('button', { name: '剧情复核', exact: true }).click()
  await page.getByRole('button', { name: '生成复核报告' }).click()
  await page.getByLabel('第 12 章', { exact: true }).check()
  await page.getByLabel('确认本次复核与选定调整', { exact: true }).check()
  await page.screenshot({ path: info.outputPath('future-adjustment.png'), fullPage: true })
  await page.getByRole('button', { name: '确认报告并保存调整草稿' }).click()
  await expect.poll(() => confirmations).toBe(2)
  expect(tasks[0]!.task.resultOutlineId).toBe('future-draft')
  expect(versions.every(item => item.requested === item.actual)).toBe(true)
  expect(confirmationsReceived.every(item => item.authorConfirmed)).toBe(true)
  expect(confirmationsReceived[1]!.selectedChapters).toEqual([12])
})

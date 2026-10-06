import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import { character } from '../src/__tests__/character-blueprint-fixtures'

test('shows planning separately and shares the ledger across both pages', async ({ page }, testInfo) => {
  const models = createModelSettingsFixture()
  const blueprint = character()
  const characterId = 'character-1'
  let syncs = 0
  let generations = 0
  let entry = {
    plan: { id: 'plan-1', projectId: 'test-project', kind: 'FORESHADOW', title: '纸条上的签名', promise: '找出写信的人',
      setup: '辨认字迹', payoff: '主角认出字迹', aftermath: '承担选择', plannedChapter: 3, version: 0 },
    state: 'PLANNED', stale: false, history: [],
  }
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path.endsWith('/actions/generate')) { generations++; return route.fulfill({ status: 500 }) }
    if (path.endsWith('/planning-materials/actions/sync')) { syncs++; return route.fulfill({ status: 204 }) }
    if (path.endsWith('/planning-materials/characters')) return route.fulfill({ json: [{ sourceBibleId: 'bible-1', characterId, blueprint }] })
    if (path.endsWith('/planning-materials/relationships')) return route.fulfill({ json: [{ id: 'relation-1', sourceBibleId: 'bible-1', characterId, description: '江澈与许冬相识一年，尚未明确回应喜欢。' }] })
    if (path.endsWith('/planning-materials/plan-origins')) return route.fulfill({ json: [{ planId: 'plan-1', sourceKind: 'BIBLE', sourceId: 'bible-1', current: true }] })
    if (path.endsWith('/canon/entities')) return route.fulfill({ json: [{ id: characterId, type: 'CHARACTER', name: blueprint.name, status: 'PLANNED', canonVersionFrom: 0 }] })
    if (path.endsWith('/characters')) return route.fulfill({ json: [{ id: characterId, canonicalName: blueprint.name, sourceName: blueprint.name, roleKey: 'PROTAGONIST', version: 0 }] })
    if (path.endsWith('/character-profiles')) return route.fulfill({ json: [{ ...blueprint, characterId, canonicalName: blueprint.name, roleKey: 'PROTAGONIST', version: 1, gender: '', ageDescription: '', notes: '' }] })
    if (path.endsWith('/reader-experiences/memory')) return route.fulfill({ json: { mode: 'EXISTING_CANON_SUMMARIES', outlineId: null, arcs: [], unassignedChapters: [] } })
    if (path.endsWith('/reader-experiences/sources')) return route.fulfill({ json: [] })
    if (path.endsWith('/reader-experiences/plan-1') && method === 'PUT') {
      entry = { ...entry, plan: { ...entry.plan, ...route.request().postDataJSON(), version: entry.plan.version + 1 } }
      return route.fulfill({ json: entry })
    }
    if (path.endsWith('/reader-experiences')) return route.fulfill({ json: [entry] })
    if (path.endsWith('/canon/relationships') || path.endsWith('/canon/foreshadows')) return route.fulfill({ json: [] })
    if (path.endsWith('/writing-style/presets')) return route.fulfill({ json: [] })
    if (path.endsWith('/writing-style')) return route.fulfill({ json: { profile: null, version: 0 } })
    if (path.endsWith('/test-project')) return route.fulfill({ json: { id: 'test-project', name: '规划资料验证', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '故事资料', exact: true }).click()
  await expect(page.getByLabel('成长与家庭背景', { exact: true })).toHaveValue(blueprint.background)
  await expect(page.getByText('已发布人物底稿 · 作者设定')).toBeVisible()
  await expect(page.getByText(blueprint.abilitiesAndLimits, { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '关系', exact: true }).click()
  await expect(page.getByText('江澈与许冬相识一年，尚未明确回应喜欢。')).toBeVisible()
  await expect(page.getByText('规划关系 · 未作为正文事实确认')).toBeVisible()
  await expect(page.getByText('暂无已确认的人物关系。')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('planned-relations.png'), fullPage: true })
  await page.getByRole('button', { name: '伏笔与承诺', exact: true }).click()
  await expect(page.locator('.entry')).toContainText('故事圣经规划')
  await expect(page.locator('.entry')).toContainText('计划中')
  await page.locator('.entry').click()
  await page.getByLabel('兑现计划', { exact: true }).fill('作者确认的新兑现计划')
  await page.getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(page.getByText('已保存', { exact: true }).last()).toBeVisible()
  await page.getByRole('button', { name: '故事资料', exact: true }).click()
  await page.getByRole('button', { name: '伏笔', exact: true }).click()
  await page.locator('.entry').click()
  await expect(page.getByLabel('兑现计划', { exact: true })).toHaveValue('作者确认的新兑现计划')
  await page.getByRole('button', { name: '同步已发布规划', exact: true }).click()
  await expect.poll(() => syncs).toBe(1)
  expect(generations).toBe(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('shared-ledger.png'), fullPage: true })
})

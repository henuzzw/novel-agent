import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import { bible, character } from '../src/__tests__/character-blueprint-fixtures'

test('unifies character details, relations and facts while preserving independent non-character entities', async ({ page }) => {
  const models = createModelSettingsFixture()
  const blueprint = character()
  let name = '江澈', version = 0, nameSaves = 0, paidCalls = 0
  let savedHeader: string | undefined
  const relationQueries: (string | null)[] = []
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path.includes('/actions/generate') || path.includes('/actions/complete-characters')) {
      paidCalls++; return route.fulfill({ status: 500 })
    }
    if (path.endsWith('/characters/c1') && route.request().method() === 'PUT') {
      savedHeader = route.request().headers()['if-match']
      name = route.request().postDataJSON().canonicalName; version++; nameSaves++
      return route.fulfill({ json: { id: 'c1', sourceName: '江澈', canonicalName: name, nickname: null, title: null, roleKey: 'PROTAGONIST', version } })
    }
    if (path.endsWith('/project-1')) return route.fulfill({ json: { id: 'project-1', name: '人物档案整合验证', entryMode: 'IDEA', currentCanonVersion: 1, version: 0 } })
    if (path.endsWith('/story-bibles/latest')) return route.fulfill({ json: { ...bible(), status: 'PUBLISHED' } })
    if (path.endsWith('/characters') && !path.includes('/planning-materials/')) return route.fulfill({ json: [{ id: 'c1', canonicalName: name, sourceName: '江澈', roleKey: 'PROTAGONIST', version }] })
    if (path.endsWith('/character-profiles')) return route.fulfill({ json: [{ ...blueprint, characterId: 'c1', canonicalName: name, roleKey: 'PROTAGONIST', gender: '', ageDescription: '', notes: '', version: 1 }] })
    if (path.endsWith('/planning-materials/characters')) return route.fulfill({ json: [{ sourceBibleId: 'bible-1', characterId: 'c1', blueprint }] })
    if (path.endsWith('/planning-materials/relationships')) {
      relationQueries.push(new URL(route.request().url()).searchParams.get('characterId'))
      return route.fulfill({ json: [{ id: 'planned1', sourceBibleId: 'bible-1', characterId: 'c1', description: '开篇规划：与许冬是学习伙伴，未表态。' }] })
    }
    if (path.endsWith('/canon/entities')) return route.fulfill({ json: [{ id: 'c1', type: 'CHARACTER', name, status: 'PLANNED', canonVersionFrom: 0 }, { id: 'i1', type: 'ITEM', name: '细框眼镜', status: 'PLANNED', canonVersionFrom: 0 }] })
    if (path.endsWith('/canon/relationships')) return route.fulfill({ json: [{ id: 'canon1', sourceEntityName: name, targetEntityName: '许冬', relationType: 'FRIEND', attributes: {}, chapterNumber: 1, canonVersionFrom: 1, evidence: '我们约好一起复习。' }] })
    if (path.endsWith('/canon/knowledge')) return route.fulfill({ json: [] })
    if (path.endsWith('/entities/c1/state')) return route.fulfill({ json: [{ changeId: 'state1', field: 'location', value: '第二排', chapterNumber: 1, canonVersionFrom: 1, evidence: '我坐到了第二排。' }] })
    if (path.endsWith('/entities/c1/aliases')) return route.fulfill({ json: [{ id: 'alias1', alias: '小澈', aliasType: 'NICKNAME' }] })
    if (path.endsWith('/entities/c1/mentions')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/project-1')
  await page.getByRole('button', { name: '故事资料', exact: true }).click()
  await expect(page.getByLabel('身份', { exact: true })).toHaveValue(blueprint.identity)
  await expect(page.getByText('待补充设定：性别、年龄', { exact: true })).toBeVisible()
  await expect(page.getByText('开篇规划：与许冬是学习伙伴，未表态。')).toBeVisible()
  await expect(page.getByText('正文正史关系', { exact: true })).toBeVisible()
  await expect(page.getByText('第二排', { exact: true })).toBeVisible()
  await expect(page.getByText('小澈', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: '人物命名', exact: true })).toHaveCount(0)
  await expect(page.locator('form form')).toHaveCount(0)
  await page.getByLabel('正式姓名', { exact: true }).fill('江明')
  await page.getByRole('button', { name: '保存人物命名', exact: true }).click()
  await expect.poll(() => nameSaves).toBe(1)
  await expect(page.locator('.profile-picker')).toContainText('江明')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.evaluate(() => scrollTo(0, 0))
  await page.getByRole('button', { name: '非人物实体', exact: true }).click()
  await expect(page.getByLabel('实体列表')).toContainText('细框眼镜')
  await expect(page.getByLabel('实体列表')).not.toContainText('江明')
  expect(paidCalls).toBe(0)
  expect(savedHeader).toBe('"0"')
  expect(relationQueries.length).toBeGreaterThan(0)
  expect(relationQueries.every(id => id === 'c1')).toBe(true)
})

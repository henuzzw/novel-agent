import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'
import { bible, character } from '../src/__tests__/character-blueprint-fixtures'

test('completes legacy character designs and saves edits before explicit publication', async ({ page }) => {
  const models = createModelSettingsFixture()
  let current = { ...bible(), projectId: 'test-project', status: 'PUBLISHED' as 'DRAFT' | 'PUBLISHED' }
  delete current.content.characterBlueprints
  let completions = 0
  let saves = 0
  let publications = 0
  let fullGenerations = 0
  let completionVersion = ''
  let completionProvider = ''
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path.endsWith('/story-bibles/latest')) return route.fulfill({ json: current })
    if (path.endsWith('/story-bibles') && method === 'GET') return route.fulfill({ json: [{
      id: current.id, generationNumber: current.generationNumber, status: current.status,
      logline: current.content.logline, baseBibleVersionId: current.baseBibleVersionId, createdAt: '',
    }] })
    if (path.endsWith('/actions/complete-characters')) {
      completionVersion = route.request().headers()['if-match'] ?? ''
      completionProvider = route.request().postDataJSON().provider
      completions++
      current = { ...current, id: 'bible-2', generationNumber: 2, version: 0, status: 'DRAFT',
        baseBibleVersionId: 'bible-1', content: { ...current.content, characterBlueprints: [character()] } }
      return route.fulfill({ status: 201, json: current })
    }
    if (path.endsWith('/story-bibles/bible-2') && method === 'PUT') {
      saves++
      current = { ...current, version: 1, content: route.request().postDataJSON().content }
      return route.fulfill({ json: current })
    }
    if (path.endsWith('/actions/publish')) {
      publications++
      current = { ...current, version: 2, status: 'PUBLISHED' }
      return route.fulfill({ json: current })
    }
    if (path.endsWith('/story-bibles/actions/generate')) {
      fullGenerations++
      return route.fulfill({ status: 500 })
    }
    if (path.endsWith('/test-project')) return route.fulfill({ json: {
      id: 'test-project', name: '人物底稿验证', entryMode: 'MATERIALS', version: 0, currentCanonVersion: 0,
    } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/test-project')
  await page.getByRole('button', { name: '故事圣经', exact: true }).click()
  await expect(page.getByText('尚无人物底稿')).toBeVisible()
  expect(completions).toBe(0)
  await page.getByRole('button', { name: '补全人物底稿', exact: true }).click()
  await expect(page.getByText('第 2 版 · 草稿')).toBeVisible()
  await expect(page.getByLabel('背景与行为成因', { exact: true })).toHaveValue(character().background)
  expect(publications).toBe(0)
  expect(completions).toBe(1)
  expect(completionVersion).toBe('"3"')
  expect(completionProvider).toBe('LOCAL_CODEX')
  await page.getByLabel('背景与行为成因', { exact: true }).fill('从小被要求懂事，但仍想保留自己的决定。')
  await expect(page.getByRole('button', { name: '确认并发布', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '补全人物底稿', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '确认并发布', exact: true })).toBeEnabled()
  expect(saves).toBe(1)
  expect(current.content.characterBlueprints?.[0]?.background).toBe('从小被要求懂事，但仍想保留自己的决定。')
  await expect(page.getByLabel('未来弧光与触发条件（尚未发生）', { exact: true })).toHaveValue(character().characterArc)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.locator('.blueprint-heading').evaluate(element => element.scrollIntoView({ block: 'start' }))
  await page.getByRole('button', { name: '确认并发布', exact: true }).click()
  await expect(page.getByText('第 2 版 · 已发布')).toBeVisible()
  expect(publications).toBe(1)
  expect(fullGenerations).toBe(0)
})

import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

test('project chat persists navigation, previews a proposal and only applies after confirmation', async ({ page }) => {
  const models = createModelSettingsFixture()
  let applied = false
  const changes = [{ id: 'change', turnId: 'turn', key: 'OUTLINE:outline:1', title: '第一章 · 纸条', before: '她把纸条收起来。', after: '她把纸条退回来，要求他说明自己的选择。',
    reason: '让关系压力进入现场行动。', impact: '第二章需要承接这次选择。', status: 'PENDING', resultKey: null, createdAt: '2026-10-12T01:00:00Z' }]
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/chat/changes/change/apply')) { applied = true; changes[0]!.status = 'APPLIED'; return route.fulfill({ status: 204 }) }
    if (path.endsWith('/chat/changes')) return route.fulfill({ json: changes })
    if (path.endsWith('/chat/turns')) return route.fulfill({ json: [{ id: 'turn', question: '让第一章的关系冲突更明确。', reply: '已提出修改候选，等待你确认。', status: 'SUCCEEDED', error: null,
      sources: [{ key: 'OUTLINE:outline:1', title: '第一章 · 纸条', offset: 0, end: 12, characters: 12, fingerprint: 'fingerprint' }], createdAt: '2026-10-12T01:00:00Z' }] })
    if (path.endsWith('/chat/sources')) return route.fulfill({ json: [{ key: 'OUTLINE:outline:1', title: '第一章 · 纸条', writable: true, characters: 12 }] })
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: [] })
    if (/\/projects\/[^/]+$/.test(path)) return route.fulfill({ json: { id: 'chat-ux', name: '全班都知道她喜欢我', entryMode: 'MATERIALS', currentCanonVersion: 0, version: 0 } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/chat-ux?section=chat')
  const panel = page.getByRole('region', { name: '项目创作对话' })
  await expect(panel.getByText('她把纸条收起来。', { exact: true })).toBeVisible()
  await expect(panel.getByText('她把纸条退回来，要求他说明自己的选择。', { exact: true })).toBeVisible()
  expect(applied).toBe(false)
  await page.reload()
  await expect(panel.getByRole('heading', { name: '创作对话' })).toBeVisible()
  await expect(page.getByRole('button', { name: '创作对话', exact: true })).toHaveAttribute('aria-current', 'page')
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/project-chat.png', fullPage: true })
  await panel.getByRole('button', { name: '确认应用', exact: true }).click()
  await expect(panel.getByRole('button', { name: '撤销本次修改' })).toBeVisible()
  expect(applied).toBe(true)
})

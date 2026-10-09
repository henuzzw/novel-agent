import { test, expect } from '@playwright/test'

test('account management stays locked and transport only changes after explicit confirmation', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  let choice = { transport: 'APP_SERVER', version: 0 }
  const calls: string[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (path === '/api/v1/settings/model') return route.fulfill({ json: { provider: 'LOCAL_CODEX', codexModel: 'test-model', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 0 } })
    if (path === '/api/v1/settings/model/chatgpt-models') return route.fulfill({ json: [{ model: 'test-model', label: 'Test model', efforts: ['medium', 'high'], defaultEffort: 'medium' }] })
    if (path.startsWith('/api/v1/settings/model/chatgpt')) {
      calls.push(path)
      if (request.headers()['x-chatgpt-admin-key'] !== 'test-management') return route.fulfill({ status: 403, json: { message: '管理口令不正确' } })
      if (path.endsWith('/transport')) { choice = { transport: request.postDataJSON().transport, version: 1 }; return route.fulfill({ json: choice }) }
      return route.fulfill({ json: { choice, auth: { connected: true, canGenerate: true, email: 'test@example.invalid', expiresAt: null, loginStatus: 'IDLE', error: null, attemptId: null, loginExpiresAt: null } } })
    }
    return route.fulfill({ json: [] })
  })
  await page.goto('/projects')
  await page.getByRole('button', { name: '全局模型设置', exact: true }).click()
  await expect(page.getByLabel('连接管理口令')).toBeVisible()
  expect(calls).toEqual([])
  await page.getByLabel('连接管理口令').fill('test-management')
  await page.getByRole('button', { name: '解锁', exact: true }).click()
  await expect(page.getByRole('radio', { name: 'Codex App Server', exact: true })).toBeChecked()
  await page.getByRole('radio', { name: 'ChatGPT OAuth 直连（Beta）', exact: true }).click()
  await expect(page.getByRole('radio', { name: 'ChatGPT OAuth 直连（Beta）', exact: true })).toBeChecked()
  await page.screenshot({ path: 'test-results/chatgpt-connection-desktop.png' })
  expect(await page.evaluate(() => localStorage.getItem('X-ChatGPT-Admin-Key'))).toBeNull()
  expect(errors).toEqual([])
})

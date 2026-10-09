import { expect, test } from '@playwright/test'
import { createModelSettingsFixture } from './model-settings-fixture'

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

test('project creation works on an HTTP origin without native randomUUID', async ({ page }) => {
  const origin = 'http://novel-agent.test:5173'
  const models = createModelSettingsFixture()
  const errors: string[] = []
  const project = { id: 'uuid-compatibility', name: 'HTTP校园小说', entryMode: 'MATERIALS', version: 0,
    currentCanonVersion: 0, creativeIntent: null }
  let requestKey = ''
  page.on('pageerror', error => errors.push(error.message))
  // Serve Vite assets under a genuinely untrusted HTTP origin; all business requests are intercepted.
  await page.route(`${origin}/**`, async route => {
    const url = new URL(route.request().url())
    const response = await route.fetch({ url: `http://localhost:5173${url.pathname}${url.search}` })
    await route.fulfill({ response })
  })
  await page.route('**/api/v1/**', async route => {
    if (await models(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/projects') {
      requestKey = request.headers()['idempotency-key'] ?? ''
      return route.fulfill({ status: 201, json: project })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400, json: { detail: 'Unexpected mutation' } })
    if (path === `/api/v1/projects/${project.id}`) return route.fulfill({ json: project })
    if (path.endsWith('/creative-strategy')) return route.fulfill({ json: { strategy: 'STANDARD', policyVersion: 1, version: 0 } })
    if (path.endsWith('/latest') || path.endsWith('/current')) return route.fulfill({ status: 204 })
    return route.fulfill({ json: [] })
  })
  await page.goto(`${origin}/projects/new?mode=MATERIALS`)
  expect(await page.evaluate(() => ({ secure: isSecureContext, nativeUuid: typeof crypto.randomUUID,
    randomBytes: typeof crypto.getRandomValues }))).toEqual({ secure: false, nativeUuid: 'undefined', randomBytes: 'function' })
  await page.getByLabel('项目名称', { exact: true }).fill(project.name)
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/projects/${project.id}`))
  expect(requestKey).toMatch(uuidV4)
  expect(errors).toEqual([])
})

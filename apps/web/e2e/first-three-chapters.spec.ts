import { test, expect, type Page } from '@playwright/test'
import { openingReport, openingView } from '../src/__tests__/FirstThreeChaptersFixtures'
import type { OpeningCheckInput } from '../src/api/firstThreeChapters'

test.use({ headless: true, channel: process.platform === 'win32' ? 'msedge' : undefined })

// The panel is mounted independently so the integration owner can choose its workspace location.
async function setup(page: Page, options: { missing?: boolean; overflow?: boolean; stale?: boolean; fail?: boolean } = {}) {
  const state = openingView()
  const checks: OpeningCheckInput[] = []
  const selections: string[] = []
  if (options.missing) {
    state.available = false
    state.source.chapters[2]!.body = null
    state.source.chapters[2]!.manuscriptId = null
    state.source.chapters[2]!.versions = []
    state.source.unavailableReasons = ['缺少第三章完整正文']
  }
  if (options.overflow) state.budget.fits = false
  if (options.stale) state.latestReport = { ...openingReport(state), current: false, fingerprint: 'old-source' }
  await page.route('**/api/v1/projects/project-1/opening-review**', async route => {
    const request = route.request()
    if (request.method() === 'POST') {
      const input = request.postDataJSON() as OpeningCheckInput
      checks.push(input)
      if (options.fail) return route.fulfill({ status: 400, json: { detail: '检查期间依据已变化，请刷新' } })
      state.latestReport = state.latestValidReport = openingReport(state)
      return route.fulfill({ status: 201, json: state.latestReport })
    }
    const selection = new URL(request.url()).searchParams.get('manuscriptIds')
    if (selection) {
      selections.push(selection)
      const ids = selection.split(',')
      for (let n = 0; n < 3; n++) {
        if (ids[n]?.startsWith('old')) {
          state.source.chapters[n]!.manuscriptId = ids[n]!
          state.source.chapters[n]!.versionNumber = 1
          state.source.chapters[n]!.body = `历史第${n + 1}章完整正文。历史结尾。`
          state.source.fingerprint = 'history-hash'
        }
      }
    }
    return route.fulfill({ json: state })
  })
  const module = await (await page.request.get('/src/components/FirstThreeChaptersPanel.vue')).text()
  const vueModule = module.match(/from ["']([^"']*\/vue\.js[^"']*)["']/)?.[1]
  if (!vueModule) throw new Error('Vite did not expose the panel Vue runtime')
  await page.route('**/first-three-chapters-test', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html>
    <html lang="zh"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
    <body style="margin:16px;font-family:system-ui;color:#252d32"><div id="opening-test"></div>
    <script type="module">
    import { createApp } from '${vueModule}';
    import Panel from '/src/components/FirstThreeChaptersPanel.vue';
    createApp(Panel, { projectId: 'project-1', provider: 'DEEPSEEK' }).mount('#opening-test');
    </script></body></html>` }))
  await page.goto('/first-three-chapters-test')
  await expect(page.getByRole('heading', { name: '前三章连读', exact: true })).toBeVisible()
  await expect(page.locator('.opening-chapter')).toHaveCount(3)
  return { checks, selections }
}

for (const viewport of [{ width: 1440, height: 1000 }]) {
  test.describe(`standalone opening reader ${viewport.width}px`, () => {
    test.use({ viewport })
    test('reads all three full sources, selects history, and checks only on explicit action', async ({ page }) => {
      const activity = await setup(page)
      expect(activity.checks).toHaveLength(0)
      await expect(page.getByText('未完成检查', { exact: true })).toBeVisible()
      for (let n = 1; n <= 3; n++) await expect(page.locator('.opening-chapter').nth(n - 1).locator('.opening-body')).toContainText(`第${n}章结尾。`)
      await page.getByRole('combobox', { name: '第 1 章正文版本', exact: true }).selectOption('old1')
      await expect(page.locator('.opening-body').first()).toContainText('历史结尾。')
      expect(activity.selections).toContain('old1,m2,m3')
      expect(activity.checks).toHaveLength(0)
      await page.getByLabel('输入预算上限（Token）').fill('11000')
      await expect(page.getByRole('button', { name: '检查完整三章', exact: true })).toBeDisabled()
      await page.getByLabel('输入预算上限（Token）').fill('20000')
      await page.getByRole('button', { name: '检查完整三章', exact: true }).click()
      await expect(page.getByText('兑现需作者复核', { exact: false })).toBeVisible()
      expect(activity.checks).toEqual([{ manuscriptIds: ['old1', 'm2', 'm3'], provider: 'DEEPSEEK', instruction: '', expectedFingerprint: 'history-hash', maxInputTokens: 20000 }])
      await expect(page.locator('.opening-report')).not.toContainText('通过')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
    test('missing chapters and overflow block a model check', async ({ page }) => {
      const activity = await setup(page, { missing: true, overflow: true })
      await expect(page.getByText('缺少第三章完整正文', { exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '检查完整三章', exact: true })).toBeDisabled()
      await expect(page.locator('.opening-chapter').last().locator('.opening-body')).toContainText('合同不代替正文')
      expect(activity.checks).toHaveLength(0)
    })
    test('stale reports and check failures do not trigger automatic retries', async ({ page }) => {
      const activity = await setup(page, { stale: true, fail: true })
      await expect(page.getByText('报告已过期，正文或写作依据已变化。', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: '检查完整三章', exact: true }).click()
      await expect(page.getByRole('alert')).toHaveText('检查期间依据已变化，请刷新')
      expect(activity.checks).toHaveLength(1)
      await expect(page.locator('.opening-chapter').last().locator('.opening-body')).toContainText('第3章结尾。')
    })
  })
}

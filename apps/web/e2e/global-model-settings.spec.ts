import { test, expect } from '@playwright/test'

test('global settings persist after reload and drive generation in another project', async ({ page }, testInfo) => {
  let settings = { provider: 'LOCAL_CODEX', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 0 }
  let generatedProvider = ''
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/settings/model') {
      if (request.method() === 'PUT') settings = { ...request.postDataJSON(), version: settings.version + 1 }
      return route.fulfill({ json: settings })
    }
    if (path.endsWith('/chatgpt-models')) return route.fulfill({ json: [
      { model: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', efforts: ['high', 'max'], defaultEffort: 'high' },
      { model: 'gpt-6-luna', label: 'GPT-6 Luna', efforts: ['low', 'medium'], defaultEffort: 'medium' },
    ] })
    if (path === '/api/v1/projects') return route.fulfill({ json: [] })
    if (/\/projects\/project-[ab]$/.test(path)) return route.fulfill({ json: { id: path.split('/').pop(), name: '模型配置验证', entryMode: 'IDEA', version: 0, currentCanonVersion: 0,
      creativeIntent: null } })
    if (path.endsWith('/creative-intent')) return route.fulfill({ json: { id: path.split('/')[4], name: '模型配置验证', entryMode: 'IDEA', version: 0, currentCanonVersion: 0, creativeIntent: { ...request.postDataJSON(), version: 1 } } })
    if (path.endsWith('/story-directions/actions/generate')) {
      generatedProvider = request.postDataJSON().provider
      return route.fulfill({ status: 503, json: { detail: '验证已捕获模型选择，未调用真实模型' } })
    }
    return route.fulfill({ json: path.endsWith('/latest') || path.endsWith('/current') ? null : [] })
  })
  await page.goto('/projects/project-a')
  await page.getByRole('button', { name: '全局模型设置', exact: true }).click()
  await page.getByLabel('ChatGPT 模型', { exact: true }).selectOption('gpt-6-luna')
  await expect(page.getByLabel('ChatGPT 推理强度')).toHaveValue('medium')
  await page.getByLabel('ChatGPT 推理强度').selectOption('low')
  await page.getByRole('button', { name: '保存全局设置' }).click()
  await expect(page.locator('.global-model-button')).toContainText('gpt-6-luna · low')
  await page.getByLabel('全局模型供应商').selectOption('DEEPSEEK')
  await page.getByLabel('DeepSeek 模型').selectOption('deepseek-v4-pro')
  await page.getByRole('button', { name: '保存全局设置' }).click()
  await expect(page.locator('.global-model-button')).toContainText('DeepSeek V4 Pro')
  await page.reload()
  await expect(page.locator('.global-model-button')).toContainText('DeepSeek V4 Pro')
  await page.goto('/projects/project-b')
  await expect(page.locator('.global-model-badge').first()).toContainText('DeepSeek V4 Pro')
  await page.getByLabel('一句话创意', { exact: true }).fill('同学寻找失物')
  await page.getByLabel('主角简述', { exact: true }).fill('林安想找回同学遗失的纸条')
  await page.getByLabel('核心冲突', { exact: true }).fill('同学们给出的线索彼此矛盾')
  await page.getByRole('button', { name: '保存并生成故事方向' }).click()
  await expect(page.getByRole('alert')).toContainText('验证已捕获模型选择')
  expect(generatedProvider).toBe('DEEPSEEK')
  await page.getByRole('button', { name: '全局模型设置', exact: true }).click()
  await expect(page.getByLabel('DeepSeek 模型')).toHaveValue('deepseek-v4-pro')
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: testInfo.outputPath('global-model-settings.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const brand = await page.locator('.brand').boundingBox()
  const modelButton = await page.locator('.global-model-button').boundingBox()
  expect(brand!.x + brand!.width).toBeLessThanOrEqual(modelButton!.x)
  expect(failures).toEqual([])
})

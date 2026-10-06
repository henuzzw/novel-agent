import { expect, type Page, type Route } from '@playwright/test'

export function createModelSettingsFixture() {
  let settings = { provider: 'LOCAL_CODEX', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 0 }
  return async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/settings/model') {
      if (route.request().method() === 'PUT') settings = { ...route.request().postDataJSON(), version: settings.version + 1 }
      await route.fulfill({ json: settings })
      return true
    }
    if (path === '/api/v1/settings/model/chatgpt-models') {
      await route.fulfill({ json: [{ model: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', efforts: ['high', 'max'], defaultEffort: 'high' }] })
      return true
    }
    return false
  }
}

export async function selectGlobalProvider(page: Page, provider: string) {
  const toggle = page.getByRole('button', { name: '全局模型设置', exact: true })
  if (await toggle.getAttribute('aria-expanded') !== 'true') await toggle.click()
  if (await page.getByLabel('全局模型供应商').inputValue() === provider) {
    await toggle.click()
    return
  }
  await page.getByLabel('全局模型供应商').selectOption(provider)
  await page.getByRole('button', { name: '保存全局设置' }).click()
  const label = provider === 'LOCAL_TEMPLATE' ? '本地模板' : provider === 'DEEPSEEK' ? 'DeepSeek' : 'ChatGPT'
  await expect(toggle).toContainText(label)
  await toggle.click()
}

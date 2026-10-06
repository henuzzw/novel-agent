import { defineConfig, devices } from '@playwright/test'
import process from 'node:process'

export default defineConfig({
  testDir: './e2e',
  testMatch: ['automation.spec.ts', 'quality-and-style.spec.ts', 'automation-quality.spec.ts', 'automation-limits.spec.ts', 'style-preview.spec.ts', 'style-recommendation.spec.ts', 'style-preview-editing.spec.ts', 'global-model-settings.spec.ts', 'creative-strategy.spec.ts', 'first-three-chapters.spec.ts', 'creative-additional.spec.ts', 'planning-batches.spec.ts', 'character-blueprints.spec.ts', 'character-dossier.spec.ts', 'agent-run-output.spec.ts', 'planning-materials.spec.ts', 'creation-preparations.spec.ts', 'import-analysis.spec.ts', 'generation-status.spec.ts'],
  timeout: 30000,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5174', headless: true,
    channel: process.env.PLAYWRIGHT_CHANNEL ?? (process.platform === 'win32' ? 'msedge' : undefined) },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 5'] } },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: true,
  },
})

import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config.ts'

export function createTestConfig(full = false) {
  return mergeConfig(
    viteConfig,
    defineConfig({
      test: {
        environment: 'jsdom',
        // Keep local checks bounded; the full suite is an explicit command.
        maxWorkers: 1,
        fileParallelism: false,
        include: full
          ? ['src/**/__tests__/**/*.spec.ts']
          : [
              'src/__tests__/uuid.spec.ts',
              'src/__tests__/http-api.spec.ts',
              'src/__tests__/create-project-strategy.spec.ts',
              'src/__tests__/workspace-location.spec.ts',
              'src/__tests__/snowflake-planning.spec.ts',
              'src/__tests__/draft-loops.spec.ts',
              'src/__tests__/manuscript-manual-revision.spec.ts',
            ],
        exclude: [...configDefaults.exclude, 'e2e/**'],
        root: fileURLToPath(new URL('./', import.meta.url)),
      },
    }),
  )
}

export default createTestConfig()

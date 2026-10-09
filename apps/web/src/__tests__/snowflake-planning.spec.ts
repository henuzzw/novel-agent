import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SnowflakePlanningPanel from '@/components/SnowflakePlanningPanel.vue'
import { getLatestSnowflakePlan, type SnowflakePlan } from '@/api/snowflake'
import { generationRequests } from '@/lib/generation-activity'

vi.mock('@/api/snowflake', () => ({ getLatestSnowflakePlan: vi.fn() }))
let wrapper: VueWrapper | undefined
let client: QueryClient
function plan(overrides: Partial<SnowflakePlan> = {}): SnowflakePlan {
  return { id: 'plan-1', projectId: 'p1', mode: 'NEW_STORY', provider: 'DEEPSEEK',
    status: 'SUCCEEDED', activeStage: 'PLOT', core: '自由文本故事核心', characters: '人物自由叙述，无字段清单',
    world: '世界使人物无法轻易选择', plot: '第一幕起因，第二幕受阻，第三幕承担代价', errorMessage: null,
    steps: { CORE: '自由文本故事核心', CHARACTER_SETTINGS: '人物自由叙述，无字段清单' },
    createdAt: '', updatedAt: '', ...overrides }
}
function render() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  wrapper = mount(SnowflakePlanningPanel, { props: { projectId: 'p1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return wrapper
}

describe('snowflake planning', () => {
  beforeEach(() => { vi.resetAllMocks(); generationRequests.value = [] })
  afterEach(() => { wrapper?.unmount(); client?.clear(); generationRequests.value = [] })

  it('shows arbitrary prose without nested creative fields', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan())
    const view = render()
    await flushPromises()
    expect(view.findAll('details')).toHaveLength(9)
    expect(view.text()).toContain('人物自由叙述，无字段清单')
    expect(view.text()).toContain('全部场景清单')
    expect(view.findAll('textarea')).toHaveLength(0)
  })

  it('keeps successful text visible when a later stage fails or stops', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'FAILED', activeStage: 'CHARACTER_ARCS',
      world: null, plot: null, errorMessage: '世界请求超时' }))
    const view = render()
    await flushPromises()
    expect(view.text()).toContain('世界请求超时')
    expect(view.text()).toContain('自由文本故事核心')
    expect(view.findAll('summary')[2]!.text()).toContain('失败')
    expect(view.findAll('summary')[3]!.text()).toContain('待生成')
  })

  it('hides absent plans and reloads for the next request', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(null)
    const view = render()
    await flushPromises()
    expect(view.find('section').exists()).toBe(false)
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'RUNNING', activeStage: 'CORE',
      core: null, characters: null, world: null, plot: null }))
    generationRequests.value = [{ id: 'r1', projectId: 'p1', stage: 'STORY_BIBLE', status: 'RUNNING',
      startedAt: '', completedAt: null, errorMessage: null, source: 'request', chapter: null }]
    await flushPromises()
    expect(view.text()).toContain('请求中')
    expect(getLatestSnowflakePlan).toHaveBeenCalledTimes(2)
  })

  it('does not retain another project text after switching', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValueOnce(plan()).mockResolvedValueOnce(null)
    const view = render()
    await flushPromises()
    await view.setProps({ projectId: 'p2' })
    await flushPromises()
    expect(view.find('section').exists()).toBe(false)
    expect(getLatestSnowflakePlan).toHaveBeenLastCalledWith('p2')
  })

  it('renders load failures with an explicit retry', async () => {
    vi.mocked(getLatestSnowflakePlan).mockRejectedValue(new Error('读取失败'))
    const view = render()
    await flushPromises()
    expect(view.text()).toContain('读取失败')
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan())
    await view.get('button').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('自由文本故事核心')
  })
})

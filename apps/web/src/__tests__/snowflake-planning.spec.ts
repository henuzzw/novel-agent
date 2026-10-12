import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SnowflakePlanningPanel from '@/components/SnowflakePlanningPanel.vue'
import { confirmSnowflakePlan, getLatestSnowflakePlan, type SnowflakePlan } from '@/api/snowflake'
import { generationRequests } from '@/lib/generation-activity'
import { resumeImportedSnowflake } from '@/api/imports'
import { getPlanningSnapshot, type StoryBibleVersion } from '@/api/planning'

vi.mock('@/api/snowflake', () => ({ getLatestSnowflakePlan: vi.fn(), confirmSnowflakePlan: vi.fn() }))
vi.mock('@/api/planning', () => ({ getPlanningSnapshot: vi.fn() }))
vi.mock('@/api/imports', () => ({ resumeImportedSnowflake: vi.fn() }))
let wrapper: VueWrapper | undefined
let client: QueryClient
function plan(overrides: Partial<SnowflakePlan> = {}): SnowflakePlan {
  return { id: 'plan-1', projectId: 'p1', mode: 'NEW_STORY', provider: 'DEEPSEEK',
    status: 'SUCCEEDED', activeStage: 'PLOT', core: '自由文本故事核心', characters: '人物自由叙述，无字段清单',
    world: '世界使人物无法轻易选择', plot: '第一幕起因，第二幕受阻，第三幕承担代价', errorMessage: null,
    steps: { CORE: '自由文本故事核心', CHARACTER_SETTINGS: '人物自由叙述，无字段清单' },
    createdAt: '', updatedAt: '', ...overrides }
}
function render(props: { importId?: string; planningStatus?: 'CANCELLED' | 'FAILED' | 'GENERATING' } = {}) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  wrapper = mount(SnowflakePlanningPanel, { props: { projectId: 'p1', ...props },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return wrapper
}

describe('snowflake planning', () => {
  beforeEach(() => { vi.resetAllMocks(); vi.mocked(getPlanningSnapshot).mockResolvedValue(null); generationRequests.value = [] })
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
    generationRequests.value = [{ id: 'r1', projectId: 'p1', stage: 'SNOWFLAKE_PLANNING', status: 'RUNNING',
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

  it('marks unreadable saved prose as invalid, offers recovery and never confirms it', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'CANCELLED', sourceImportId: 'i1',
      steps: { CORE: '当前没有本地文件读取工具', SYNOPSIS: '依赖无效来源的梗概' },
      invalidSteps: { CORE: '读取失败', SYNOPSIS: '依赖无效来源' } }))
    const view = render({ importId: 'i1', planningStatus: 'CANCELLED' })
    await flushPromises()
    expect(view.findAll('summary')[0]!.text()).toContain('需重新生成')
    expect(view.findAll('summary')[1]!.text()).toContain('需重新生成')
    expect(view.text()).toContain('从第一个无效步骤重做')
    expect(view.text()).toContain('继续生成')
    client.setQueryData(['snowflake-plan', 'p1'], plan({ invalidSteps: { CORE: '读取失败' } }))
    await flushPromises()
    expect(view.text()).not.toContain('确认雪花规划')
    expect(confirmSnowflakePlan).not.toHaveBeenCalled()
  })

  it('resumes the exact stopped plan once and keeps successful text visible', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'CANCELLED', sourceImportId: 'i1' }))
    let finish!: (value: SnowflakePlan) => void
    vi.mocked(resumeImportedSnowflake).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const view = render({ importId: 'i1', planningStatus: 'CANCELLED' })
    const cache = vi.spyOn(client, 'setQueryData')
    await flushPromises()
    const button = view.findAll('button').find(item => item.text() === '继续生成')!
    await button.trigger('click')
    await button.trigger('click')
    expect(resumeImportedSnowflake).toHaveBeenCalledExactlyOnceWith('p1', 'i1', 'plan-1')
    expect(view.text()).toContain('正在继续')
    expect(view.text()).toContain('自由文本故事核心')
    const completed = plan({ sourceImportId: 'i1' })
    finish(completed)
    await flushPromises()
    expect(view.emitted('planningGenerated')).toHaveLength(1)
    expect(cache).toHaveBeenCalledWith(['snowflake-plan', 'p1'], completed)
  })

  it('offers finalization retry but not for another import or an active request', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'SUCCEEDED', sourceImportId: 'i1' }))
    const view = render({ importId: 'i1', planningStatus: 'FAILED' })
    await flushPromises()
    expect(view.text()).toContain('继续生成')
    await view.setProps({ importId: 'i2' })
    expect(view.text()).not.toContain('继续生成')
    await view.setProps({ importId: 'i1', planningStatus: 'GENERATING' })
    expect(view.text()).not.toContain('继续生成')
    expect(resumeImportedSnowflake).not.toHaveBeenCalled()
  })

  it('shows resume errors and allows another attempt without replacing saved text', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'FAILED', sourceImportId: 'i1' }))
    vi.mocked(resumeImportedSnowflake).mockRejectedValue(new Error('解析报告已被替换，请重新确认'))
    const view = render({ importId: 'i1', planningStatus: 'FAILED' })
    await flushPromises()
    await view.findAll('button').find(item => item.text() === '继续生成')!.trigger('click')
    await flushPromises()
    expect(view.text()).toContain('解析报告已被替换')
    expect(view.text()).toContain('自由文本故事核心')
    expect(view.findAll('button').find(item => item.text() === '继续生成')!.attributes('disabled')).toBeUndefined()
  })

  it('confirms the exact completed plan once, caches its source and emits only after save', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ sourceImportId: 'i1' }))
    let finish!: (value: StoryBibleVersion) => void
    vi.mocked(confirmSnowflakePlan).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const view = render({ importId: 'i1' })
    await flushPromises()
    expect(view.text()).toContain('待确认')
    const button = view.findAll('button').find(item => item.text() === '确认雪花规划')!
    await button.trigger('click')
    await button.trigger('click')
    expect(confirmSnowflakePlan).toHaveBeenCalledExactlyOnceWith('p1', 'plan-1')
    expect(view.emitted('planningConfirmed')).toBeUndefined()
    finish({ id: 'snapshot', sourceSnowflakeId: 'plan-1' } as StoryBibleVersion)
    await flushPromises()
    expect(view.text()).toContain('已确认')
    expect(view.emitted('planningConfirmed')).toHaveLength(1)
    expect(client.getQueryData(['planning-snapshot', 'p1'])).toMatchObject({ sourceSnowflakeId: 'plan-1' })
  })

  it('never offers confirmation for stopped runs, other imports or already confirmed runs', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ status: 'CANCELLED', sourceImportId: 'i1' }))
    const view = render({ importId: 'i1' })
    await flushPromises()
    expect(view.text()).not.toContain('确认雪花规划')
    client.setQueryData(['snowflake-plan', 'p1'], plan({ sourceImportId: 'i2' }))
    await flushPromises()
    expect(view.text()).not.toContain('确认雪花规划')
    client.setQueryData(['snowflake-plan', 'p1'], plan({ sourceImportId: 'i1' }))
    client.setQueryData(['planning-snapshot', 'p1'], { sourceSnowflakeId: 'plan-1' })
    await flushPromises()
    expect(view.text()).not.toContain('确认雪花规划')
    expect(confirmSnowflakePlan).not.toHaveBeenCalled()
  })

  it('keeps a failed confirmation retryable and does not unlock the outline', async () => {
    vi.mocked(getLatestSnowflakePlan).mockResolvedValue(plan({ sourceImportId: 'i1' }))
    vi.mocked(confirmSnowflakePlan).mockRejectedValue(new Error('原文或确认解析已改变'))
    const view = render({ importId: 'i1' })
    await flushPromises()
    await view.findAll('button').find(item => item.text() === '确认雪花规划')!.trigger('click')
    await flushPromises()
    expect(view.text()).toContain('原文或确认解析已改变')
    expect(view.emitted('planningConfirmed')).toBeUndefined()
    expect(view.findAll('button').find(item => item.text() === '确认雪花规划')!.attributes('disabled')).toBeUndefined()
  })
})

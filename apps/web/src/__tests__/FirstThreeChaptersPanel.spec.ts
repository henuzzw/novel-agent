import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FirstThreeChaptersPanel from '@/components/FirstThreeChaptersPanel.vue'
import { checkFirstThreeChapters, getFirstThreeChapters, type OpeningReport, type OpeningView } from '@/api/firstThreeChapters'
import { openingReport, openingView } from './FirstThreeChaptersFixtures'
vi.mock('@/api/firstThreeChapters', () => ({ checkFirstThreeChapters: vi.fn(), getFirstThreeChapters: vi.fn() }))
let wrappers: VueWrapper[] = []
function render() {
  const wrapper = mount(FirstThreeChaptersPanel, { props: { projectId: 'project-1', provider: 'DEEPSEEK' } })
  wrappers.push(wrapper)
  return wrapper
}
function checkButton(wrapper: VueWrapper) { return wrapper.findAll('button').find(b => b.text().includes('检查完整三章'))! }
describe('FirstThreeChaptersPanel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getFirstThreeChapters).mockResolvedValue(openingView())
    vi.mocked(checkFirstThreeChapters).mockResolvedValue(openingReport())
  })
  afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers = [] })
  it('reads every complete body and exposes source/version without an automatic check', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.opening-body')).toHaveLength(3)
    expect(wrapper.findAll('.opening-body')[2]!.text()).toContain('第3章结尾。')
    expect(wrapper.text()).toContain('未完成检查')
    expect(wrapper.findAll('select')[0]!.element.value).toBe('m1')
    expect(checkFirstThreeChapters).not.toHaveBeenCalled()
  })
  it('checks only on click with exact versions, author budget and source fingerprint', async () => {
    const wrapper = render()
    await flushPromises()
    const view = openingView()
    view.latestReport = view.latestValidReport = openingReport(view)
    vi.mocked(getFirstThreeChapters).mockResolvedValue(view)
    await checkButton(wrapper).trigger('click')
    await flushPromises()
    expect(checkFirstThreeChapters).toHaveBeenCalledExactlyOnceWith('project-1', {
      manuscriptIds: ['m1', 'm2', 'm3'], provider: 'DEEPSEEK', instruction: '', expectedFingerprint: 'hash-1', maxInputTokens: 54000,
    })
    expect(wrapper.text()).toContain('兑现需作者复核')
    expect(wrapper.text()).not.toContain('通过')
    expect(wrapper.emitted('checked')).toHaveLength(1)
  })
  it('selects a historical version without a check and refetches its budget', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('select')[0]!.setValue('old1')
    await flushPromises()
    expect(getFirstThreeChapters).toHaveBeenLastCalledWith('project-1', 'DEEPSEEK', ['old1', 'm2', 'm3'], '')
    expect(checkFirstThreeChapters).not.toHaveBeenCalled()
  })
  it('disables incomplete sources and insufficient author or context budget', async () => {
    const view = openingView()
    view.available = false
    view.source.unavailableReasons = ['缺少第三章完整正文']
    vi.mocked(getFirstThreeChapters).mockResolvedValueOnce(view)
    const wrapper = render()
    await flushPromises()
    expect(checkButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('缺少第三章完整正文')
    await wrapper.findAll('button')[0]!.trigger('click')
    await flushPromises()
    await wrapper.find('input[type=number]').setValue(1)
    expect(checkButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('预算不足')
    expect(checkFirstThreeChapters).not.toHaveBeenCalled()
  })
  it('requires a refreshed budget when author instruction changes', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.find('textarea').setValue('检查因果')
    expect(checkButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(getFirstThreeChapters).toHaveBeenLastCalledWith('project-1', 'DEEPSEEK', ['m1', 'm2', 'm3'], '检查因果')
    expect(checkButton(wrapper).attributes('disabled')).toBeUndefined()
  })
  it('marks stale reports and preserves their original sources for inspection', async () => {
    const view = openingView()
    view.latestReport = { ...openingReport(view), current: false, fingerprint: 'old' }
    vi.mocked(getFirstThreeChapters).mockResolvedValue(view)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('报告已过期')
    expect(wrapper.text()).toContain('报告对应的原正文与写作计划')
    expect(wrapper.text()).toContain('第3章结尾。')
  })
  it('keeps local source checks explicitly unassessed', async () => {
    const view = openingView()
    view.latestReport = view.latestValidReport = { ...openingReport(view), provider: 'LOCAL_TEMPLATE', reviewMode: 'RULES_ONLY' }
    vi.mocked(getFirstThreeChapters).mockResolvedValue(view)
    const wrapper = render()
    await wrapper.setProps({ provider: 'LOCAL_TEMPLATE' })
    await flushPromises()
    expect(wrapper.text()).toContain('未完成文学检查')
    expect(checkFirstThreeChapters).not.toHaveBeenCalled()
  })
  it('shows a failed check and waits for explicit retry', async () => {
    vi.mocked(checkFirstThreeChapters).mockRejectedValue(new Error('来源已变化'))
    const wrapper = render()
    await flushPromises()
    await checkButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[role=alert]').text()).toBe('来源已变化')
    expect(checkFirstThreeChapters).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('checked')).toBeUndefined()
  })
  it('ignores late reports from another project', async () => {
    let finish!: (value: OpeningReport) => void
    vi.mocked(checkFirstThreeChapters).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const wrapper = render()
    await flushPromises()
    await checkButton(wrapper).trigger('click')
    await wrapper.setProps({ projectId: 'project-2' })
    await flushPromises()
    finish(openingReport())
    await flushPromises()
    expect(wrapper.emitted('checked')).toBeUndefined()
    expect(wrapper.text()).not.toContain('兑现需作者复核')
  })
  it('ignores a late source read after provider change', async () => {
    let finish!: (value: OpeningView) => void
    vi.mocked(getFirstThreeChapters).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const wrapper = render()
    await wrapper.setProps({ provider: 'LOCAL_TEMPLATE' })
    await flushPromises()
    finish({ ...openingView(), available: false })
    await flushPromises()
    expect(wrapper.text()).toContain('未评估文学效果')
    expect(wrapper.findAll('.opening-body')).toHaveLength(3)
  })
})

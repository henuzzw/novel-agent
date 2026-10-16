import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import PublishedManuscriptExportButton from '@/components/PublishedManuscriptExportButton.vue'
import { getPublishedManuscriptExport } from '@/api/writing'

vi.mock('@/api/writing', () => ({ getPublishedManuscriptExport: vi.fn() }))
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.clearAllMocks() })

it('downloads all returned chapters with a safe name and cleans up the download link', async () => {
  vi.mocked(getPublishedManuscriptExport).mockResolvedValue('# 书名\n\n## 第一卷\n\n### 第 1 章\n\n正文')
  const create = vi.fn(() => 'blob:book')
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    expect(this.download).toBe('校园_故事-已完成正文.md')
    expect(this.href).toBe('blob:book')
  })
  const wrapper = mount(PublishedManuscriptExportButton, { props: { projectId: 'project', projectName: '校园/故事' } })
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(getPublishedManuscriptExport).toHaveBeenCalledExactlyOnceWith('project')
  expect(create).toHaveBeenCalledTimes(1)
  expect(click).toHaveBeenCalledTimes(1)
  expect(document.querySelector('a[download]')).toBeNull()
  expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  expect(wrapper.get('button').attributes('aria-label')).toBe('导出已完成正文')
  wrapper.unmount()
})

it('shows empty or failed export reason without downloading', async () => {
  vi.mocked(getPublishedManuscriptExport).mockRejectedValue(new Error('暂无已发布且记忆整理完成的正文可导出'))
  const wrapper = mount(PublishedManuscriptExportButton, { props: { projectId: 'project' } })
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('暂无已发布且记忆整理完成')
  expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  await wrapper.get('[aria-label="关闭导出错误"]').trigger('click')
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  wrapper.unmount()
})

it('prevents repeated clicks while exporting and does not download another project after navigation', async () => {
  let finish!: (value: string) => void
  vi.mocked(getPublishedManuscriptExport).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const create = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() })
  const wrapper = mount(PublishedManuscriptExportButton, { props: { projectId: 'project' } })
  await wrapper.get('button').trigger('click')
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  expect(wrapper.get('button').attributes('aria-label')).toContain('正在导出')
  expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
  await wrapper.get('button').trigger('click')
  expect(getPublishedManuscriptExport).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ projectId: 'other' })
  finish('# 旧项目'); await flushPromises()
  expect(create).not.toHaveBeenCalled()
  wrapper.unmount()
})

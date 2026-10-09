import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { planningViews, readWorkspaceChapter, readWorkspaceChoice, useWorkspaceChapter, useWorkspaceChoice, workspaceSections, writingViews } from '@/composables/useWorkspaceLocation'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

const Workspace = defineComponent({
  setup() {
    const section = useWorkspaceChoice('section', workspaceSections, 'outline')
    const planning = useWorkspaceChoice('planning', planningViews, 'directions')
    const chapter = useWorkspaceChapter()
    const writing = useWorkspaceChoice('writing', writingViews, 'manuscript')
    const dirty = ref(false)
    useUnsavedChanges(dirty, ['section', 'planning', 'chapter'])
    function openChapter() { section.value = 'writing'; chapter.value = 3; writing.value = 'manuscript' }
    return { section, planning, chapter, writing, dirty, openChapter }
  },
  template: `<div><output>{{ section }}/{{ planning }}/{{ chapter }}/{{ writing }}</output>
    <button @click="planning = 'bible'">圣经</button><button @click="section = 'runs'">任务</button>
    <button @click="openChapter">打开章节</button><button @click="dirty = true">编辑</button></div>`,
})

afterEach(() => vi.restoreAllMocks())

async function createWorkspace(url: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:projectId', component: Workspace }] })
  await router.push(url)
  const wrapper = mount(RouterView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('workspace URL state', () => {
  it('validates unknown, repeated and malformed query values', () => {
    expect(readWorkspaceChoice('bible', planningViews, 'directions')).toBe('bible')
    for (const value of [undefined, null, 'unknown', ['bible'], '<script>']) {
      expect(readWorkspaceChoice(value, planningViews, 'directions')).toBe('directions')
    }
    for (const value of [undefined, null, ['2'], '0', '-1', '2.5', '999999', '2e2', '01']) {
      expect(readWorkspaceChapter(value)).toBe(1)
    }
    expect(readWorkspaceChapter('24')).toBe(24)
  })

  it('restores nested tabs and chapters when mounted from a URL', async () => {
    const { wrapper } = await createWorkspace('/projects/a?section=writing&planning=bible&chapter=7&writing=quality')
    expect(wrapper.find('output').text()).toBe('writing/bible/7/quality')
    wrapper.unmount()
  })

  it('merges multi-field navigation, preserves other queries, and supports back', async () => {
    const { wrapper, router } = await createWorkspace('/projects/a?section=outline&planning=bible&filter=keep')
    await wrapper.findAll('button')[2]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ section: 'writing', planning: 'bible', chapter: '3', writing: 'manuscript', filter: 'keep' })
    expect(wrapper.find('output').text()).toBe('writing/bible/3/manuscript')
    router.back()
    await vi.waitFor(() => expect(wrapper.find('output').text()).toBe('outline/bible/1/manuscript'))
    wrapper.unmount()
  })

  it('keeps URL and current view unchanged when abandoning edits is declined', async () => {
    const { wrapper, router } = await createWorkspace('/projects/a?section=outline')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.findAll('button')[3]!.trigger('click')
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.query.section).toBe('outline')
    expect(wrapper.find('output').text()).toBe('outline/directions/1/manuscript')
    wrapper.unmount()
  })

  it('works in isolated components without a router', async () => {
    const wrapper = mount(Workspace)
    await wrapper.findAll('button')[2]!.trigger('click')
    expect(wrapper.find('output').text()).toBe('writing/directions/3/manuscript')
    wrapper.unmount()
  })
})

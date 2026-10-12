<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { Activity, BookOpen, GitBranch, ListTree, PenLine, Settings, Upload } from 'lucide-vue-next'
import { getProject } from '@/api/projects'
import type { AutomationChapterTarget } from '@/api/automation'
import { navigateWorkspaceButtons } from '@/lib/workspace-keyboard'
import { planningViews, useWorkspaceChapter, useWorkspaceChoice, workspaceSections, writingViews } from '@/composables/useWorkspaceLocation'
import GenerationStatusPanel from '@/components/GenerationStatusPanel.vue'
import OutlinePanel from '@/components/OutlinePanel.vue'
import StoryMaterialsPanel from '@/components/StoryMaterialsPanel.vue'
import WorkImportPanel from '@/components/WorkImportPanel.vue'
import AgentRunPanel from '@/components/AgentRunPanel.vue'
import WritingWorkbench from '@/components/WritingWorkbench.vue'
import WritingStylePanel from '@/components/WritingStylePanel.vue'
import ProjectSettingsPanel from '@/components/ProjectSettingsPanel.vue'
import ReaderExperiencePanel from '@/components/ReaderExperiencePanel.vue'
import BookScanPanel from '@/components/BookScanPanel.vue'

const props = defineProps<{ projectId: string }>()
const route = useRoute()
const generationBusy = ref(false)
const activeSection = useWorkspaceChoice('section', workspaceSections, 'imports')
const planningView = useWorkspaceChoice('planning', planningViews, 'outline')
const targetChapter = useWorkspaceChapter()
const targetWritingView = useWorkspaceChoice('writing', writingViews, 'manuscript')
function openAutomationChapter(target: AutomationChapterTarget) {
  targetChapter.value = target.chapter
  targetWritingView.value = target.mode
  activeSection.value = 'writing'
}
const projectQuery = useQuery({
  queryKey: computed(() => ['project', props.projectId]),
  queryFn: () => getProject(props.projectId),
  refetchInterval: query => query.state.data?.name === '待生成书名' ? 3000 : false,
})
const navigation = [
  { value: 'imports', label: '导入', icon: Upload },
  { value: 'outline', label: '大纲', icon: ListTree },
  { value: 'materials', label: '资料', icon: BookOpen },
  { value: 'experience', label: '伏笔和承诺', icon: GitBranch },
  { value: 'runs', label: '任务', icon: Activity },
  { value: 'settings', label: '设置', icon: Settings },
] as const
</script>

<template>
  <div class="workspace">
    <aside class="workspace-nav" aria-label="项目导航">
      <button v-for="item in navigation" :key="item.value" type="button"
        :class="{ active: activeSection === item.value }"
        :aria-current="activeSection === item.value ? 'page' : undefined" @click="activeSection = item.value">
        <component :is="item.icon" :size="18" aria-hidden="true" /><span>{{ item.label }}</span>
      </button>
    </aside>
    <section v-if="projectQuery.isPending.value" class="workspace-content loading-block">正在加载项目…</section>
    <section v-else-if="projectQuery.isError.value" class="workspace-content status-panel error-panel">
      <strong>项目加载失败</strong><span>{{ projectQuery.error.value?.message }}</span>
    </section>
    <section v-else class="workspace-content">
      <header class="workspace-header">
        <div><span class="eyebrow">正史 v{{ projectQuery.data.value?.currentCanonVersion }}</span><h1>{{ projectQuery.data.value?.name }}</h1></div>
        <span class="save-status">{{ generationBusy ? '生成请求中' : '已保存' }}</span>
      </header>
      <GenerationStatusPanel :key="projectId" :project-id="projectId" @busy="generationBusy = $event" @open-tasks="activeSection = 'runs'" />
      <WritingWorkbench v-if="activeSection === 'writing'" :key="projectId" :project-id="projectId" />
      <StoryMaterialsPanel v-else-if="activeSection === 'materials'" :key="projectId" :project-id="projectId" />
      <div v-else-if="activeSection === 'experience'" :key="projectId">
        <ReaderExperiencePanel :project-id="projectId" /><BookScanPanel :project-id="projectId" />
      </div>
      <WorkImportPanel v-else-if="activeSection === 'imports'" :key="projectId" :project-id="projectId"
        :auto-analyze="route.query.analyze === '1'" @planning-confirmed="activeSection = 'outline'" />
      <AgentRunPanel v-else-if="activeSection === 'runs'" :key="projectId" :project-id="projectId" @open-chapter="openAutomationChapter" />
      <ProjectSettingsPanel v-else-if="activeSection === 'settings'" :key="projectId" :project-id="projectId" />
      <div v-else>
        <div class="planning-tabs" role="group" @keydown="navigateWorkspaceButtons" aria-label="大纲与写作">
          <button type="button" :aria-pressed="planningView === 'outline'" :class="{ active: planningView === 'outline' }" @click="planningView = 'outline'"><GitBranch :size="16" />分层大纲</button>
          <button type="button" :aria-pressed="planningView === 'style'" :class="{ active: planningView === 'style' }" @click="planningView = 'style'"><PenLine :size="16" />风格试写</button>
          <button type="button" @click="activeSection = 'writing'; targetWritingView = 'manuscript'"><PenLine :size="16" />章节写作</button>
        </div>
        <OutlinePanel v-if="planningView === 'outline'" :project-id="projectId" @choose-style="planningView = 'style'" />
        <WritingStylePanel v-else :project-id="projectId" />
      </div>
    </section>
  </div>
</template>

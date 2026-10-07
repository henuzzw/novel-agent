<script setup lang="ts">
import GlobalModelSettingsPanel from '@/components/GlobalModelSettingsPanel.vue'
import CreativeStrategyPanel from '@/components/CreativeStrategyPanel.vue'
import { RouterLink } from 'vue-router'
import { FilePenLine } from 'lucide-vue-next'
defineProps<{ projectId: string }>()
</script>
<template>
  <div class="project-settings">
    <CreativeStrategyPanel :key="projectId" :project-id="projectId" />
    <GlobalModelSettingsPanel />
    <section class="prompt-settings-entry">
      <h2>全局提示词</h2>
      <RouterLink class="button secondary prompt-settings-link" to="/settings/prompts"><FilePenLine :size="16" />提示词管理</RouterLink>
    </section>
  </div>
</template>

<style scoped>
.project-settings { display: grid; gap: 24px; min-width: 0; }
.prompt-settings-entry { border-top: 1px solid #dbe2e6; padding-top: 20px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.prompt-settings-entry h2 { font-size: 16px; margin: 0; }
.prompt-settings-link { display: inline-flex; align-items: center; gap: 7px; text-decoration: none; }
</style>

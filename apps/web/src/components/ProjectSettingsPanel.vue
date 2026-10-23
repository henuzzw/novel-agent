<script setup lang="ts">
import GlobalModelSettingsPanel from '@/components/GlobalModelSettingsPanel.vue'
import CreativeStrategyPanel from '@/components/CreativeStrategyPanel.vue'
import CreativeBoundaryPanel from '@/components/CreativeBoundaryPanel.vue'
import CreativeIntentPanel from '@/components/CreativeIntentPanel.vue'
defineProps<{ projectId: string }>()
</script>
<template>
  <div class="project-settings">
    <CreativeIntentPanel :key="projectId" :project-id="projectId" />
    <CreativeStrategyPanel :key="projectId" :project-id="projectId" />
    <CreativeBoundaryPanel :key="projectId" :project-id="projectId" />
    <GlobalModelSettingsPanel />
  </div>
</template>

<style scoped>
.project-settings { display: grid; gap: 24px; min-width: 0; }
</style>

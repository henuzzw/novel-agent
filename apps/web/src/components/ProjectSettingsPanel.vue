<script setup lang="ts">
import GlobalModelSettingsPanel from '@/components/GlobalModelSettingsPanel.vue'
import CreativeStrategyPanel from '@/components/CreativeStrategyPanel.vue'
defineProps<{ projectId: string }>()
</script>
<template>
  <div class="project-settings">
    <CreativeStrategyPanel :key="projectId" :project-id="projectId" />
    <GlobalModelSettingsPanel />
  </div>
</template>

<style scoped>
.project-settings { display: grid; gap: 24px; min-width: 0; }
</style>

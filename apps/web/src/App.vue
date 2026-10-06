<script setup lang="ts">
import { Activity, BookOpenText, Cpu, RefreshCw } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import GlobalModelSettingsPanel from '@/components/GlobalModelSettingsPanel.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
const { settings, label, loading, loadError, load } = useGlobalModelSettings()
const showSettings = ref(false)
onMounted(load)
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/projects" aria-label="小说 Agent 项目列表">
        <BookOpenText :size="21" aria-hidden="true" />
        <span>小说 Agent</span>
      </RouterLink>
      <div class="topbar-actions">
        <button class="global-model-button" type="button" title="全局模型设置" aria-label="全局模型设置" :aria-expanded="showSettings" @click="showSettings = !showSettings"><Cpu :size="18" /><span>{{ label }}</span></button>
        <button class="icon-button" type="button" title="任务中心" aria-label="任务中心">
          <Activity :size="18" aria-hidden="true" />
        </button>
        <div class="avatar" title="当前为本地开发用户">作</div>
      </div>
    </header>
    <GlobalModelSettingsPanel v-if="showSettings && settings" />
    <main class="app-main">
      <p v-if="loading && !settings" role="status">正在读取模型设置…</p>
      <p v-else-if="loadError" class="form-error" role="alert">{{ loadError }}<button type="button" class="icon-button" title="重新读取设置" aria-label="重新读取设置" @click="load"><RefreshCw :size="18" /></button></p>
      <RouterView v-else-if="settings" />
    </main>
  </div>
</template>
<style scoped>
.global-model-button { display: inline-flex; align-items: center; gap: 7px; border: 0; background: transparent; color: #38464d; cursor: pointer; min-width: 0; padding: 6px; font-size: 13px; }
.global-model-button span { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.global-model-button svg { flex-shrink: 0; }
@media (max-width: 640px) { .global-model-button span { max-width: 130px; } }
</style>

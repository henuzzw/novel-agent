<script setup lang="ts">
import { Activity, BookOpenText } from 'lucide-vue-next'
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/projects" aria-label="小说 Agent 项目列表">
        <BookOpenText :size="21" aria-hidden="true" />
        <span>小说 Agent</span>
      </RouterLink>
      <div class="topbar-actions">
        <button class="icon-button" type="button" title="任务中心" aria-label="任务中心">
          <Activity :size="18" aria-hidden="true" />
        </button>
        <div class="avatar" title="当前为本地开发用户">作</div>
      </div>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

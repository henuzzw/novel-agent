<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight, FileText, Lightbulb, Plus, Upload } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'

import { listProjects } from '@/api/projects'

const projectsQuery = useQuery({
  queryKey: ['projects'],
  queryFn: listProjects,
})

const entryLabels = {
  IDEA: '从想法开始',
  MANUSCRIPT: '已有作品',
  MATERIALS: '大纲与设定',
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="page project-list-page">
    <div class="page-toolbar">
      <div>
        <h1>小说项目</h1>
        <p>继续创作，或从已有资料建立一个新项目。</p>
      </div>
      <RouterLink class="button primary" to="/projects/new">
        <Plus :size="17" aria-hidden="true" />
        新建项目
      </RouterLink>
    </div>

    <div v-if="projectsQuery.isPending.value" class="project-table skeleton-list" aria-label="正在加载项目">
      <div v-for="index in 3" :key="index" class="skeleton-row"></div>
    </div>

    <div v-else-if="projectsQuery.isError.value" class="status-panel error-panel">
      <strong>项目加载失败</strong>
      <span>{{ projectsQuery.error.value?.message }}</span>
      <button class="button secondary" type="button" @click="projectsQuery.refetch()">重新加载</button>
    </div>

    <div v-else-if="projectsQuery.data.value?.length" class="project-table">
      <div class="project-table-head">
        <span>项目</span>
        <span>创作入口</span>
        <span>正史版本</span>
        <span>最近编辑</span>
        <span aria-hidden="true"></span>
      </div>
      <RouterLink
        v-for="project in projectsQuery.data.value"
        :key="project.id"
        class="project-row"
        :to="`/projects/${project.id}`"
      >
        <span class="project-title-cell">
          <FileText :size="18" aria-hidden="true" />
          <strong>{{ project.name }}</strong>
        </span>
        <span>{{ entryLabels[project.entryMode] }}</span>
        <span>v{{ project.currentCanonVersion }}</span>
        <span>{{ formatDate(project.updatedAt) }}</span>
        <ArrowRight :size="17" aria-hidden="true" />
      </RouterLink>
    </div>

    <div v-else class="empty-state">
      <div class="empty-copy">
        <h2>还没有小说项目</h2>
        <p>选择一种最接近你现状的方式开始。</p>
      </div>
      <div class="entry-actions">
        <RouterLink class="entry-action" to="/projects/new?mode=IDEA">
          <Lightbulb :size="20" aria-hidden="true" />
          <span><strong>输入一个故事想法</strong><small>从灵感生成方向和大纲</small></span>
          <ArrowRight :size="17" aria-hidden="true" />
        </RouterLink>
        <RouterLink class="entry-action" to="/projects/new?mode=MANUSCRIPT">
          <Upload :size="20" aria-hidden="true" />
          <span><strong>导入已有作品</strong><small>整理正文并继续创作</small></span>
          <ArrowRight :size="17" aria-hidden="true" />
        </RouterLink>
        <RouterLink class="entry-action" to="/projects/new?mode=MATERIALS">
          <FileText :size="20" aria-hidden="true" />
          <span><strong>导入大纲与设定</strong><small>整理人物、世界观和情节</small></span>
          <ArrowRight :size="17" aria-hidden="true" />
        </RouterLink>
      </div>
    </div>
  </section>
</template>

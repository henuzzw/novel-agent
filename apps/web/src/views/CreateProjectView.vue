<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ArrowLeft, BookOpen, FileText, Lightbulb, Sparkles, Upload } from 'lucide-vue-next'
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { createProject, type CreativeStrategy, type CreateProjectInput, type EntryMode } from '@/api/projects'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const initialMode = ['IDEA', 'MANUSCRIPT', 'MATERIALS'].includes(String(route.query.mode))
  ? (route.query.mode as EntryMode)
  : 'IDEA'

const form = reactive({
  name: '',
  entryMode: initialMode,
  creativeStrategy: 'STANDARD' as CreativeStrategy,
  premise: '',
  genresText: '青春校园',
  protagonistBrief: '',
  centralConflict: '',
  tonesText: '温暖, 轻松',
  targetWords: 120000,
})
const errorMessage = ref('')

const modes: Array<{ value: EntryMode; label: string; description: string; icon: typeof Lightbulb }> = [
  { value: 'IDEA', label: '故事想法', description: '从一个灵感开始规划', icon: Lightbulb },
  { value: 'MANUSCRIPT', label: '已有作品', description: '创建后进入正文导入', icon: Upload },
  { value: 'MATERIALS', label: '大纲与设定', description: '创建后整理创作资料', icon: FileText },
]

const isIdeaMode = computed(() => form.entryMode === 'IDEA')

function splitTags(value: string) {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

const createMutation = useMutation({
  mutationFn: createProject,
  onSuccess: async (project) => {
    await queryClient.invalidateQueries({ queryKey: ['projects'] })
    await router.push(`/projects/${project.id}`)
  },
  onError: (error: Error) => {
    errorMessage.value = error.message
  },
})

function submit() {
  errorMessage.value = ''
  if (!form.name.trim()) {
    errorMessage.value = '请输入项目名称。'
    return
  }

  if (isIdeaMode.value && (!form.premise.trim() || !form.protagonistBrief.trim() || !form.centralConflict.trim())) {
    errorMessage.value = '请填写故事创意、主角简述和核心冲突。'
    return
  }

  const input: CreateProjectInput = {
    name: form.name.trim(),
    entryMode: form.entryMode,
    creativeStrategy: form.creativeStrategy,
  }

  if (isIdeaMode.value) {
    input.creativeIntent = {
      premise: form.premise.trim(),
      genres: splitTags(form.genresText),
      protagonistBrief: form.protagonistBrief.trim(),
      centralConflict: form.centralConflict.trim(),
      tones: splitTags(form.tonesText),
      targetWords: form.targetWords,
      mustHave: [],
      avoid: [],
      stylePreferences: [],
    }
  }

  createMutation.mutate(input)
}
</script>

<template>
  <section class="page create-page">
    <div class="create-header">
      <RouterLink class="back-link" to="/projects"><ArrowLeft :size="17" />返回项目</RouterLink>
      <div>
        <h1>创建小说项目</h1>
        <p>先提供最必要的信息，复杂设定可以在创作过程中逐步补充。</p>
      </div>
    </div>

    <form class="create-form" @submit.prevent="submit">
      <fieldset class="form-section">
        <legend>创作入口</legend>
        <div class="mode-segment" role="radiogroup" aria-label="创作入口">
          <label v-for="mode in modes" :key="mode.value" :class="{ selected: form.entryMode === mode.value }">
            <input v-model="form.entryMode" type="radio" :value="mode.value" />
            <component :is="mode.icon" :size="19" aria-hidden="true" />
            <span><strong>{{ mode.label }}</strong><small>{{ mode.description }}</small></span>
          </label>
        </div>
      </fieldset>

      <fieldset class="form-section">
        <legend>项目信息</legend>
        <label class="field">
          <span>项目名称</span>
          <input v-model="form.name" maxlength="200" placeholder="例如：钟楼来信" autofocus />
        </label>
      </fieldset>

      <fieldset class="form-section">
        <legend>创作策略</legend>
        <div class="creative-strategy-segment" role="radiogroup" aria-label="创作策略">
          <label :class="{ selected: form.creativeStrategy === 'STANDARD' }">
            <input v-model="form.creativeStrategy" type="radio" name="creative-strategy" value="STANDARD" :disabled="createMutation.isPending.value" />
            <BookOpen :size="18" aria-hidden="true" /><span>标准创作</span>
          </label>
          <label :class="{ selected: form.creativeStrategy === 'FANQIE_GRIPPING' }">
            <input v-model="form.creativeStrategy" type="radio" name="creative-strategy" value="FANQIE_GRIPPING" :disabled="createMutation.isPending.value" />
            <Sparkles :size="18" aria-hidden="true" /><span>番茄强开篇</span>
          </label>
        </div>
      </fieldset>

      <fieldset v-if="isIdeaMode" class="form-section">
        <legend>创作意图</legend>
        <label class="field full-span">
          <span>一句话创意</span>
          <textarea v-model="form.premise" rows="3" maxlength="2000" placeholder="这是一个什么故事？"></textarea>
        </label>
        <label class="field">
          <span>小说类型</span>
          <input v-model="form.genresText" placeholder="用逗号分隔" />
        </label>
        <label class="field">
          <span>故事基调</span>
          <input v-model="form.tonesText" placeholder="例如：温暖, 轻松" />
        </label>
        <label class="field full-span">
          <span>主角简述</span>
          <textarea v-model="form.protagonistBrief" rows="3" maxlength="2000" placeholder="主角是谁，她想得到什么？"></textarea>
        </label>
        <label class="field full-span">
          <span>核心冲突</span>
          <textarea v-model="form.centralConflict" rows="3" maxlength="2000" placeholder="什么阻碍主角实现目标？"></textarea>
        </label>
        <label class="field">
          <span>目标字数</span>
          <input v-model.number="form.targetWords" type="number" min="1000" max="10000000" step="1000" />
        </label>
      </fieldset>

      <div v-else class="next-step-note">
        <BookOpen :size="20" aria-hidden="true" />
        <span>项目创建后将进入资料导入向导，原始文件不会被修改。</span>
      </div>

      <div v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</div>

      <div class="form-actions">
        <RouterLink class="button secondary" to="/projects">取消</RouterLink>
        <button class="button primary" type="submit" :disabled="createMutation.isPending.value">
          {{ createMutation.isPending.value ? '正在创建…' : '创建项目' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.creative-strategy-segment { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.creative-strategy-segment label { display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 44px; padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 4px; cursor: pointer; }
.creative-strategy-segment label.selected { border-color: #176b63; background: #f3faf8; }
.creative-strategy-segment label:focus-within { outline: 2px solid #176b63; outline-offset: 2px; }
.creative-strategy-segment input { width: auto; margin: 0; accent-color: #176b63; }
.creative-strategy-segment svg { flex-shrink: 0; }
.creative-strategy-segment span { overflow-wrap: anywhere; }
@media (max-width: 480px) { .creative-strategy-segment { grid-template-columns: 1fr; } }
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Plus } from 'lucide-vue-next'
import { addEntityAlias, getEntityState, listEntityAliases, listEntityMentions, type EntityAlias } from '@/api/writing'

const props = defineProps<{ projectId: string; entityId: string }>()
const client = useQueryClient()
const alias = ref('')
const aliasType = ref<EntityAlias['aliasType']>('NICKNAME')
const error = ref('')
watch(() => [props.projectId, props.entityId], () => { alias.value = ''; error.value = '' })
const states = useQuery({ queryKey: computed(() => ['canon-entity-state', props.projectId, props.entityId]),
  queryFn: () => getEntityState(props.projectId, props.entityId) })
const aliases = useQuery({ queryKey: computed(() => ['canon-entity-aliases', props.projectId, props.entityId]),
  queryFn: () => listEntityAliases(props.projectId, props.entityId) })
const mentions = useQuery({ queryKey: computed(() => ['canon-entity-mentions', props.projectId, props.entityId]),
  queryFn: () => listEntityMentions(props.projectId, props.entityId) })
const add = useMutation({
  mutationFn: async () => {
    if (!alias.value.trim()) throw new Error('请输入别名。')
    const projectId = props.projectId, entityId = props.entityId
    await addEntityAlias(projectId, entityId, alias.value.trim(), aliasType.value)
    return { projectId, entityId }
  },
  onSuccess: ({ projectId, entityId }) => {
    client.invalidateQueries({ queryKey: ['canon-entity-aliases', projectId, entityId] })
    if (props.projectId === projectId && props.entityId === entityId) { alias.value = ''; error.value = '' }
  },
  onError: (reason: Error) => { error.value = reason.message },
})
function stateLabel(value: string) {
  return ({ location: '所在地点', life_status: '生命状态', physical_status: '身体状态', goal: '当前目标', holder: '持有人' } as Record<string, string>)[value] ?? value
}
function aliasLabel(value: string) {
  return ({ NICKNAME: '昵称', TITLE: '称谓', FORMER_NAME: '曾用名', OTHER: '其他' } as Record<string, string>)[value] ?? value
}
function display(value: unknown) { return value == null ? '未知' : typeof value === 'string' ? value : JSON.stringify(value) }
</script>

<template>
  <section aria-label="正文事实与别名">
    <section class="material-section">
      <h3>正文当前状态</h3>
      <p v-if="states.isPending.value" role="status">正在读取状态…</p>
      <p v-else-if="states.isError.value" class="form-error" role="alert">状态读取失败：{{ states.error.value?.message }}</p>
      <div v-else-if="states.data.value?.length" class="state-table">
        <div v-for="item in states.data.value" :key="item.changeId"><strong>{{ stateLabel(item.field) }}</strong><span>{{ display(item.value) }}</span><small>第 {{ item.chapterNumber }} 章 · 正史 V{{ item.canonVersionFrom }}</small><blockquote v-if="item.evidence">{{ item.evidence }}</blockquote></div>
      </div>
      <p v-else class="quiet-text">暂无正文正史状态。</p>
    </section>
    <section class="material-section">
      <h3>确认别名</h3>
      <p v-if="aliases.isPending.value" role="status">正在读取别名…</p>
      <p v-else-if="aliases.isError.value" class="form-error" role="alert">别名读取失败：{{ aliases.error.value?.message }}</p>
      <div v-else class="alias-list"><span v-for="item in aliases.data.value ?? []" :key="item.id">{{ item.alias }}<small>{{ aliasLabel(item.aliasType) }}</small></span><span v-if="!aliases.data.value?.length" class="quiet-text">暂无别名</span></div>
      <form class="alias-form" @submit.prevent="add.mutate()">
        <input v-model="alias" maxlength="200" placeholder="新增别名" aria-label="新增别名" />
        <select v-model="aliasType" aria-label="别名类型"><option value="NICKNAME">昵称</option><option value="TITLE">称谓</option><option value="FORMER_NAME">曾用名</option><option value="OTHER">其他</option></select>
        <button class="button secondary" type="submit" :disabled="add.isPending.value"><Plus :size="15" />添加</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </section>
    <section class="material-section">
      <h3>历史提及</h3>
      <p v-if="mentions.isPending.value" role="status">正在读取历史提及…</p>
      <p v-else-if="mentions.isError.value" class="form-error" role="alert">历史提及读取失败：{{ mentions.error.value?.message }}</p>
      <div v-else-if="mentions.data.value?.length" class="mention-list"><div v-for="item in mentions.data.value" :key="item.id"><strong>{{ item.mention }}</strong><span>{{ item.resolutionMethod }} · 置信度 {{ Math.round(item.confidence * 100) }}%</span><small v-if="item.evidence">{{ item.evidence }}</small></div></div>
      <p v-else class="quiet-text">暂无解析记录。</p>
    </section>
  </section>
</template>
<style scoped>
.state-table blockquote { grid-column: 1 / -1; margin: 0; overflow-wrap: anywhere; }
</style>

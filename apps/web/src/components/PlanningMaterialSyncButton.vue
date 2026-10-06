<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { RefreshCw } from 'lucide-vue-next'
import { ref } from 'vue'
import { syncPlanningMaterials } from '@/api/planningMaterials'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ synced: [] }>()
const queryClient = useQueryClient()
const busy = ref(false)
const error = ref('')
async function sync() {
  if (busy.value) return
  const project = props.projectId
  busy.value = true; error.value = ''
  try {
    await syncPlanningMaterials(project)
    await queryClient.invalidateQueries({ predicate: query => query.queryKey.includes(project) })
    if (project === props.projectId) emit('synced')
  } catch (reason) { if (project === props.projectId) error.value = (reason as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <div class="planning-sync">
    <button class="button secondary" type="button" :disabled="busy" @click="sync"><RefreshCw :size="16" />{{ busy ? '正在同步…' : '同步已发布规划' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.planning-sync { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.planning-sync button { min-height: 38px; }
</style>

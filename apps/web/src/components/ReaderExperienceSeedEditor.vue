<script setup lang="ts">
import { Plus, Trash2 } from 'lucide-vue-next'
import type { ReaderExperienceSeed } from '@/api/planning'
const props = defineProps<{ modelValue: ReaderExperienceSeed[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: ReaderExperienceSeed[]] }>()
function update(index: number, patch: Partial<ReaderExperienceSeed>) {
  emit('update:modelValue', props.modelValue.map((item, i) => i === index ? { ...item, ...patch } : item))
}
function add() {
  emit('update:modelValue', [...props.modelValue, { key: `plan_${crypto.randomUUID().replace(/-/g, '')}`,
    kind: 'FORESHADOW', title: '', promise: '', setup: '', payoff: '', aftermath: '', plannedChapter: null }])
}
</script>

<template>
  <section class="seed-editor">
    <header><h3>承诺与伏笔规划</h3><button v-if="!disabled" type="button" title="新增规划" aria-label="新增承诺与伏笔规划" :disabled="modelValue.length >= 80" @click="add"><Plus :size="18" /></button></header>
    <p v-if="!modelValue.length" class="quiet-text">暂无明确规划</p>
    <fieldset v-for="(item, index) in modelValue" :key="item.key" :disabled="disabled">
      <div class="seed-grid">
        <label>类型<select :value="item.kind" @change="update(index, { kind: ($event.target as HTMLSelectElement).value as ReaderExperienceSeed['kind'] })"><option value="FORESHADOW">伏笔</option><option value="PROMISE">读者承诺</option></select></label>
        <label>计划兑现章<input type="number" min="1" :value="item.plannedChapter" @input="update(index, { plannedChapter: ($event.target as HTMLInputElement).value ? Number(($event.target as HTMLInputElement).value) : null })" /></label>
        <label class="full">标题<input :value="item.title" required maxlength="200" @input="update(index, { title: ($event.target as HTMLInputElement).value })" /></label>
        <label v-for="field in ([['promise', '读者期待'], ['setup', '铺垫计划'], ['payoff', '兑现计划'], ['aftermath', '余波计划']] as const)" :key="field[0]">
          {{ field[1] }}<textarea :value="item[field[0]]" :required="field[0] === 'promise'" maxlength="4000" rows="3" @input="update(index, { [field[0]]: ($event.target as HTMLTextAreaElement).value })" />
        </label>
      </div>
      <button v-if="!disabled" type="button" title="删除规划" aria-label="删除承诺与伏笔规划" @click="emit('update:modelValue', modelValue.filter((_, i) => i !== index))"><Trash2 :size="16" /></button>
    </fieldset>
  </section>
</template>

<style scoped>
.seed-editor { padding: 16px 0; min-width: 0; border-top: 1px solid #d8dce0; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
fieldset { border: 0; border-bottom: 1px solid #d8dce0; padding: 12px 0; margin: 0; min-width: 0; }
.seed-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
label { display: grid; gap: 6px; min-width: 0; }
input, select, textarea { width: 100%; min-width: 0; box-sizing: border-box; }
.full { grid-column: 1 / -1; }
@media (max-width: 600px) { .seed-grid { grid-template-columns: minmax(0, 1fr); } }
</style>

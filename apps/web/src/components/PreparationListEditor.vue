<script setup lang="ts">
import { Plus, Trash2 } from 'lucide-vue-next'
export interface PreparationField { key: string; label: string; type?: 'number' | 'list'; options?: string[]; max?: number }
const props = defineProps<{ modelValue: object[]; fields: PreparationField[]; title: string; disabled: boolean; maxItems: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>[]] }>()
function records() { return props.modelValue as Record<string, unknown>[] }
function display(row: object, field: PreparationField) {
  const value = (row as Record<string, unknown>)[field.key]
  return Array.isArray(value) ? value.join('\n') : String(value ?? '')
}
function update(index: number, field: PreparationField, event: Event) {
  const value = (event.target as HTMLInputElement).value
  const updated: unknown = field.type === 'number' ? Number(value) : field.type === 'list' ? value.split('\n').map(item => item.trim()).filter(Boolean) : value
  emit('update:modelValue', records().map((row, i) => i === index ? { ...row, [field.key]: updated } : { ...row }))
}
function add() {
  const row = Object.fromEntries(props.fields.map(field => [field.key, field.type === 'number' ? 1 : field.type === 'list' ? [] : field.options?.[0] ?? '']))
  if ('key' in row) row.key = `p_${crypto.randomUUID().replace(/-/g, '')}`
  emit('update:modelValue', [...records(), row])
}
</script>

<template>
  <section class="preparation-list">
    <header><h3>{{ title }}</h3><button v-if="!disabled" type="button" :disabled="modelValue.length >= maxItems" :title="`新增${title}`" @click="add"><Plus :size="16" />新增</button></header>
    <p v-if="!modelValue.length" class="empty">暂无{{ title }}</p>
    <details v-for="(row, index) in modelValue" :key="index" :open="modelValue.length === 1">
      <summary>{{ display(row, fields.find(field => ['title', 'name', 'character', 'source', 'event'].includes(field.key)) ?? fields[0]!) || `${title} ${index + 1}` }}</summary>
      <div class="fields">
        <label v-for="field in fields" :key="field.key">{{ field.label }}
          <select v-if="field.options" :value="display(row, field)" :disabled="disabled" @change="update(index, field, $event)"><option v-for="option in field.options" :key="option">{{ option }}</option></select>
          <input v-else-if="field.type === 'number'" type="number" min="1" :value="display(row, field)" :disabled="disabled" @input="update(index, field, $event)">
          <textarea v-else :value="display(row, field)" :disabled="disabled" :maxlength="field.max ?? 2000" rows="3" @input="update(index, field, $event)" />
        </label>
      </div>
      <button v-if="!disabled" type="button" :title="`删除${title}`" @click="emit('update:modelValue', records().filter((_, i) => i !== index))"><Trash2 :size="16" />删除</button>
    </details>
  </section>
</template>

<style scoped>
.preparation-list { padding: 18px 0; border-top: 1px solid #dce2e5; }
header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h3 { font-size: 16px; margin: 0; }
details { padding: 12px 0; border-bottom: 1px solid #e7ebed; }
summary { cursor: pointer; overflow-wrap: anywhere; }
.fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding: 14px 0; }
label { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #cbd3d8; border-radius: 4px; padding: 8px; font: inherit; background: #fff; }
textarea { resize: vertical; }
button { display: inline-flex; align-items: center; gap: 6px; padding: 7px 10px; background: #fff; border: 1px solid #cbd3d8; border-radius: 4px; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.empty { color: #76838a; font-size: 13px; }
@media (max-width: 700px) { .fields { grid-template-columns: minmax(0, 1fr); } }
</style>

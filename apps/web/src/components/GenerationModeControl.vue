<script setup lang="ts">
import { useId } from 'vue'
import type { GenerationMode } from '@/api/planning'

withDefaults(defineProps<{
  modelValue: GenerationMode
  disabled?: boolean
  label?: string
  reviseLabel?: string
  regenerateLabel?: string
}>(), { label: '生成方式', reviseLabel: '基于当前版本调整', regenerateLabel: '重新生成' })
const emit = defineEmits<{ 'update:modelValue': [value: GenerationMode] }>()
const name = useId()
</script>

<template>
  <fieldset class="generation-mode-control" :disabled="disabled">
    <legend>{{ label }}</legend>
    <div class="generation-mode-options">
      <label :class="{ selected: modelValue === 'REVISE' }">
        <input type="radio" :name="name" value="REVISE" :checked="modelValue === 'REVISE'" @change="emit('update:modelValue', 'REVISE')" />
        <span>{{ reviseLabel }}</span>
      </label>
      <label :class="{ selected: modelValue === 'REGENERATE' }">
        <input type="radio" :name="name" value="REGENERATE" :checked="modelValue === 'REGENERATE'" @change="emit('update:modelValue', 'REGENERATE')" />
        <span>{{ regenerateLabel }}</span>
      </label>
    </div>
  </fieldset>
</template>

<script setup lang="ts">
import { useId } from 'vue'
import type { AdaptationScope, WorldMode } from '@/api/projects'
import { adaptationOptions, worldOptions } from '@/lib/creative-boundaries'
const world = defineModel<WorldMode>('worldMode', { default: 'AUTO' })
const scope = defineModel<AdaptationScope>('adaptationScope', { default: 'AUTO' })
defineProps<{ disabled?: boolean }>()
const id = useId()
</script>

<template>
  <div class="boundary-fields">
    <fieldset :disabled="disabled">
      <legend>世界设定</legend>
      <div class="boundary-options" role="radiogroup" aria-label="世界设定">
        <label v-for="option in worldOptions" :key="option.value" :class="{ selected: world === option.value }">
          <input v-model="world" type="radio" :name="`${id}-world`" :value="option.value" />{{ option.label }}
        </label>
      </div>
    </fieldset>
    <fieldset :disabled="disabled">
      <legend>改编幅度</legend>
      <div class="boundary-options" role="radiogroup" aria-label="改编幅度">
        <label v-for="option in adaptationOptions" :key="option.value" :class="{ selected: scope === option.value }">
          <input v-model="scope" type="radio" :name="`${id}-scope`" :value="option.value" />{{ option.label }}
        </label>
      </div>
    </fieldset>
  </div>
</template>

<style scoped>
.boundary-fields { display: grid; gap: 16px; min-width: 0; grid-column: 1 / -1; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
legend { font-size: 14px; margin-bottom: 8px; }
.boundary-options { display: flex; flex-wrap: wrap; gap: 8px; }
label { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 8px 12px; border: 1px solid #cbd2d9; border-radius: 4px; cursor: pointer; overflow-wrap: anywhere; max-width: 100%; box-sizing: border-box; }
label.selected { border-color: #176b63; background: #f3faf8; }
label:focus-within { outline: 2px solid #176b63; outline-offset: 2px; }
input { width: auto; margin: 0; accent-color: #176b63; }
fieldset:disabled label { opacity: .65; cursor: default; }
</style>

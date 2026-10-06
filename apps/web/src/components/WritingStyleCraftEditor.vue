<script setup lang="ts">
import type { WritingStyleCraft } from '@/api/writingQuality'

defineProps<{ disabled: boolean }>()
const craft = defineModel<WritingStyleCraft | null>()
const fields = [
  { key: 'narratorPosition', label: '叙述立场' },
  { key: 'paragraphMoves', label: '段落组织' },
  { key: 'sentenceMoves', label: '句法推进' },
  { key: 'wordChoice', label: '用词选择' },
  { key: 'dialogueMoves', label: '对白组织' },
  { key: 'rhetoricMoves', label: '修辞机制' },
  { key: 'sceneVariants', label: '场景适配' },
  { key: 'revisionChecks', label: '修订检查' },
] as const
function update(key: typeof fields[number]['key'], event: Event) {
  if (craft.value) craft.value = { ...craft.value, [key]: (event.target as HTMLTextAreaElement).value }
}
</script>

<template>
  <details v-if="craft" class="style-craft-details" open>
    <summary>写作技法</summary>
    <div class="style-fields">
      <label v-for="field in fields" :key="field.key">
        <span>{{ field.label }}</span>
        <textarea :value="craft[field.key]" required maxlength="1000" rows="5" :disabled="disabled"
          @input="update(field.key, $event)" />
      </label>
    </div>
    <details v-if="craft.examples.length" class="style-craft-examples">
      <summary>原创技法对照 · {{ craft.examples.length }} 组</summary>
      <section v-for="(example, index) in craft.examples" :key="index" class="craft-example">
        <h4>{{ example.scene }}</h4>
        <dl>
          <dt>事实边界</dt><dd>{{ example.facts }}</dd>
          <dt>表达示例</dt><dd>{{ example.positive }}</dd>
          <dt>近似反例</dt><dd>{{ example.nearMiss }}</dd>
          <dt>差异</dt><dd>{{ example.explanation }}</dd>
        </dl>
      </section>
    </details>
    <details v-if="craft.evidence.length" class="style-craft-evidence">
      <summary>样本依据 · {{ craft.evidence.length }} 条</summary>
      <section v-for="(item, index) in craft.evidence" :key="index" class="craft-example">
        <h4>{{ fields.find(field => field.key === item.dimension)?.label ?? item.dimension }}</h4>
        <blockquote>{{ item.quote }}</blockquote><p>{{ item.explanation }}</p>
      </section>
    </details>
  </details>
</template>

<style scoped>
.style-craft-details { margin-block: 1rem; min-width: 0; }
summary { cursor: pointer; padding-block: 0.5rem; font-weight: 600; }
.style-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 0.75rem; }
label { display: grid; gap: 8px; min-width: 0; }
label > span { color: #52606b; font-size: 13px; }
textarea { width: 100%; min-width: 0; box-sizing: border-box; resize: vertical; padding: 10px 12px;
  border: 1px solid #cbd2d9; border-radius: 4px; background: #fff; color: #23333d; font: inherit; line-height: 1.5; }
textarea:focus-visible { outline: 2px solid #176b63; outline-offset: 2px; }
.craft-example { border-top: 1px solid var(--border, #dbe1e4); padding-block: 1rem; overflow-wrap: anywhere; }
h4 { font-size: 1rem; margin: 0 0 0.75rem; }
dt { font-weight: 600; margin-top: 0.75rem; }
dd { margin: 0.25rem 0 0; white-space: pre-wrap; line-height: 1.7; }
blockquote { margin: 0.5rem 0; padding-left: 1rem; border-left: 3px solid var(--border, #dbe1e4); white-space: pre-wrap; }
@media (max-width: 640px) { .style-fields { grid-template-columns: 1fr; } }
</style>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown, ArrowUp, Columns2, List, UnfoldVertical } from 'lucide-vue-next'
import { compareText, diffEntries, type DiffRow } from '@/lib/text-diff'
import { useWorkspaceChoice } from '@/composables/useWorkspaceLocation'

const props = defineProps<{ before: string; after: string; beforeLabel: string; afterLabel: string }>()
const mode = useWorkspaceChoice('diffView', ['split', 'unified'] as const,
  () => typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 700px)').matches ? 'unified' : 'split')
const onlyChanges = ref(true)
const expanded = ref(new Set<number>())
const current = ref(0)
const surface = ref<HTMLElement | null>(null)
const diff = computed(() => compareText(props.before, props.after))
const entries = computed(() => diffEntries(diff.value.rows, onlyChanges.value, expanded.value))
watch(() => [props.before, props.after], () => { expanded.value = new Set(); current.value = 0 })
function firstInHunk(row: DiffRow) { return row.hunk !== null && diff.value.rows[row.index - 1]?.hunk !== row.hunk }
function unfold(start: number) { expanded.value = new Set([...expanded.value, start]) }
async function jump(step: number) {
  current.value = (current.value + step + diff.value.hunks) % diff.value.hunks
  await nextTick()
  surface.value?.querySelector<HTMLElement>(`[data-hunk-start="${current.value}"]`)?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
}
</script>

<template>
  <section class="text-diff" aria-label="文本差异">
    <div class="diff-tools">
      <div class="segmented" role="group" aria-label="差异显示方式">
        <button type="button" :aria-pressed="mode === 'split'" title="左右对比" @click="mode = 'split'"><Columns2 :size="16" /><span>左右</span></button>
        <button type="button" :aria-pressed="mode === 'unified'" title="合并对比" @click="mode = 'unified'"><List :size="16" /><span>合并</span></button>
      </div>
      <label class="check"><input v-model="onlyChanges" type="checkbox" />仅看差异</label>
      <span class="diff-count" role="status">{{ diff.hunks }} 处变化 <b class="plus">+{{ diff.addedLines }} 行</b><b class="minus">-{{ diff.removedLines }} 行</b></span>
      <div class="jump-tools">
        <span v-if="diff.hunks">{{ current + 1 }} / {{ diff.hunks }}</span>
        <button type="button" title="上一处差异" aria-label="上一处差异" :disabled="!diff.hunks" @click="jump(-1)"><ArrowUp :size="16" /></button>
        <button type="button" title="下一处差异" aria-label="下一处差异" :disabled="!diff.hunks" @click="jump(1)"><ArrowDown :size="16" /></button>
      </div>
    </div>
    <p v-if="!diff.hunks" class="same-message" role="status">两版内容一致</p>
    <p v-if="diff.coarse" class="same-message" role="status">部分大范围改写采用整段高亮</p>
    <div ref="surface" class="diff-surface" :class="mode" tabindex="0">
      <div class="diff-heading"><strong>{{ beforeLabel }}</strong><strong>{{ afterLabel }}</strong></div>
      <template v-for="entry in entries" :key="entry.kind === 'row' ? `row-${entry.row.index}` : `gap-${entry.start}`">
        <button v-if="entry.kind === 'gap'" class="fold" type="button" @click="unfold(entry.start)"><UnfoldVertical :size="15" />展开 {{ entry.count }} 行未变内容</button>
        <div v-else class="diff-row" :class="[entry.row.kind, { focused: entry.row.hunk !== null && entry.row.hunk === current }]"
          :data-hunk-start="firstInHunk(entry.row) ? entry.row.hunk : undefined">
          <template v-if="mode === 'split'">
            <div v-for="side in (['left', 'right'] as const)" :key="side" class="line-cell" :class="[side, { absent: !entry.row[side] }]">
              <span class="line-number" aria-hidden="true">{{ entry.row[side]?.number }}</span>
              <div v-if="entry.row[side]" class="line-text"><span v-for="(part, index) in entry.row[side].parts" :key="index" :class="{ changed: part.changed }">{{ part.text }}</span><small v-if="entry.row.left && entry.row.right && entry.row.left.ending !== entry.row.right.ending" class="eol">{{ entry.row[side].ending }}</small></div>
            </div>
          </template>
          <template v-else>
            <div v-if="entry.row.left" class="line-cell left" :class="{ unchanged: entry.row.kind === 'same' }">
              <span class="line-number" aria-hidden="true">{{ entry.row.left.number }}</span><span class="line-number" aria-hidden="true">{{ entry.row.kind === 'same' ? entry.row.right?.number : '' }}</span><span class="sign" aria-hidden="true">{{ entry.row.kind === 'same' ? ' ' : '-' }}</span>
              <div class="line-text"><span v-for="(part, index) in entry.row.left.parts" :key="index" :class="{ changed: part.changed }">{{ part.text }}</span><small v-if="entry.row.left.ending !== entry.row.right?.ending" class="eol">{{ entry.row.left.ending }}</small></div>
            </div>
            <div v-if="entry.row.right && entry.row.kind !== 'same'" class="line-cell right">
              <span class="line-number" aria-hidden="true"></span><span class="line-number" aria-hidden="true">{{ entry.row.right.number }}</span><span class="sign" aria-hidden="true">+</span>
              <div class="line-text"><span v-for="(part, index) in entry.row.right.parts" :key="index" :class="{ changed: part.changed }">{{ part.text }}</span><small v-if="entry.row.right.ending !== entry.row.left?.ending" class="eol">{{ entry.row.right.ending }}</small></div>
            </div>
          </template>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.text-diff { min-width: 0; }
.diff-tools, .segmented, .jump-tools, .check { display: flex; align-items: center; gap: 8px; }
.diff-tools { flex-wrap: wrap; gap: 14px; padding: 12px 0; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 32px; padding: 5px 8px; background: #fff; border: 1px solid #d4dce1; border-radius: 4px; color: #31424b; font: inherit; cursor: pointer; }
button:disabled { opacity: .45; cursor: default; } button:focus-visible { outline: 2px solid #176b63; outline-offset: 2px; }
.segmented { gap: 0; }.segmented button { border-radius: 0; }.segmented button:first-child { border-radius: 4px 0 0 4px; }.segmented button:last-child { border-radius: 0 4px 4px 0; border-left: 0; }
button[aria-pressed=true] { background: #e9f3f0; color: #155e52; }
.check { white-space: nowrap; font-size: 13px; }.check input { accent-color: #176b63; }
.diff-count { display: flex; flex-wrap: wrap; gap: 8px; font-size: 13px; }.plus { color: #167246; }.minus { color: #b33948; }
.jump-tools { margin-left: auto; font-size: 12px; }.jump-tools button { width: 32px; padding: 0; }
.same-message { margin: 0; padding: 10px 0; color: #667580; font-size: 13px; }
.diff-surface { max-height: 70vh; overflow: auto; border: 1px solid #d8e0e4; border-radius: 4px; background: #fff; overscroll-behavior: contain; }
.diff-heading, .split .diff-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.diff-heading { position: sticky; top: 0; z-index: 1; background: #f2f5f6; border-bottom: 1px solid #d8e0e4; }
.diff-heading strong { padding: 10px 12px; font-size: 13px; overflow-wrap: anywhere; }.diff-heading strong + strong { border-left: 1px solid #d8e0e4; }
.line-cell { display: flex; align-items: flex-start; min-width: 0; padding: 5px 0; }.split .right { border-left: 1px solid #d8e0e4; }
.line-number { flex: 0 0 38px; width: 38px; padding-right: 8px; text-align: right; color: #7e8a92; font: 12px/25px ui-monospace, monospace; user-select: none; }
.line-text { min-width: 0; flex: 1; padding: 0 12px 0 4px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 15px; line-height: 25px; min-height: 25px; }
.modified .left, .removed .left { background: #fff0f1; }.modified .right, .added .right { background: #ebf8ee; }
.left .changed { background: #f9c7cf; color: #792734; }.right .changed { background: #bce8c9; color: #164d2c; }
.absent { background: repeating-linear-gradient(135deg, #f4f6f7 0 4px, #fff 4px 8px); }
.focused { box-shadow: inset 3px 0 #297ab0; }.sign { flex: 0 0 18px; font: 14px/25px ui-monospace, monospace; }
.eol { margin-left: 8px; padding: 1px 4px; border: 1px solid #a9b7c0; color: #60717e; font: 11px/18px ui-monospace, monospace; }
.fold { width: 100%; border: 0; border-top: 1px solid #e2e8eb; border-bottom: 1px solid #e2e8eb; border-radius: 0; background: #f2f5f6; color: #576d7a; font-size: 12px; }
@media (max-width: 700px) { .diff-tools { gap: 10px; }.line-number { flex-basis: 27px; width: 27px; padding-right: 4px; }.line-text { padding-right: 8px; font-size: 14px; }.diff-surface { max-height: 65vh; } }
</style>

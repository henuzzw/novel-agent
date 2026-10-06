<script setup lang="ts">
import type { ChapterContractContent } from '@/api/writing'
defineProps<{ content: ChapterContractContent }>()
</script>

<template>
  <section class="execution-panel" aria-label="场景与读者体验">
    <h3>场景与读者体验</h3>
    <dl class="execution-meta">
      <div><dt>本章目标</dt><dd>{{ content.objective || '未填写' }}</dd></div>
      <div><dt>退出状态</dt><dd>{{ content.expectedExitState || '未填写' }}</dd></div>
    </dl>
    <ol class="execution-beats">
      <li v-for="(beat, index) in content.requiredBeats" :key="index">
        <span class="beat-label">节拍 {{ index + 1 }}</span><p>{{ beat }}</p>
      </li>
    </ol>
    <p v-if="!content.requiredBeats.length" class="empty">尚未填写场景节拍</p>
    <div class="execution-columns">
      <section><h4>本章揭示</h4><ul><li v-for="(item, index) in content.requiredReveals" :key="index">{{ item }}</li></ul><p v-if="!content.requiredReveals.length" class="empty">未填写</p></section>
      <section><h4>伏笔动作</h4><ul><li v-for="(item, index) in content.foreshadowActions" :key="index">{{ item }}</li></ul><p v-if="!content.foreshadowActions.length" class="empty">未填写</p></section>
      <section><h4>禁止越界</h4><ul><li v-for="(item, index) in content.forbiddenFacts" :key="index">{{ item }}</li></ul><p v-if="!content.forbiddenFacts.length" class="empty">未填写</p></section>
    </div>
    <dl class="execution-meta"><div><dt>结尾延续</dt><dd>{{ content.hook || '未填写' }}</dd></div></dl>
  </section>
</template>

<style scoped>
.execution-panel { padding-block: 18px; border-top: 1px solid var(--color-border, #dfe3e0); min-width: 0; }
h3 { font-size: 16px; margin: 0 0 14px; } h4 { font-size: 14px; margin: 0 0 8px; }
.execution-meta { margin: 0; display: grid; gap: 12px; }
.execution-meta div { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 12px; }
dt, .beat-label { color: var(--color-text-secondary, #68716b); font-size: 13px; }
dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.execution-beats { list-style: none; padding: 0; margin: 14px 0; }
.execution-beats li { padding-block: 12px; border-bottom: 1px solid var(--color-border, #dfe3e0); }
.execution-beats p { margin: 6px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.execution-columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; margin-block: 18px; }
.execution-columns section { min-width: 0; }
ul { padding-left: 20px; margin: 0; } li { overflow-wrap: anywhere; white-space: pre-wrap; }
.empty { color: var(--color-text-secondary, #68716b); font-size: 13px; }
@media (max-width: 700px) { .execution-columns { grid-template-columns: minmax(0, 1fr); } .execution-meta div { grid-template-columns: minmax(0, 1fr); gap: 4px; } }
</style>

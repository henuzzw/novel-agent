<script setup lang="ts">
import { Plus, Trash2 } from 'lucide-vue-next'
import type { CharacterBlueprint } from '@/api/planning'

const characters = defineModel<CharacterBlueprint[]>({ required: true })
defineProps<{ disabled?: boolean }>()
type TextField = Exclude<keyof CharacterBlueprint, 'role' | 'initialRelationships' | 'initialPossessions' | 'knowledgeBoundaries'>
type ListField = 'initialRelationships' | 'initialPossessions' | 'knowledgeBoundaries'
const groups: { title: string, fields: { key: TextField, label: string }[] }[] = [
  { title: '身份与背景', fields: [
    { key: 'gender', label: '性别（未确定可留空）' }, { key: 'ageDescription', label: '起点年龄或年龄范围' },
    { key: 'identity', label: '身份与生活目标' }, { key: 'appearance', label: '外貌' },
    { key: 'background', label: '背景与行为成因' }, { key: 'abilitiesAndLimits', label: '能力与限制' },
  ] },
  { title: '性格与选择', fields: [
    { key: 'externalPersonality', label: '外在性格' }, { key: 'internalPersonality', label: '内在性格' },
    { key: 'coreDesire', label: '核心欲望' }, { key: 'fear', label: '恐惧' },
    { key: 'flaw', label: '缺陷' }, { key: 'values', label: '价值观' },
    { key: 'speechStyle', label: '说话风格' }, { key: 'behaviorHabits', label: '行为习惯' },
    { key: 'behaviorBoundaries', label: '行为底线' },
  ] },
  { title: '故事起点', fields: [{ key: 'openingState', label: '开篇身体与心理状态' }] },
  { title: '作者侧设计', fields: [
    { key: 'secret', label: '秘密（不代表人物已知）' },
    { key: 'characterArc', label: '未来弧光与触发条件（尚未发生）' },
  ] },
]
const lists: { key: ListField, label: string }[] = [
  { key: 'initialRelationships', label: '初始关系与形成依据' },
  { key: 'initialPossessions', label: '开篇物品、持有人与来源' },
  { key: 'knowledgeBoundaries', label: '已知、未知与误认' },
]
const roleLabels = { PROTAGONIST: '主角', SUPPORTING: '重要配角', MINOR: '次要角色' }

function edit(index: number, patch: Partial<CharacterBlueprint>) {
  characters.value = characters.value.map((value, i) => i === index ? { ...value, ...patch } : value)
}

function editList(index: number, field: ListField, value: string) {
  edit(index, { [field]: value.split('\n').map(item => item.trim()).filter(Boolean) })
}

function add() {
  if (characters.value.length >= 12) return
  characters.value = [...characters.value, {
    name: '', role: characters.value.length ? 'SUPPORTING' : 'PROTAGONIST', identity: '', appearance: '',
    background: '', externalPersonality: '', internalPersonality: '', coreDesire: '', fear: '', flaw: '',
    values: '', speechStyle: '', behaviorHabits: '', abilitiesAndLimits: '', behaviorBoundaries: '',
    secret: '', characterArc: '', openingState: '', initialRelationships: [], initialPossessions: [], knowledgeBoundaries: [],
  }]
}
</script>

<template>
  <section class="character-blueprints">
    <div class="blueprint-heading">
      <h3>人物底稿</h3>
      <button type="button" class="button secondary" :disabled="disabled || characters.length >= 12" @click="add">
        <Plus :size="16" />添加人物
      </button>
    </div>
    <div v-if="!characters.length" class="blueprint-empty">尚无人物底稿</div>
    <details v-for="(character, index) in characters" :key="index" :open="index === 0" class="blueprint-character">
      <summary>{{ character.name || '未命名人物' }} · {{ roleLabels[character.role] }}</summary>
      <div class="blueprint-identity">
        <label class="bible-field"><span>姓名或明确称谓</span><input :value="character.name" maxlength="100" :disabled="disabled" @input="edit(index, { name: ($event.target as HTMLInputElement).value })" /></label>
        <label class="bible-field"><span>角色类型</span><select :value="character.role" :disabled="disabled" @change="edit(index, { role: ($event.target as HTMLSelectElement).value as CharacterBlueprint['role'] })"><option value="PROTAGONIST">主角</option><option value="SUPPORTING">重要配角</option><option value="MINOR">次要角色</option></select></label>
        <button type="button" class="button secondary blueprint-remove" :aria-label="`删除人物底稿：${character.name || '未命名人物'}`" :title="`删除人物底稿：${character.name || '未命名人物'}`" :disabled="disabled" @click="characters = characters.filter((_, i) => i !== index)"><Trash2 :size="16" /></button>
      </div>
      <div v-for="group in groups" :key="group.title" class="blueprint-group">
        <h4>{{ group.title }}</h4>
        <div class="blueprint-fields">
          <label v-for="field in group.fields" :key="field.key" class="bible-field">
            <span>{{ field.label }}</span>
            <textarea :value="character[field.key]" rows="3" :maxlength="field.key === 'gender' ? 40 : field.key === 'ageDescription' ? 100 : 3000" :disabled="disabled" @input="edit(index, { [field.key]: ($event.target as HTMLTextAreaElement).value })" />
          </label>
          <template v-if="group.title === '故事起点'">
            <label v-for="field in lists" :key="field.key" class="bible-field">
              <span>{{ field.label }}</span>
              <textarea :value="character[field.key].join('\n')" rows="3" :disabled="disabled" @input="editList(index, field.key, ($event.target as HTMLTextAreaElement).value)" />
            </label>
          </template>
        </div>
      </div>
    </details>
  </section>
</template>

<style scoped>
.character-blueprints { margin-top: 24px; min-width: 0; }
.blueprint-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; scroll-margin-top: 88px; }
.blueprint-heading h3 { margin: 0; font-size: 18px; }
.blueprint-empty { padding: 16px 0; color: #66717d; }
.blueprint-character { border-bottom: 1px solid #d7dde3; padding: 14px 0; }
.blueprint-character summary { cursor: pointer; font-weight: 600; overflow-wrap: anywhere; }
.blueprint-identity { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; align-items: end; gap: 12px; margin-top: 16px; }
.blueprint-group { margin: 20px 0; }
.blueprint-group h4 { margin: 0 0 12px; font-size: 14px; }
.blueprint-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.blueprint-fields label, .blueprint-identity label { min-width: 0; }
.blueprint-fields textarea, .blueprint-identity input, .blueprint-identity select { width: 100%; box-sizing: border-box; min-width: 0; }
.blueprint-identity select { min-height: 38px; padding: 7px 9px; border: 1px solid #bfc8d1; border-radius: 6px; color: #1c232b; background: #fff; font: inherit; }
.blueprint-remove { min-height: 40px; }
@media (max-width: 640px) {
  .blueprint-fields { grid-template-columns: minmax(0, 1fr); }
  .blueprint-identity { grid-template-columns: minmax(0, 1fr) auto; }
  .blueprint-identity label:first-child { grid-column: 1 / -1; }
}
</style>

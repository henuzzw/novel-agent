import type { AdaptationScope, WorldMode } from '@/api/projects'

export const worldOptions: { value: WorldMode; label: string }[] = [
  { value: 'AUTO', label: '自动判断' },
  { value: 'REALISTIC', label: '现实写实' },
  { value: 'REALISTIC_FICTION', label: '现实背景 · 允许虚构活动' },
  { value: 'ALTERNATE', label: '架空世界' },
]
export const adaptationOptions: { value: AdaptationScope; label: string }[] = [
  { value: 'AUTO', label: '自动判断' },
  { value: 'PRESERVE_CORE', label: '保留核心主线' },
  { value: 'RESTRUCTURE', label: '允许重构主线' },
]
export function worldLabel(value: WorldMode) { return worldOptions.find(option => option.value === value)?.label ?? value }
export function adaptationLabel(value: AdaptationScope) { return adaptationOptions.find(option => option.value === value)?.label ?? value }

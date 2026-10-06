<script setup lang="ts">
import { Cpu } from 'lucide-vue-next'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
const { label } = useGlobalModelSettings()
</script>
<template><span class="global-model-badge" title="全局模型设置"><Cpu :size="15" />{{ label }}</span></template>
<style scoped>
.global-model-badge { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #52616c; overflow-wrap: anywhere; min-width: 0; }
svg { flex-shrink: 0; }
</style>

import { inject, onScopeDispose, type Ref } from 'vue'
import { matchedRouteKey, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'

export function useUnsavedChanges(dirty: Readonly<Ref<boolean>>, locationKeys: string[]) {
  const confirmDiscard = () => !dirty.value || window.confirm('有未保存的修改。确定离开并放弃这些修改吗？')
  if (inject(matchedRouteKey, null)) {
    onBeforeRouteLeave(confirmDiscard)
    onBeforeRouteUpdate((to, from) => {
      if (to.path !== from.path || locationKeys.some(key => to.query[key] !== from.query[key])) return confirmDiscard()
      return true
    })
  }
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  window.addEventListener('beforeunload', beforeUnload)
  onScopeDispose(() => window.removeEventListener('beforeunload', beforeUnload))
}

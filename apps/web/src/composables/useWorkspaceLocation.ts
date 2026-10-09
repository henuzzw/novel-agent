import { computed, inject, ref } from 'vue'
import { routeLocationKey, routerKey, type LocationQueryRaw, type Router } from 'vue-router'

export const workspaceSections = ['imports', 'directions', 'bible', 'outline', 'materials', 'experience', 'runs', 'settings', 'writing'] as const
export const planningViews = ['directions', 'bible', 'outline', 'style'] as const
export const writingViews = ['manuscript', 'quality', 'opening', 'review', 'memory'] as const
export const materialViews = ['profiles', 'entities', 'timeline', 'foreshadows', 'relations', 'style'] as const

type PendingNavigation = { path: string; query: LocationQueryRaw; hash: string }
const pendingNavigations = new WeakMap<Router, PendingNavigation>()

// Merge synchronous changes (e.g. opening a chapter from tasks) into one history entry.
function updateQuery(router: Router, key: string, value: string) {
  const route = router.currentRoute.value
  const pending = pendingNavigations.get(router)
  if (pending?.path === route.path) {
    pending.query[key] = value
    return
  }
  const navigation = { path: route.path, query: { ...route.query, [key]: value }, hash: route.hash }
  pendingNavigations.set(router, navigation)
  queueMicrotask(() => {
    if (pendingNavigations.get(router) !== navigation) return
    pendingNavigations.delete(router)
    if (router.currentRoute.value.path === navigation.path) void router.push(navigation)
  })
}

export function readWorkspaceChoice<T extends string>(value: unknown, choices: readonly T[], fallback: T): T {
  return typeof value === 'string' && choices.includes(value as T) ? value as T : fallback
}

export function readWorkspaceChapter(value: unknown): number {
  if (typeof value !== 'string' || !/^[1-9]\d{0,4}$/.test(value)) return 1
  return Number(value)
}

export function useWorkspaceChoice<T extends string>(key: string, choices: readonly T[], fallback: T | (() => T)) {
  const router = inject(routerKey, null)
  const route = inject(routeLocationKey, null)
  const local = ref<T | null>(null)
  const defaultValue = () => typeof fallback === 'function' ? fallback() : fallback
  return computed<T>({
    get: () => route ? readWorkspaceChoice(route.query[key], choices, defaultValue()) : local.value ?? defaultValue(),
    set: value => {
      if (!choices.includes(value)) return
      if (router) updateQuery(router, key, value)
      else local.value = value
    },
  })
}

export function useWorkspaceChapter() {
  const router = inject(routerKey, null)
  const route = inject(routeLocationKey, null)
  const local = ref(1)
  return computed({
    get: () => route ? readWorkspaceChapter(route.query.chapter) : local.value,
    set: (value: number) => {
      if (!Number.isInteger(value) || value < 1 || value > 99999) return
      if (router) updateQuery(router, 'chapter', String(value))
      else local.value = value
    },
  })
}

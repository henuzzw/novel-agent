import { apiRequest } from './http'
import type { CharacterBlueprint } from './planning'

export interface PlannedRelationship {
  id: string; sourceBibleId: string; characterId: string | null; description: string
}
export interface PlanningCharacterSnapshot {
  sourceBibleId: string; characterId: string; blueprint: CharacterBlueprint
}
export interface PlanOrigin {
  planId: string; sourceKind: 'BIBLE' | 'OUTLINE' | 'CANON' | 'PREPARATION'; sourceId: string; current: boolean
}
const base = (project: string) => `/api/v1/projects/${encodeURIComponent(project)}/planning-materials`
export const syncPlanningMaterials = (project: string) => apiRequest<void>(`${base(project)}/actions/sync`, { method: 'POST' })
export const listPlanningCharacters = (project: string) => apiRequest<PlanningCharacterSnapshot[]>(`${base(project)}/characters`)
export const listPlanningRelationships = (project: string, character?: string) =>
  apiRequest<PlannedRelationship[]>(`${base(project)}/relationships${character ? `?${new URLSearchParams({ characterId: character })}` : ''}`)
export const listPlanOrigins = (project: string) => apiRequest<PlanOrigin[]>(`${base(project)}/plan-origins`)

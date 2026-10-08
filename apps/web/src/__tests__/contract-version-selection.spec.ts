import { describe, expect, it } from 'vitest'
import { readWorkspaceChoice, writingViews } from '@/composables/useWorkspaceLocation'

describe('retired contract navigation', () => {
  it.each(['contract', 'contractReview'])('opens the manuscript editor for old %s links', oldView => {
    expect(readWorkspaceChoice(oldView, writingViews, 'manuscript')).toBe('manuscript')
  })
  it('keeps manuscript, review and quality bookmarks', () => {
    for (const view of ['manuscript', 'review', 'quality'] as const) {
      expect(readWorkspaceChoice(view, writingViews, 'manuscript')).toBe(view)
    }
    expect(writingViews).not.toContain('contract')
  })
})

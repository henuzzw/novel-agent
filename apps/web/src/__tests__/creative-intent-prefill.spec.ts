import { describe, expect, it } from 'vitest'

import type { OutlineVersion, StoryBibleVersion } from '@/api/planning'
import { createCreativeIntentPrefill } from '@/lib/creative-intent-prefill'

describe('createCreativeIntentPrefill', () => {
  it('derives an editable creative intent from imported planning artifacts', () => {
    const bible = {
      sourceImportId: 'import-1',
      content: {
        logline: '高中男生在一次选座后陷入两段错位的暗恋。',
        theme: '少年在误会中成长。',
        worldSetting: '普通高中校园。',
        protagonist: '迟钝但善良的高一男生。',
        centralConflict: '他喜欢的女生和喜欢他的女生分别坐在两侧。',
        narrativeStyle: '真实细腻、轻松自嘲，又带一点酸涩。',
        hardConstraints: ['第二排的五人座位顺序不可改变。'],
      },
    } as StoryBibleVersion
    const outline = {
      wordBudget: { targetWords: 50_000 },
    } as OutlineVersion

    const result = createCreativeIntentPrefill(bible, outline)

    expect(result.genresText).toContain('青春校园')
    expect(result.genresText).toContain('情感')
    expect(result.tonesText).toContain('酸涩')
    expect(result.targetWords).toBe(50_000)
    expect(result.mustHave).toEqual(['第二排的五人座位顺序不可改变。'])
  })
})

import type { OutlineVersion, StoryBibleVersion } from '@/api/planning'

export interface CreativeIntentPrefill {
  premise: string
  genresText: string
  targetAudience: string
  protagonistBrief: string
  centralConflict: string
  tonesText: string
  targetWords: number
  mustHave: string[]
}

function matchingLabels(text: string, candidates: Array<[RegExp, string]>, fallback: string[]) {
  const labels = candidates.filter(([pattern]) => pattern.test(text)).map(([, label]) => label)
  return labels.length ? [...new Set(labels)] : fallback
}

export function createCreativeIntentPrefill(
  bible: StoryBibleVersion,
  outline: OutlineVersion | null,
): CreativeIntentPrefill {
  const content = bible.content
  const searchableText = [
    content.logline,
    content.theme,
    content.worldSetting,
    content.protagonist,
    content.centralConflict,
    content.narrativeStyle,
  ].join('\n')
  const genres = matchingLabels(searchableText, [
    [/校园|高中|学生|同学/, '青春校园'],
    [/暗恋|爱情|恋爱|喜欢|情感/, '情感'],
    [/成长|成熟|改变/, '成长'],
    [/轻喜剧|幽默|诙谐|自嘲/, '轻喜剧'],
    [/悬疑|谜团|侦探|真相/, '悬疑'],
    [/科幻|未来|星际|人工智能/, '科幻'],
    [/奇幻|魔法|异世界|修仙/, '奇幻'],
    [/历史|古代|朝堂|江湖/, '历史'],
  ], ['现实题材'])
  const tones = matchingLabels(searchableText, [
    [/真实|现实/, '真实'],
    [/细腻|细节/, '细腻'],
    [/轻松|幽默|轻喜剧|自嘲/, '轻松'],
    [/酸涩|遗憾|错过/, '酸涩'],
    [/温暖|治愈/, '温暖'],
    [/克制|含蓄/, '克制'],
    [/紧张|悬疑/, '紧张'],
  ], ['真实', '细腻'])

  return {
    premise: content.logline,
    genresText: genres.join(', '),
    targetAudience: `喜欢${genres.join('、')}和细腻人物关系的读者`,
    protagonistBrief: content.protagonist,
    centralConflict: content.centralConflict,
    tonesText: tones.join(', '),
    targetWords: outline?.wordBudget.targetWords ?? 120000,
    mustHave: [...content.hardConstraints],
  }
}

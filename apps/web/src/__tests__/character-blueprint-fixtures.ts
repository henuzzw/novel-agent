import type { CharacterBlueprint, StoryBibleVersion } from '@/api/planning'

export function character(): CharacterBlueprint {
  return {
    name: '江澈', role: 'PROTAGONIST', identity: '学生，想保住学习时间', appearance: '细框眼镜',
    background: '家里一直要求他懂事', externalPersonality: '客气', internalPersonality: '怕拒绝别人',
    coreDesire: '希望被明确选择', fear: '失去朋友', flaw: '回避表态', values: '不轻易失信',
    speechStyle: '短句，常先答应再解释', behaviorHabits: '紧张时扶眼镜', abilitiesAndLimits: '数学好，但不擅长表达感情',
    behaviorBoundaries: '不公开他人的纸条', secret: '尚未承认偏爱', characterArc: '计划学会选择，尚未发生',
    openingState: '选座前尚未知道对方心意', initialRelationships: ['与同学相识一年，互相讲题'],
    initialPossessions: ['眼镜由家长购买，目前本人持有'], knowledgeBoundaries: ['不知道同学写了纸条'],
  }
}

export function bible(): StoryBibleVersion {
  return {
    id: 'bible-1', projectId: 'project-1', generationNumber: 1, schemaVersion: 'story-bible/2', status: 'DRAFT',
    generatorType: 'LOCAL_CODEX', authorInstruction: null, sourceDirectionSetId: null, sourceCandidateId: null,
    sourceImportId: null, baseBibleVersionId: null, changeSummary: [], version: 3, createdAt: '', updatedAt: '',
    content: { logline: '学生面对选择', theme: '承担', worldSetting: '校园', worldRules: [], protagonist: '江澈：学生',
      protagonistArc: '学会选择', supportingCharacters: [], relationshipDynamics: [], centralConflict: '回避选择',
      stakes: '失去信任', narrativeStyle: '克制', endingDirection: '主动承担', hardConstraints: [], openQuestions: [],
      characterBlueprints: [character()] },
  }
}

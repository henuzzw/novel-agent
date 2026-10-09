import { createModelSettingsFixture, selectGlobalProvider } from './model-settings-fixture'
import { test, expect } from '@playwright/test'
import type { StylePreviewInput, WritingStyleProfile } from '../src/api/writingQuality'
import { readFileSync } from 'node:fs'

test('tries first-chapter styles from planning and adopts the selected sample explicitly', async ({ page }) => {
  const first: WritingStyleProfile = { name: '现实细腻', narrativeVoice: '贴近感知', sentenceRhythm: '长短交错',
    descriptionFocus: '生活细节', dialogueStyle: '自然口语', emotionalExpression: '动作反应', pacing: '重要互动展开', avoidPatterns: ['重复解释'],
    basePresetId: 'realistic', basePresetVersion: 1,
    craft: {
  "narratorPosition": "贴着既定视角的注意力走：人物先看见什么、刻意不看什么，决定叙述顺序。只写可观察的动作和有依据的心理；不从表情直接判定另一个人暗恋、背叛或算计。叙述者不替人物解释所有尴尬，也不预告多年后的答案。",
  "paragraphMoves": "一个段落围绕一次注意力或互动变化组织：眼前动作带出有用细节，别人回应使动作改变，收束到新的处境。关键互动不概述成大家聊了一会儿；过渡则不逐步登记起身、开门、走路。",
  "sentenceMoves": "动作句交代对象与位置，观察句适度延伸，犹豫处可以保留完整复句。观察与转折可用完整长句，不把呼吸、抬眼、捏衣角拆成一串孤立短句；同一情绪有新变化时才追加第二个反应。",
  "wordChoice": "用人物认识的器物、动作和空间词。细节必须改变读者对人物习惯、当下压力或关系的理解；不为了显得细腻平均分配声音、气味、光线。抽象词出现后要有具体行为支持。",
  "dialogueMoves": "对话由各自目的驱动。保留省略、没有正面回答和短暂沉默；接话后的动作显示理解或误解。两个熟人不互相讲解已经知道的背景，人物也不轮流完整发表感情分析。",
  "rhetoricMoves": "情绪先由注意力偏向、回应与选择累积，必要时保留简短直接心理。比喻只解释当前感受，不能可互换地套在任何人物身上；不以主题总结替场景收尾。",
  "sceneVariants": "日常：挑一个有关系信息的习惯展开。冲突：追踪话语如何改变行动，不只写双方生气。告别：放慢一个尚未完成的动作，不替角色宣布释怀。高潮：突出选择及后果，减少旁观景物。",
  "revisionChecks": "检查细节是否与当前人物或行动有关，能否看出每次回应后的变化，心理判断是否越过视角。优先修正重复解释与无依据解读；不把有效的心理描写一律删除。",
  "examples": [
    {
      "scene": "选座与让位",
      "facts": "教室第二排还剩两个相邻座位。林宁先坐下，把书包放在空椅上。周禾问有没有人。林宁说没有，拿起书包放到脚边，周禾坐下。两人的感情未知。",
      "positive": "林宁坐进第二排，把书包搁在旁边的椅子上。周禾停在桌边，问这里有没有人。她伸手去拿书包，背带挂住了椅背，便换了一只手。‘没有。’书包放到脚边后，她把脚往里收了收。周禾拉开椅子坐下，两张桌子之间的空隙也就有了人。",
      "nearMiss": "林宁选好了第二排的座位，放下书包。周禾过来询问，她回答没人，把书包拿走，周禾就坐下了。整个过程很自然。",
      "explanation": "正例让拿包和收脚随他人的靠近发生，展开空间与互动；近似反例事实清楚，却以过程概述和评价取代体验。动作细节不是恋爱证据。"
    },
    {
      "scene": "修理铺结账",
      "facts": "修理工已修好顾客的自行车。顾客打开钱包说今天钱不够，询问明天付款。修理工同意并递回车钥匙，顾客推车离开。其他背景未知。",
      "positive": "他把修好的车推到门边。顾客打开钱包，拇指在里面停了一下，说今天钱不够，明天送来行不行。他看着对方还没合上的钱包，把钥匙递了过去。‘明天吧。’顾客接过钥匙，合上钱包，推着车出了门。",
      "nearMiss": "修理工修好了自行车，顾客没带够钱，希望明天付。修理工善良地同意了，递给顾客钥匙。顾客非常感动，推车离开。",
      "explanation": "正例以正在发生的动作衔接付款困难和回应；近似反例给两人贴上善良与感动的标签，替代了可见行为。"
    }
  ],
  "evidence": []
} }
  const second = { ...first, name: '悬疑克制', narrativeVoice: '冷静观察' }
  const migration = readFileSync(new URL('../../server/src/main/resources/db/migration/V048__campus_relationship_style.sql', import.meta.url), 'utf8')
  const campus: WritingStyleProfile = JSON.parse(migration.split('$style_seed$')[1]!)
  const chapter = { number: 1, title: '旧信', pov: '林雨', objective: '找到旧信', coreEvent: '发现信封', reveal: '笔迹', endingHook: '敲门', suggestedMinWords: 1000, suggestedMaxWords: 2000, status: 'PLANNED' }
  const outline = { id: 'outline-1', version: 3, generationNumber: 2, status: 'DRAFT', generatorType: 'LOCAL_TEMPLATE', changeSummary: [], sourceBibleVersionId: 'bible-1', baseOutlineVersionId: null,
    wordBudget: { targetWords: 10000, acceptableMinWords: 1000, acceptableMaxWords: 20000, recommendedChapterCount: 5 },
    content: { title: '旧信', premise: '寻找真相', structureSummary: '调查', pacingStrategy: '递进', suggestedMinWords: 1000, suggestedMaxWords: 20000,
      arcs: [{ ordinal: 1, title: '第一卷', objective: '调查', mainConflict: '误会', turningPoint: '旧信', outcome: '找到线索', suggestedMinWords: 1000, suggestedMaxWords: 10000, chapters: [chapter] }] } }
  const generated: WritingStyleProfile[] = []
  const generationInputs: StylePreviewInput[] = []
  let appliedVersion: number | null = null
  let applied: WritingStyleProfile | null = null
  const modelSettings = createModelSettingsFixture()
  await page.route('**/api/v1/**', async (route) => {
    if (await modelSettings(route)) return
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/writing-style/presets')) return route.fulfill({ json: [first, second, campus] })
    if (path.endsWith('/writing-style')) {
      if (request.method() === 'PUT') { appliedVersion = request.postDataJSON().expectedVersion; applied = request.postDataJSON().profile }
      return route.fulfill({ json: { profile: applied, version: applied ? 5 : 4 } })
    }
    if (path.endsWith('/writing-style/actions/preview')) {
      const input = request.postDataJSON()
      generationInputs.push(input)
      generated.push(input.profile)
      return route.fulfill({ json: { sourceOutlineVersionId: 'outline-1', sourceOutlineRowVersion: 3,
        outlineGenerationNumber: 2, sourceBibleVersionId: 'bible-1', profile: input.profile, provider: input.provider,
        targetWords: 800, previewMode: 'MODEL', content: { title: '旧信',
          body: input.profile.name === first.name ? '她推开窗，晨风翻过桌上的信封。门外有人轻轻咳了一声。' : '信封停在门缝里。没有邮戳。她看向走廊，脚步声已经消失。' } } })
    }
    if (path.endsWith('/writing-style/actions/check-preview')) return route.fulfill({ json: {
      id: 'review-1', provider: 'LOCAL_CODEX', reviewMode: 'MODEL', revisionAttempted: false,
      content: { summary: '本次检查未提出修改建议。', scores: [], issues: [] },
    } })
    if (path.endsWith('/outlines/latest')) return route.fulfill({ json: outline })
    if (path.endsWith('/outlines')) return route.fulfill({ json: [] })
    if (path.endsWith('/style-test')) return route.fulfill({ json: { id: 'style-test', name: '风格试写验证', entryMode: 'MATERIALS', currentCanonVersion: 0, version: 4, creativeIntent: null } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/projects/style-test')
  await page.getByRole('button', { name: '分层大纲', exact: true }).click()
  await page.getByRole('button', { name: '选择风格并试写', exact: true }).click()
  await expect(page.getByRole('heading', { name: '第一章试写' })).toBeVisible()
  await page.getByLabel('风格预设').selectOption(first.name)
  await expect(page.getByLabel('段落组织', { exact: true })).toHaveValue(first.craft!.paragraphMoves)
  await expect(page.getByLabel('修辞机制', { exact: true })).toHaveValue(first.craft!.rhetoricMoves)
  await page.locator('.style-craft-examples summary').click()
  await expect(page.locator('.craft-example')).toHaveCount(2)
  await expect(page.locator('.craft-example').first()).toContainText(first.craft!.examples[0]!.positive)
  await page.getByRole('button', { name: '试写第一章开头' }).click()
  await expect(page.locator('.preview-result')).toHaveCount(1)
  expect(applied).toBeNull()
  await page.getByLabel('风格预设').selectOption(second.name)
  await page.getByRole('button', { name: '试写第一章开头' }).click()
  await expect(page.locator('.preview-result')).toHaveCount(2)
  for (const field of await page.locator('.style-craft-details textarea').all()) {
    const box = await field.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.width).toBeGreaterThan(150)
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  }
  expect(generated.map((value) => value.name)).toEqual([first.name, second.name])
  expect(generationInputs.map(input => [input.outlineVersionId, input.expectedOutlineVersion, input.targetWords]))
    .toEqual([['outline-1', 3, 800], ['outline-1', 3, 800]])
  expect(applied).toBeNull()
  await page.locator('.preview-result').filter({ has: page.getByRole('heading', { name: first.name }) })
    .getByRole('button', { name: '采用此风格' }).click()
  await expect.poll(() => applied).toEqual(first)
  expect(appliedVersion).toBe(4)
  await page.getByLabel('风格预设').selectOption(campus.name)
  await expect(page.getByLabel('叙述语气', { exact: true })).toHaveValue(campus.narrativeVoice)
  await expect(page.getByLabel('场景适配', { exact: true })).toHaveValue(campus.craft!.sceneVariants)
  await expect(page.locator('.craft-example').first()).toContainText(campus.craft!.examples[0]!.positive)
  await page.getByRole('button', { name: '试写第一章开头' }).click()
  await expect(page.locator('.preview-result')).toHaveCount(3)
  expect(generated.at(-1)).toEqual(campus)
  expect(generationInputs.at(-1)?.profile.craft).toEqual(campus.craft)
  await page.locator('.preview-result').filter({ has: page.getByRole('heading', { name: campus.name }) })
    .getByRole('button', { name: '采用此风格' }).click()
  await expect.poll(() => applied).toEqual(campus)
  expect(appliedVersion).toBe(5)
  await page.getByRole('button', { name: '故事圣经', exact: true }).click()
  await page.getByRole('button', { name: '风格试写', exact: true }).click()
  await expect(page.locator('.preview-result')).toHaveCount(3)
  await page.getByLabel('叙述立场', { exact: true }).scrollIntoViewIfNeeded()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

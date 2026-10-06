import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContractExecutionPanel from '@/components/ContractExecutionPanel.vue'
import type { ChapterContractContent } from '@/api/writing'

const content: ChapterContractContent = {
  chapterTitle: '选座', pov: '我', objective: '找座位', storyTime: '开学', locations: ['教室'],
  requiredBeats: ['旧自由文本：她问座位是否有人。', '行动 / 阻力 / 变化：留下空位。'],
  requiredReveals: ['她希望一起坐'], forbiddenFacts: ['不能获得老师名单'],
  expectedExitState: '座位确定', foreshadowActions: ['迟疑未解释'], hook: '下一次讨论',
  suggestedMinWords: 2000, suggestedMaxWords: 3000,
}

describe('contract execution view', () => {
  it('preserves old free text and does not invent structured evidence', () => {
    const wrapper = mount(ContractExecutionPanel, { props: { content } })
    expect(wrapper.findAll('.execution-beats p').map(node => node.text())).toEqual(content.requiredBeats)
    expect(wrapper.text()).toContain('不能获得老师名单')
    expect(wrapper.text()).toContain('座位确定')
    expect(wrapper.text()).not.toContain('已兑现')
  })
  it('displays missing values rather than claiming they were assessed', () => {
    const wrapper = mount(ContractExecutionPanel, { props: { content: { ...content,
      requiredBeats: [], requiredReveals: [], foreshadowActions: [], forbiddenFacts: [], hook: '' } } })
    expect(wrapper.text()).toContain('尚未填写场景节拍')
    expect(wrapper.text()).toContain('未填写')
  })
})

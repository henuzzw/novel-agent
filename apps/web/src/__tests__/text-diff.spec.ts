import { expect, it } from 'vitest'
import { compareText, diffEntries } from '@/lib/text-diff'

it('preserves exact source texts, line numbers and Unicode during aligned changes', () => {
  const before = '原句\r\n她收下纸条。\n\n最后一行'
  const after = '原句\r\n她退回纸条。\n新增一句。\n\n最后一行\n'
  const diff = compareText(before, after)
  expect(diff.rows.map(row => row.left?.value ?? '').join('')).toBe(before)
  expect(diff.rows.map(row => row.right?.value ?? '').join('')).toBe(after)
  expect(diff.rows.flatMap(row => row.left ? [row.left.number] : [])).toEqual([1, 2, 3, 4])
  expect(diff.rows.flatMap(row => row.right ? [row.right.number] : [])).toEqual([1, 2, 3, 4, 5])
  const edited = diff.rows.find(row => row.left?.value === '她收下纸条。\n')!
  expect(edited.left!.parts.filter(p => p.changed).map(p => p.text).join('')).toBe('收下')
  expect(edited.right!.parts.filter(p => p.changed).map(p => p.text).join('')).toBe('退回')
})

it('handles equal, empty, inserted, removed and newline-only content without inventing lines', () => {
  expect(compareText('', '').rows).toEqual([])
  expect(compareText('相同内容', '相同内容').hunks).toBe(0)
  expect(compareText('', '新正文').rows[0]?.kind).toBe('added')
  expect(compareText('旧正文', '').rows[0]?.kind).toBe('removed')
  const diff = compareText('甲\r\n\n乙', '甲\n\n乙')
  expect(diff.hunks).toBe(1)
  expect(diff.rows[0]?.left?.ending).toBe('CRLF')
  expect(diff.rows[0]?.right?.ending).toBe('LF')
  expect(compareText('ab𝄞cd', 'ab𝄢cd').rows[0]?.left?.parts.find(p => p.changed)?.text).toBe('𝄞')
})

it('folds unchanged ranges while preserving nearby context and supports expansion', () => {
  const before = Array.from({ length: 20 }, (_, i) => `行${i}`).join('\n')
  const after = before.replace('行10\n', '改动10\n')
  const rows = compareText(before, after).rows
  const entries = diffEntries(rows, true, new Set())
  expect(entries.filter(entry => entry.kind === 'gap')).toHaveLength(2)
  expect(entries.filter(entry => entry.kind === 'row')).toHaveLength(5)
  expect(diffEntries(rows, true, new Set([0])).filter(entry => entry.kind === 'gap')).toHaveLength(1)
  expect(diffEntries(rows, false, new Set())).toHaveLength(20)
})

it('keeps repeated lines, whitespace and very different long text intact', () => {
  for (const [before, after] of [['同一句\n同一句\n结尾', '同一句\n新增\n同一句\n结尾'],
    [' a \n\t字\n', 'a\n 字\n'], ['甲'.repeat(8000), '乙'.repeat(8000)]]) {
    const diff = compareText(before!, after!)
    expect(diff.rows.map(row => row.left?.value ?? '').join('')).toBe(before)
    expect(diff.rows.map(row => row.right?.value ?? '').join('')).toBe(after)
  }
})

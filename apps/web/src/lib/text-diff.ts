import { diffChars, diffLines, type Change } from 'diff'

export interface DiffPart { text: string; changed: boolean }
export interface DiffLine { number: number; value: string; parts: DiffPart[]; ending: string }
export interface DiffRow { index: number; kind: 'same' | 'added' | 'removed' | 'modified'; left: DiffLine | null; right: DiffLine | null; hunk: number | null }
export interface TextDiff { rows: DiffRow[]; hunks: number; addedLines: number; removedLines: number; coarse: boolean }
export type DiffEntry = { kind: 'row'; row: DiffRow } | { kind: 'gap'; start: number; count: number }

function lines(value: string): string[] { return value.match(/[^\n]*\n|[^\n]+$/g) ?? [] }
function ending(value: string): string { return value.endsWith('\r\n') ? 'CRLF' : value.endsWith('\n') ? 'LF' : '无换行' }
function content(value: string): string { return value.replace(/\r?\n$/, '') }

/** Exact line alignment with bounded Unicode character highlighting, never whitespace normalization. */
export function compareText(before: string, after: string): TextDiff {
  let coarse = false
  let changes = diffLines(before, after, { timeout: 100, maxEditLength: 4000 })
  if (!changes) {
    coarse = true
    changes = [
      ...(before ? [{ value: before, removed: true, added: false, count: 0 }] : []),
      ...(after ? [{ value: after, added: true, removed: false, count: 0 }] : []),
    ]
  }
  const rows: DiffRow[] = []
  let oldNumber = 1, newNumber = 1, hunks = 0, addedLines = 0, removedLines = 0
  const highlightDeadline = performance.now() + 150
  function line(value: string, number: number, parts?: DiffPart[]): DiffLine {
    return { number, value, ending: ending(value), parts: parts ?? [{ text: content(value), changed: false }] }
  }
  for (let i = 0; i < changes.length;) {
    const change = changes[i]!
    if (!change.added && !change.removed) {
      for (const value of lines(change.value)) rows.push({ index: rows.length, kind: 'same', left: line(value, oldNumber++), right: line(value, newNumber++), hunk: null })
      i++
      continue
    }
    let oldText = '', newText = ''
    while (i < changes.length && (changes[i]!.added || changes[i]!.removed)) {
      const part = changes[i++]!
      if (part.removed) oldText += part.value
      if (part.added) newText += part.value
    }
    const oldLines = lines(oldText), newLines = lines(newText)
    removedLines += oldLines.length; addedLines += newLines.length
    const hunk = hunks++
    for (let j = 0; j < Math.max(oldLines.length, newLines.length); j++) {
      const old = oldLines[j], next = newLines[j]
      let parts: Change[] | undefined
      if (old !== undefined && next !== undefined && performance.now() < highlightDeadline)
        parts = diffChars(content(old), content(next), { timeout: 15, maxEditLength: 2000 })
      if (old !== undefined && next !== undefined && !parts) coarse = true
      const left = old === undefined ? null : line(old, oldNumber++, parts
        ? parts.filter(p => !p.added).map(p => ({ text: p.value, changed: p.removed })) : [{ text: content(old), changed: true }])
      const right = next === undefined ? null : line(next, newNumber++, parts
        ? parts.filter(p => !p.removed).map(p => ({ text: p.value, changed: p.added })) : [{ text: content(next), changed: true }])
      rows.push({ index: rows.length, kind: left && right ? 'modified' : left ? 'removed' : 'added', left, right, hunk })
    }
  }
  return { rows, hunks, addedLines, removedLines, coarse }
}

export function diffEntries(rows: DiffRow[], onlyChanges: boolean, expanded: ReadonlySet<number>): DiffEntry[] {
  if (!onlyChanges || !rows.some(row => row.hunk !== null)) return rows.map(row => ({ kind: 'row', row }))
  const visible = new Set<number>()
  rows.forEach((row, index) => { if (row.hunk !== null) for (let j = Math.max(0, index - 2); j <= Math.min(rows.length - 1, index + 2); j++) visible.add(j) })
  const result: DiffEntry[] = []
  for (let i = 0; i < rows.length;) {
    if (visible.has(i)) { result.push({ kind: 'row', row: rows[i++]! }); continue }
    const start = i
    while (i < rows.length && !visible.has(i)) i++
    if (expanded.has(start)) for (let j = start; j < i; j++) result.push({ kind: 'row', row: rows[j]! })
    else result.push({ kind: 'gap', start, count: i - start })
  }
  return result
}

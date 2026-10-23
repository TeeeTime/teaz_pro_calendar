/*
 * Overlapping events side by side. Events that overlap, directly or through
 * others, form a group; each event takes the first column (lane) of its group
 * that's free at its start, and every event of a group divides the space by
 * the group's number of columns. Used vertically by the day view (columns
 * next to each other) and horizontally by the week view (lanes on top of each
 * other).
 */
export interface Span<T> {
  item: T
  /** Minutes since midnight. */
  start: number
  end: number
}

export interface Placed<T> extends Span<T> {
  column: number
  columns: number
}

/**
 * @param minLength short events count as at least this long, so ones that
 *   are drawn bigger than they are don't cover their neighbours
 */
export function placeSideBySide<T>(spans: Span<T>[], minLength: number): Placed<T>[] {
  const sorted = [...spans].sort((a, b) => a.start - b.start || b.end - a.end)
  const placed: Placed<T>[] = []
  let group: Placed<T>[] = []
  let columnEnds: number[] = []
  let groupEnd = -Infinity

  const closeGroup = () => {
    for (const p of group) p.columns = columnEnds.length
    group = []
    columnEnds = []
  }

  for (const span of sorted) {
    if (span.start >= groupEnd) closeGroup()
    const end = Math.max(span.end, span.start + minLength)
    let column = columnEnds.findIndex((columnEnd) => columnEnd <= span.start)
    if (column === -1) column = columnEnds.push(end) - 1
    else columnEnds[column] = end
    groupEnd = group.length === 0 ? end : Math.max(groupEnd, end)
    const p = { ...span, column, columns: 1 }
    group.push(p)
    placed.push(p)
  }
  closeGroup()
  return placed
}

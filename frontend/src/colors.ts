import type { CalendarInfo } from '@/types'

/** Calendar id to its iCloud color, for {@link eventStyle}. */
export type CalendarColors = ReadonlyMap<number, string>

export function calendarColors(calendars: CalendarInfo[]): CalendarColors {
  return new Map(calendars.flatMap((c) => (c.color ? [[c.id, c.color] as const] : [])))
}

/** Sets --event-color (see .event in main.css) to the calendar's color; without one the accent stays. */
export function eventStyle(colors: CalendarColors, calendarId: number): Record<string, string> {
  const color = colors.get(calendarId)
  return color ? { '--event-color': color } : {}
}

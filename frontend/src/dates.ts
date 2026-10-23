import type { CalendarEvent } from '@/types'

/*
 * Days are plain 'YYYY-MM-DD' strings and times are minutes since midnight,
 * both as wall-clock time in the backend's zone, wherever the viewer is. The
 * backend sends every time with Berlin's offset, so the wall-clock time is
 * simply the text before the offset. Date arithmetic runs in UTC, where no
 * day is ever 23 or 25 hours long.
 */
export const ZONE = 'Europe/Berlin'
export const MINUTES_PER_DAY = 24 * 60

const DAY_MS = 86_400_000

function utc(day: string): Date {
  return new Date(`${day}T00:00:00Z`)
}

function iso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(day: string, days: number): string {
  return iso(new Date(utc(day).getTime() + days * DAY_MS))
}

export function addMonths(day: string, months: number): string {
  const date = utc(day)
  // Day 1, so 31 January + 1 month is in February, not March.
  return iso(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + months, 1)))
}

/** 0 = Monday ... 6 = Sunday */
export function weekday(day: string): number {
  return (utc(day).getUTCDay() + 6) % 7
}

export function startOfWeek(day: string): string {
  return addDays(day, -weekday(day))
}

export function startOfMonth(day: string): string {
  return `${day.slice(0, 7)}-01`
}

export function daysBetween(from: string, to: string): number {
  return Math.round((utc(to).getTime() - utc(from).getTime()) / DAY_MS)
}

/** The days from..to, to excluded. */
export function daysOf(from: string, to: string): string[] {
  return Array.from({ length: daysBetween(from, to) }, (_, i) => addDays(from, i))
}

const TODAY = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: ZONE,
})
const NOW = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: ZONE })

/** Today in the backend's zone. */
export function today(): string {
  return TODAY.format(new Date())
}

/** Minutes since midnight now, in the backend's zone. */
export function nowMinutes(): number {
  const [h, m] = NOW.format(new Date()).split(':').map(Number)
  return (h ?? 0) * 60 + (m ?? 0)
}

/** The part of an event on {@code day}, in minutes; null if it isn't on that day. Timed events only. */
export function segmentOn(event: CalendarEvent, day: string): { start: number; end: number } | null {
  const startDay = event.start.slice(0, 10)
  const endDay = event.end.slice(0, 10)
  const startMin = minutesOf(event.start)
  const endMin = minutesOf(event.end)
  if (startDay > day || endDay < day) return null
  // Ends at midnight: nothing of it on the day it ends.
  if (endDay === day && endMin === 0 && startDay < day) return null
  return { start: startDay < day ? 0 : startMin, end: endDay > day ? MINUTES_PER_DAY : endMin }
}

/** Whether an event (all-day or timed) is on {@code day} at all. */
export function isOn(event: CalendarEvent, day: string): boolean {
  if (event.allDay) return event.start <= day && day < event.end
  return segmentOn(event, day) !== null
}

function minutesOf(dateTime: string): number {
  return Number(dateTime.slice(11, 13)) * 60 + Number(dateTime.slice(14, 16))
}

const FORMATS = {
  clock: { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' },
  dayLong: { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' },
  dayShort: { weekday: 'short', day: 'numeric', month: 'short' },
  dayMonth: { day: 'numeric', month: 'short' },
  monthYear: { month: 'long', year: 'numeric' },
  weekdayShort: { weekday: 'short' },
} satisfies Record<string, Intl.DateTimeFormatOptions>

const formatters = Object.fromEntries(
  Object.entries(FORMATS).map(([name, options]) => [
    name,
    // Plain days are midnight UTC, so they're formatted in UTC; nothing shifts them.
    new Intl.DateTimeFormat('en-GB', { ...options, timeZone: 'UTC' }),
  ]),
) as Record<keyof typeof FORMATS, Intl.DateTimeFormat>

/** e.g. dayLabel('2026-10-23', 'dayShort') = "Fri 23 Oct" */
export function dayLabel(day: string, format: keyof typeof FORMATS): string {
  return formatters[format].format(utc(day))
}

/** "09:30", for minutes since midnight. */
export function clockLabel(minutes: number): string {
  return `${String(Math.floor(minutes / 60) % 24).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
}

/** "09:30" of a timed event's start or end. */
export function timeOf(dateTime: string): string {
  return dateTime.slice(11, 16)
}

/** "09:30–10:15", or with days when it spans several: "Sat 24 Oct, 21:00 – Sun 25 Oct, 01:30". */
export function timeRange(event: CalendarEvent): string {
  if (event.allDay) {
    const last = addDays(event.end, -1)
    return last === event.start
      ? `${dayLabel(event.start, 'dayLong')}, all day`
      : `${dayLabel(event.start, 'dayShort')} – ${dayLabel(last, 'dayShort')}, all day`
  }
  const startDay = event.start.slice(0, 10)
  const endDay = event.end.slice(0, 10)
  if (startDay === endDay) {
    return `${dayLabel(startDay, 'dayLong')}, ${timeOf(event.start)}–${timeOf(event.end)}`
  }
  return `${dayLabel(startDay, 'dayShort')}, ${timeOf(event.start)} – ${dayLabel(endDay, 'dayShort')}, ${timeOf(event.end)}`
}

/** "14:02" for the time something was synced. */
export function syncedLabel(dateTime: string): string {
  return NOW.format(new Date(dateTime))
}

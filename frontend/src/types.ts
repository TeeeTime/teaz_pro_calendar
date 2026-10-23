// Shapes of the backend's JSON responses (see the records in backend/.../account and event).

export interface AccountStatus {
  connected: boolean
  /** Made-up calendars, no iCloud behind it (local dev). */
  sample: boolean
  appleId: string | null
  connectedAt: string | null
  syncedAt: string | null
  syncing: boolean
  syncError: string | null
  /** Apple rejected the stored password; syncing waits for a new one. */
  passwordRejected: boolean
}

export interface CalendarInfo {
  id: number
  name: string
  /** #rrggbb as set in iCloud, or null */
  color: string | null
}

export interface CalendarEvent {
  id: number
  calendarId: number
  title: string
  location: string | null
  allDay: boolean
  /** Timed: 2026-10-23T09:30:00+02:00 (Berlin). All-day: the first day, 2026-10-23. */
  start: string
  /** Timed: like start. All-day: the day after the last one. */
  end: string
}

export interface EventList {
  connected: boolean
  syncedAt: string | null
  syncing: boolean
  calendars: CalendarInfo[]
  events: CalendarEvent[]
}

export type ViewMode = 'day' | 'week' | 'month'

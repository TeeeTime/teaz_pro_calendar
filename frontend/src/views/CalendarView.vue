<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@teeetime/teaz-pro-ui'
import ConnectICloud from '@/components/ConnectICloud.vue'
import DayView from '@/components/DayView.vue'
import EventDetails from '@/components/EventDetails.vue'
import MonthView from '@/components/MonthView.vue'
import WeekView from '@/components/WeekView.vue'
import { api, errorMessage } from '@/api'
import { calendarColors } from '@/colors'
import {
  addDays,
  addMonths,
  dayLabel,
  daysOf,
  nowMinutes,
  startOfMonth,
  startOfWeek,
  syncedLabel,
  today,
} from '@/dates'
import type { AccountStatus, CalendarEvent, EventList, ViewMode } from '@/types'

// The backend refreshes its copy from iCloud when it's older than 2 minutes and
// we ask; asking that often keeps the page current without a reload.
const REFRESH_MS = 2 * 60_000
// While the backend syncs, ask again this soon for its result.
const FOLLOW_UP_MS = 4_000
const MODES: { mode: ViewMode; label: string }[] = [
  { mode: 'day', label: 'Day' },
  { mode: 'week', label: 'Week' },
  { mode: 'month', label: 'Month' },
]

// View and day live in the URL (?view=week&date=2026-10-23), so a reload or link keeps them.
const params = new URLSearchParams(window.location.search)
const mode = ref<ViewMode>(MODES.some((m) => m.mode === params.get('view')) ? (params.get('view') as ViewMode) : 'week')
const anchor = ref(/^\d{4}-\d{2}-\d{2}$/.test(params.get('date') ?? '') ? params.get('date')! : today())

const status = ref<AccountStatus | null>(null)
const list = ref<EventList | null>(null)
const currentDay = ref(today())
const currentMinutes = ref(nowMinutes())
const selected = ref<CalendarEvent | null>(null)
const busy = ref(false)
const confirmingDisconnect = ref(false)
// "Later" on the new-password prompt; it comes back on the next visit.
const renewLater = ref(false)
// Failed actions and failed loading are shown separately, so the periodic refresh doesn't hide an action's error.
const error = ref<string | null>(null)
const loadError = ref<string | null>(null)

/** The days on screen: from..to, to excluded. A month shows whole weeks. */
const range = computed(() => {
  if (mode.value === 'day') return { from: anchor.value, to: addDays(anchor.value, 1) }
  if (mode.value === 'week') {
    const monday = startOfWeek(anchor.value)
    return { from: monday, to: addDays(monday, 7) }
  }
  const first = startOfMonth(anchor.value)
  const last = addDays(addMonths(first, 1), -1)
  return { from: startOfWeek(first), to: addDays(startOfWeek(last), 7) }
})

const days = computed(() => daysOf(range.value.from, range.value.to))
const colors = computed(() => calendarColors(list.value?.calendars ?? []))
const events = computed(() => list.value?.events ?? [])

const title = computed(() => {
  if (mode.value === 'day') return dayLabel(anchor.value, 'dayLong')
  if (mode.value === 'month') return dayLabel(startOfMonth(anchor.value), 'monthYear')
  const first = range.value.from
  const last = addDays(range.value.to, -1)
  if (first.slice(0, 7) === last.slice(0, 7)) {
    return `${Number(first.slice(8))}–${Number(last.slice(8))} ${dayLabel(last, 'monthYear')}`
  }
  return `${dayLabel(first, 'dayMonth')} – ${dayLabel(last, 'dayMonth')} ${last.slice(0, 4)}`
})

const showsToday = computed(() => range.value.from <= currentDay.value && currentDay.value < range.value.to)

function move(step: number) {
  if (mode.value === 'day') anchor.value = addDays(anchor.value, step)
  else if (mode.value === 'week') anchor.value = addDays(anchor.value, step * 7)
  else anchor.value = addMonths(anchor.value, step)
}

function openDay(day: string) {
  anchor.value = day
  mode.value = 'day'
}

let request = 0
let followUp: ReturnType<typeof setTimeout> | undefined

async function loadEvents() {
  const { from, to } = range.value
  const id = ++request
  const result = await api.get<EventList>(`/api/events?from=${from}&to=${to}`)
  // An older request answering late must not replace what's on screen now.
  if (id !== request) return
  list.value = result
  clearTimeout(followUp)
  if (result.syncing) followUp = setTimeout(refresh, FOLLOW_UP_MS)
}

async function refresh() {
  currentDay.value = today()
  currentMinutes.value = nowMinutes()
  try {
    status.value = await api.get<AccountStatus>('/api/account')
    if (status.value.connected) await loadEvents()
    else list.value = null
    loadError.value = null
  } catch (e) {
    loadError.value = errorMessage(e)
  }
}

async function connect(appleId: string, password: string) {
  busy.value = true
  error.value = null
  try {
    status.value = await api.put<AccountStatus>('/api/account', { appleId, password })
    renewLater.value = false
    await loadEvents()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function syncNow() {
  busy.value = true
  error.value = null
  try {
    status.value = await api.post<AccountStatus>('/api/account/sync')
    await loadEvents()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function disconnect() {
  busy.value = true
  error.value = null
  try {
    await api.delete('/api/account')
    confirmingDisconnect.value = false
    await refresh()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

watch([mode, anchor], () => {
  const url = new URL(window.location.href)
  url.searchParams.set('view', mode.value)
  url.searchParams.set('date', anchor.value)
  window.history.replaceState(null, '', url)
  if (status.value?.connected) loadEvents().catch((e) => (loadError.value = errorMessage(e)))
})

/** ← → previous/next, T today, D/W/M the views; not while typing or with the details open. */
function onKey(e: KeyboardEvent) {
  if (e.altKey || e.ctrlKey || e.metaKey || selected.value || !list.value) return
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
  const actions: Record<string, () => void> = {
    ArrowLeft: () => move(-1),
    ArrowRight: () => move(1),
    t: () => (anchor.value = today()),
    d: () => (mode.value = 'day'),
    w: () => (mode.value = 'week'),
    m: () => (mode.value = 'month'),
  }
  const action = actions[e.key]
  if (action) {
    e.preventDefault()
    action()
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') refresh()
}

let timer: ReturnType<typeof setInterval> | undefined
let clock: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  refresh()
  timer = setInterval(refresh, REFRESH_MS)
  // The "now" line moves every minute.
  clock = setInterval(() => {
    currentDay.value = today()
    currentMinutes.value = nowMinutes()
  }, 60_000)
  window.addEventListener('keydown', onKey)
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(clock)
  clearTimeout(followUp)
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <div class="view calendar">
    <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
    <p v-if="loadError" class="notice notice--error" role="alert">{{ loadError }}</p>

    <ConnectICloud v-if="status && !status.connected" :busy="busy" :sample="status.sample" @connect="connect" />

    <template v-else-if="status">
      <ConnectICloud
        v-if="status.passwordRejected && !renewLater"
        class="calendar__renew"
        :busy="busy"
        :sample="status.sample"
        :renew-for="status.appleId"
        @connect="connect"
        @cancel="renewLater = true"
      />

      <header class="calendar__toolbar">
        <div class="calendar__nav">
          <button type="button" class="button" :disabled="showsToday && anchor === currentDay" @click="anchor = currentDay">
            Today
          </button>
          <button type="button" class="button button--icon" :aria-label="`Previous ${mode}`" @click="move(-1)">
            <Icon name="chevron-left" class="button__icon" />
          </button>
          <button type="button" class="button button--icon" :aria-label="`Next ${mode}`" @click="move(1)">
            <Icon name="chevron-right" class="button__icon" />
          </button>
        </div>
        <h1 class="calendar__title" aria-live="polite">{{ title }}</h1>
        <div class="calendar__tools">
          <div class="segmented" role="group" aria-label="View">
            <button
              v-for="option in MODES"
              :key="option.mode"
              type="button"
              class="segmented__option"
              :class="{ 'segmented__option--active': mode === option.mode }"
              :aria-pressed="mode === option.mode"
              @click="mode = option.mode"
            >
              {{ option.label }}
            </button>
          </div>
          <button
            type="button"
            class="button button--icon"
            aria-label="Refresh from iCloud"
            title="Refresh from iCloud"
            :disabled="busy || status.passwordRejected"
            @click="syncNow"
          >
            <Icon name="refresh" class="button__icon" :class="{ 'button__icon--spinning': busy || list?.syncing }" />
          </button>
        </div>
      </header>

      <template v-if="list">
        <DayView
          v-if="mode === 'day'"
          :day="anchor"
          :events="events"
          :colors="colors"
          :today="currentDay"
          :now-minutes="currentMinutes"
          @open="selected = $event"
        />
        <WeekView
          v-else-if="mode === 'week'"
          :days="days"
          :events="events"
          :colors="colors"
          :today="currentDay"
          :now-minutes="currentMinutes"
          @open="selected = $event"
          @select-day="openDay"
        />
        <MonthView
          v-else
          :month="startOfMonth(anchor)"
          :days="days"
          :events="events"
          :colors="colors"
          :today="currentDay"
          @open="selected = $event"
          @select-day="openDay"
        />
      </template>
      <p v-else-if="!loadError" class="calendar__loading">Loading…</p>

      <p v-if="status.syncError && !status.passwordRejected" class="notice notice--error calendar__sync-error" role="alert">
        Last sync failed: {{ status.syncError }}
        <template v-if="status.syncedAt"> Showing events as of {{ syncedLabel(status.syncedAt) }}.</template>
      </p>

      <footer class="calendar__footer">
        <p class="calendar__status">
          <span v-for="calendar in list?.calendars ?? []" :key="calendar.id" class="calendar__legend">
            <span class="swatch" :style="calendar.color ? { '--event-color': calendar.color } : {}" />
            {{ calendar.name }}
          </span>
        </p>
        <div class="calendar__account">
          <span class="calendar__status">
            iCloud: {{ status.appleId }}
            <template v-if="status.sample"> (sample data)</template>
            <template v-if="list?.syncing"> · syncing…</template>
            <template v-else-if="status.syncedAt"> · synced {{ syncedLabel(status.syncedAt) }}</template>
          </span>
          <template v-if="confirmingDisconnect">
            <span class="calendar__status">Deletes the stored password and synced events here.</span>
            <button type="button" class="button" :disabled="busy" @click="disconnect">
              <Icon name="unlink" class="button__icon" />
              Disconnect
            </button>
            <button type="button" class="button" :disabled="busy" @click="confirmingDisconnect = false">Cancel</button>
          </template>
          <template v-else>
            <button v-if="status.passwordRejected && renewLater" type="button" class="button" @click="renewLater = false">
              Enter new password
            </button>
            <button type="button" class="button" :disabled="busy" @click="confirmingDisconnect = true">
              <Icon name="unlink" class="button__icon" />
              Disconnect iCloud
            </button>
          </template>
        </div>
      </footer>
    </template>

    <p v-else-if="!error && !loadError" class="calendar__loading">Loading…</p>

    <EventDetails
      :event="selected"
      :calendar="list?.calendars.find((c) => c.id === selected?.calendarId)"
      :colors="colors"
      @close="selected = null"
    />
  </div>
</template>

<style scoped>
.calendar {
  container-type: inline-size;
}

.calendar__renew {
  margin-bottom: 24px;
}

.calendar__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  margin-bottom: 16px;
}

.calendar__nav,
.calendar__tools {
  display: flex;
  align-items: center;
  gap: 6px;
}

.calendar__title {
  flex: 1;
  min-width: 0;
  font-size: 1.3rem;
  font-weight: 700;
}

/* Narrow: navigation and tools on one line, the title on its own below. */
@container (max-width: 640px) {
  .calendar__title {
    flex-basis: 100%;
    order: 3;
    font-size: 1.15rem;
  }

  .calendar__tools {
    margin-left: auto;
  }
}

.segmented {
  display: inline-flex;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.segmented__option {
  padding: 7px 12px;
  font: inherit;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
  background-color: var(--color-surface);
  border: none;
  cursor: pointer;
}

.segmented__option + .segmented__option {
  border-left: 1px solid var(--color-border);
}

.segmented__option:hover {
  background-color: var(--color-surface-hover);
}

.segmented__option:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.segmented__option--active {
  font-weight: 600;
  color: var(--color-text-primary);
  background-color: var(--color-surface-active);
}

.calendar__sync-error {
  margin: 16px 0 0;
}

.calendar__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
}

.calendar__account {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.calendar__status {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.calendar__legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.calendar__loading {
  color: var(--color-text-secondary);
}
</style>

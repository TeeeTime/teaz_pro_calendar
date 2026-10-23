<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { eventStyle, type CalendarColors } from '@/colors'
import { clockLabel, dayLabel, isOn, MINUTES_PER_DAY, segmentOn, timeOf } from '@/dates'
import { placeSideBySide } from '@/layout'
import type { CalendarEvent } from '@/types'

const props = defineProps<{
  /** The seven days, Monday first. */
  days: string[]
  events: CalendarEvent[]
  colors: CalendarColors
  today: string
  nowMinutes: number
}>()

defineEmits<{ open: [event: CalendarEvent]; selectDay: [day: string] }>()

// Height of one lane of timed events, and of one all-day bar, in pixels.
const LANE = 34
const ALL_DAY_LANE = 22
// Shorter events are drawn this long, so a few letters of the title fit.
const MIN_MINUTES = 30

const rows = computed(() =>
  props.days.map((day) => {
    const allDay = props.events.filter((e) => e.allDay && isOn(e, day))
    const timed = placeSideBySide(
      props.events.flatMap((item) => {
        const segment = item.allDay ? null : segmentOn(item, day)
        return segment ? [{ item, ...segment }] : []
      }),
      MIN_MINUTES,
    )
    const lanes = Math.max(1, ...timed.map((p) => p.columns))
    const allDayHeight = allDay.length * ALL_DAY_LANE
    return { day, allDay, timed, allDayHeight, height: allDayHeight + lanes * LANE + 4 }
  }),
)

function percent(minutes: number): string {
  return `${(minutes / MINUTES_PER_DAY) * 100}%`
}

const scroller = ref<HTMLElement | null>(null)

/** Narrow screens scroll sideways; start at 07:00 there. */
async function scrollToMorning() {
  await nextTick()
  const el = scroller.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  const track = el.querySelector<HTMLElement>('.week__track')
  if (track) el.scrollLeft = (track.clientWidth * 7) / 24
}

onMounted(scrollToMorning)
watch(() => props.days[0], scrollToMorning)
</script>

<template>
  <section class="week">
    <div ref="scroller" class="week__scroll">
      <div class="week__table">
        <div class="week__head" aria-hidden="true">
          <div class="week__corner" />
          <div class="week__axis">
            <span
              v-for="hour in 12"
              :key="hour"
              class="week__tick"
              :style="{ left: percent((hour - 1) * 120) }"
            >
              {{ clockLabel((hour - 1) * 120) }}
            </span>
          </div>
        </div>

        <div v-for="row in rows" :key="row.day" class="week__row" :class="{ 'week__row--today': row.day === today }">
          <button
            type="button"
            class="week__day"
            :aria-label="`${dayLabel(row.day, 'dayLong')}, open day`"
            @click="$emit('selectDay', row.day)"
          >
            <span class="week__weekday">{{ dayLabel(row.day, 'weekdayShort') }}</span>
            <span class="week__date">{{ dayLabel(row.day, 'dayMonth') }}</span>
          </button>

          <div class="week__track" :style="{ height: `${row.height}px` }">
            <button
              v-for="(event, index) in row.allDay"
              :key="`all-${event.id}`"
              type="button"
              class="event week__all-day"
              :style="{ top: `${index * ALL_DAY_LANE + 2}px`, ...eventStyle(colors, event.calendarId) }"
              @click="$emit('open', event)"
            >
              {{ event.title }}
            </button>

            <button
              v-for="p in row.timed"
              :key="p.item.id"
              type="button"
              class="event week__event"
              :style="{
                top: `${row.allDayHeight + p.column * LANE + 2}px`,
                height: `${LANE - 3}px`,
                left: percent(p.start),
                width: `calc(${percent(Math.max(p.end - p.start, MIN_MINUTES))} - 2px)`,
                ...eventStyle(colors, p.item.calendarId),
              }"
              :title="`${timeOf(p.item.start)}–${timeOf(p.item.end)} ${p.item.title}`"
              @click="$emit('open', p.item)"
            >
              <span class="week__event-title">{{ p.item.title }}</span>
              <span class="week__event-time">{{ timeOf(p.item.start) }}–{{ timeOf(p.item.end) }}</span>
            </button>

            <div v-if="row.day === today" class="week__now" :style="{ left: percent(nowMinutes) }" />
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.week {
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.week__scroll {
  overflow-x: auto;
}

/* Below ~38px per hour, scroll sideways instead of squeezing the day. */
.week__table {
  min-width: calc(76px + 24 * 38px);
}

.week__head,
.week__row {
  display: flex;
}

.week__row {
  border-top: 1px solid var(--color-border);
}

.week__corner,
.week__day {
  position: sticky;
  left: 0;
  z-index: 2;
  flex-shrink: 0;
  width: 76px;
  background-color: var(--color-surface);
  border-right: 1px solid var(--color-border);
}

.week__axis {
  position: relative;
  flex: 1;
  height: 28px;
}

.week__tick {
  position: absolute;
  bottom: 6px;
  padding-left: 4px;
  font-size: 0.72rem;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
}

.week__day {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 1px;
  padding: 6px 10px;
  font: inherit;
  text-align: left;
  color: var(--color-text-primary);
  border-top: none;
  border-bottom: none;
  border-left: 3px solid transparent;
  cursor: pointer;
}

.week__day:hover {
  background-color: var(--color-surface-hover);
}

.week__day:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.week__weekday {
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  color: var(--color-text-secondary);
}

.week__date {
  font-size: 0.9rem;
  font-weight: 600;
  white-space: nowrap;
}

.week__row--today .week__day {
  border-left-color: var(--color-accent);
}

.week__row--today .week__weekday,
.week__row--today .week__date {
  color: var(--color-accent);
}

/* One vertical line per hour, a stronger one every six hours. */
.week__track {
  position: relative;
  flex: 1;
  min-width: 0;
  background-image:
    linear-gradient(to right, var(--color-border) 1px, transparent 1px),
    linear-gradient(to right, var(--color-surface-active) 1px, transparent 1px);
  background-size:
    calc(100% / 4) 100%,
    calc(100% / 24) 100%;
}

.week__all-day {
  position: absolute;
  left: 1px;
  right: 1px;
  height: calc(22px - 3px);
  padding: 0 8px;
  font-size: 0.78rem;
  font-weight: 500;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.week__event {
  position: absolute;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 6px;
  padding: 0 5px;
  font-size: 0.75rem;
  line-height: 1.2;
  white-space: nowrap;
}

.week__event-title {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
}

.week__event-time {
  overflow: hidden;
  font-variant-numeric: tabular-nums;
  text-overflow: clip;
  color: var(--color-text-secondary);
}

.week__now {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background-color: var(--color-accent);
  pointer-events: none;
}
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { eventStyle, type CalendarColors } from '@/colors'
import { clockLabel, isOn, segmentOn, timeOf } from '@/dates'
import { placeSideBySide, type Placed } from '@/layout'
import type { CalendarEvent } from '@/types'

const props = defineProps<{
  day: string
  events: CalendarEvent[]
  colors: CalendarColors
  today: string
  nowMinutes: number
}>()

defineEmits<{ open: [event: CalendarEvent] }>()

// Pixels per hour; the timeline is 24 of them, scrolled to the interesting part.
const HOUR = 48
// Shorter events are drawn this long, so their title fits.
const MIN_MINUTES = 25

const allDay = computed(() => props.events.filter((e) => e.allDay && isOn(e, props.day)))

const placed = computed(() =>
  placeSideBySide(
    props.events.flatMap((item) => {
      const segment = item.allDay ? null : segmentOn(item, props.day)
      return segment ? [{ item, ...segment }] : []
    }),
    MIN_MINUTES,
  ),
)

function position(p: Placed<CalendarEvent>) {
  const minutes = Math.max(p.end - p.start, MIN_MINUTES)
  return {
    top: `${(p.start / 60) * HOUR}px`,
    height: `${(minutes / 60) * HOUR - 2}px`,
    left: `${(p.column / p.columns) * 100}%`,
    width: `calc(${100 / p.columns}% - 3px)`,
    ...eventStyle(props.colors, p.item.calendarId),
  }
}

const scroller = ref<HTMLElement | null>(null)

/** Today: to now; otherwise to the first event, or 07:00 on an empty day. */
async function scrollToStart() {
  await nextTick()
  if (!scroller.value) return
  const first = placed.value.reduce((min, p) => Math.min(min, p.start), Infinity)
  const minutes = props.day === props.today ? props.nowMinutes - 90 : Number.isFinite(first) ? first - 30 : 7 * 60
  scroller.value.scrollTop = (Math.max(0, minutes) / 60) * HOUR
}

onMounted(scrollToStart)
watch(() => props.day, scrollToStart)
</script>

<template>
  <section class="day">
    <div class="day__all-day">
      <span class="day__gutter label">All day</span>
      <div class="day__all-day-list">
        <button
          v-for="event in allDay"
          :key="event.id"
          type="button"
          class="event day__chip"
          :style="eventStyle(colors, event.calendarId)"
          @click="$emit('open', event)"
        >
          {{ event.title }}
        </button>
        <span v-if="!allDay.length && !placed.length" class="day__empty">No events</span>
      </div>
    </div>

    <div ref="scroller" class="day__scroll">
      <div class="day__grid" :style="{ height: `${24 * HOUR}px` }">
        <div v-for="hour in 24" :key="hour" class="day__hour" :style="{ top: `${(hour - 1) * HOUR}px` }">
          <span class="day__gutter day__hour-label">{{ clockLabel((hour - 1) * 60) }}</span>
        </div>

        <div class="day__events">
          <button
            v-for="p in placed"
            :key="p.item.id"
            type="button"
            class="event day__event"
            :class="{ 'day__event--short': p.end - p.start < 50 }"
            :style="position(p)"
            :title="p.item.title"
            @click="$emit('open', p.item)"
          >
            <span class="day__event-title">{{ p.item.title }}</span>
            <span class="day__event-time">{{ timeOf(p.item.start) }}–{{ timeOf(p.item.end) }}</span>
            <span v-if="p.item.location" class="day__event-location">{{ p.item.location }}</span>
          </button>

          <div
            v-if="day === today"
            class="day__now"
            :style="{ top: `${(nowMinutes / 60) * HOUR}px` }"
            :aria-label="`Now, ${clockLabel(nowMinutes)}`"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.day {
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.day__all-day {
  display: flex;
  align-items: flex-start;
  min-height: 40px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border);
}

.day__gutter {
  flex-shrink: 0;
  width: 56px;
  padding-right: 8px;
  text-align: right;
}

.day__all-day .day__gutter {
  padding-top: 4px;
}

.day__all-day-list {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
  padding-right: 8px;
}

.day__chip {
  max-width: 100%;
  padding: 4px 8px;
  font-size: 0.85rem;
  font-weight: 500;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.day__empty {
  padding-top: 4px;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.day__scroll {
  height: min(68vh, 760px);
  min-height: 320px;
  overflow-y: auto;
}

.day__grid {
  position: relative;
}

.day__hour {
  position: absolute;
  left: 0;
  right: 0;
  display: flex;
  border-top: 1px solid var(--color-border);
}

.day__hour:first-child {
  border-top: none;
}

.day__hour-label {
  margin-top: -0.6em;
  font-size: 0.72rem;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
  background-color: var(--color-surface);
}

.day__hour:first-child .day__hour-label {
  margin-top: 2px;
}

.day__events {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 60px;
  right: 8px;
}

.day__event {
  position: absolute;
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 3px 6px;
  font-size: 0.8rem;
  line-height: 1.25;
}

.day__event-title {
  font-weight: 600;
  overflow-wrap: anywhere;
}

.day__event-time,
.day__event-location {
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}

/* Short events: one line, title and time side by side. */
.day__event--short {
  flex-direction: row;
  align-items: baseline;
  gap: 6px;
  padding-top: 1px;
  white-space: nowrap;
}

.day__event--short .day__event-title {
  overflow: hidden;
  text-overflow: ellipsis;
  overflow-wrap: normal;
}

.day__event--short .day__event-location {
  display: none;
}

.day__now {
  position: absolute;
  left: -4px;
  right: 0;
  height: 2px;
  background-color: var(--color-accent);
  pointer-events: none;
}

.day__now::before {
  content: '';
  position: absolute;
  top: -3px;
  left: 0;
  width: 8px;
  height: 8px;
  background-color: var(--color-accent);
}
</style>

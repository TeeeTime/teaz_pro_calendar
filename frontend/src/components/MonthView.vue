<script setup lang="ts">
import { computed } from 'vue'
import { eventStyle, type CalendarColors } from '@/colors'
import { dayLabel, isOn, timeOf } from '@/dates'
import type { CalendarEvent } from '@/types'

const props = defineProps<{
  /** The month's first day. */
  month: string
  /** Whole weeks around the month, Monday first. */
  days: string[]
  events: CalendarEvent[]
  colors: CalendarColors
  today: string
}>()

defineEmits<{ open: [event: CalendarEvent]; selectDay: [day: string] }>()

// Events listed per day; the rest is "+n more", which opens the day.
const VISIBLE = 3

const cells = computed(() =>
  props.days.map((day) => {
    // The backend sends all-day events first, then by start: the order a day shows them in.
    const events = props.events.filter((e) => isOn(e, day))
    return {
      day,
      events: events.slice(0, VISIBLE),
      more: Math.max(0, events.length - VISIBLE),
      inMonth: day.slice(0, 7) === props.month.slice(0, 7),
    }
  }),
)

const weekdays = computed(() => props.days.slice(0, 7))

/** The start time, unless the event began on an earlier day. */
function startTime(event: CalendarEvent, day: string): string {
  return event.start.slice(0, 10) === day ? timeOf(event.start) : ''
}
</script>

<template>
  <section class="month">
    <div class="month__head" aria-hidden="true">
      <span v-for="day in weekdays" :key="day" class="month__weekday">{{ dayLabel(day, 'weekdayShort') }}</span>
    </div>

    <div class="month__grid">
      <div
        v-for="cell in cells"
        :key="cell.day"
        class="month__cell"
        :class="{ 'month__cell--other': !cell.inMonth, 'month__cell--today': cell.day === today }"
        @click="$emit('selectDay', cell.day)"
      >
        <button
          type="button"
          class="month__date"
          :aria-label="`${dayLabel(cell.day, 'dayLong')}, open day`"
          @click.stop="$emit('selectDay', cell.day)"
        >
          {{ Number(cell.day.slice(8)) }}
        </button>

        <ul class="month__events">
          <li v-for="event in cell.events" :key="event.id">
            <button
              type="button"
              class="event month__event"
              :class="{ 'month__event--timed': !event.allDay }"
              :style="eventStyle(colors, event.calendarId)"
              :title="event.title"
              @click.stop="$emit('open', event)"
            >
              <span v-if="!event.allDay && startTime(event, cell.day)" class="month__time">
                {{ startTime(event, cell.day) }}
              </span>
              <span class="month__title">{{ event.title }}</span>
            </button>
          </li>
        </ul>

        <button v-if="cell.more" type="button" class="month__more" @click.stop="$emit('selectDay', cell.day)">
          +{{ cell.more }}<span class="month__more-word"> more</span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.month {
  container-type: inline-size;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.month__head,
.month__grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.month__weekday {
  padding: 8px;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}

.month__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  min-height: 112px;
  padding: 4px;
  border-top: 1px solid var(--color-border);
  cursor: pointer;
}

.month__cell:hover {
  background-color: var(--color-surface-hover);
}

.month__cell:not(:nth-child(7n + 1)) {
  border-left: 1px solid var(--color-border);
}

.month__cell--other {
  background-color: var(--color-bg);
}

.month__cell--other .month__date {
  color: var(--color-text-secondary);
}

.month__date {
  align-self: flex-start;
  min-width: 26px;
  padding: 2px 6px;
  font: inherit;
  font-size: 0.85rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.month__date:hover {
  background-color: var(--color-surface-hover);
}

.month__date:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.month__cell--today .month__date {
  color: var(--color-text-on-accent);
  background-color: var(--color-accent);
}

.month__events {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.month__event {
  display: flex;
  gap: 4px;
  width: 100%;
  padding: 1px 5px;
  font-size: 0.75rem;
  line-height: 1.35;
  white-space: nowrap;
}

/* Timed events: only the calendar's color strip, so all-day ones stand out as bars. */
.month__event--timed {
  background-color: transparent;
}

.month__time {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
}

.month__title {
  overflow: hidden;
  text-overflow: ellipsis;
}

.month__more {
  align-self: flex-start;
  padding: 1px 5px;
  font: inherit;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
}

.month__more:hover {
  color: var(--color-accent);
}

/* Phones: no room for titles; each event becomes a colored bar, tapping the day opens it. */
@container (max-width: 560px) {
  .month__cell {
    min-height: 72px;
    padding: 2px;
  }

  .month__weekday {
    padding: 6px 4px;
    text-align: center;
  }

  .month__date {
    align-self: center;
    font-size: 0.8rem;
  }

  .month__event {
    height: 6px;
    padding: 0;
    pointer-events: none;
    background-color: var(--event-color);
    border-left: none;
  }

  .month__time,
  .month__title,
  .month__more-word {
    display: none;
  }

  .month__more {
    align-self: center;
    padding: 0;
  }
}
</style>

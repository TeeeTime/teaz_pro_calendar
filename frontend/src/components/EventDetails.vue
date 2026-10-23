<script setup lang="ts">
import { ref, watch } from 'vue'
import { eventStyle, type CalendarColors } from '@/colors'
import { timeRange } from '@/dates'
import type { CalendarEvent, CalendarInfo } from '@/types'

const props = defineProps<{
  /** Shown as a modal while set. */
  event: CalendarEvent | null
  calendar: CalendarInfo | undefined
  colors: CalendarColors
}>()

const emit = defineEmits<{ close: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)

watch(
  () => props.event,
  (event) => {
    if (event && !dialog.value?.open) dialog.value?.showModal()
    else if (!event) dialog.value?.close()
  },
)

/** A click on the backdrop lands on the dialog itself, not on its content. */
function onClick(e: MouseEvent) {
  if (e.target === dialog.value) dialog.value?.close()
}
</script>

<template>
  <dialog ref="dialog" class="details" aria-labelledby="details-title" @close="emit('close')" @click="onClick">
    <div v-if="event" class="details__body" :style="eventStyle(colors, event.calendarId)">
      <p class="details__calendar">
        <span class="swatch" />
        {{ calendar?.name ?? 'Calendar' }}
      </p>
      <h2 id="details-title" class="details__title">{{ event.title }}</h2>
      <p class="details__line">{{ timeRange(event) }}</p>
      <p v-if="event.location" class="details__line">{{ event.location }}</p>
      <form method="dialog" class="details__actions">
        <button type="submit" class="button">Close</button>
      </form>
    </div>
  </dialog>
</template>

<style scoped>
.details {
  width: min(420px, calc(100vw - 32px));
  padding: 0;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: 0 12px 32px var(--color-shadow-strong);
}

.details::backdrop {
  background-color: var(--color-shadow-strong);
}

.details__body {
  padding: 20px;
  border-top: 4px solid var(--event-color, var(--color-accent));
}

.details__calendar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}

.details__title {
  margin: 8px 0 12px;
  font-size: 1.2rem;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.details__line {
  margin-top: 4px;
  font-size: 0.9rem;
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.details__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>

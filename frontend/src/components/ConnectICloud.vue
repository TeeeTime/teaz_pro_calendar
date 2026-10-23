<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@teeetime/teaz-pro-ui'

const props = defineProps<{
  busy: boolean
  /** Local dev: the backend makes up calendars instead of asking iCloud. */
  sample: boolean
  /** Apple rejected the stored password: asking for a new one for this Apple ID. */
  renewFor?: string | null
}>()

const emit = defineEmits<{ connect: [appleId: string, password: string]; cancel: [] }>()

const appleId = ref(props.renewFor ?? '')
const password = ref('')

function submit() {
  emit('connect', appleId.value.trim(), password.value.trim())
}
</script>

<template>
  <section class="connect">
    <h1 class="view__title connect__title">
      <Icon name="calendar" class="connect__icon" />
      {{ renewFor ? 'New app-specific password' : 'Connect your Apple calendar' }}
    </h1>

    <p v-if="renewFor" class="connect__text">
      Apple no longer accepts the stored password for {{ renewFor }}, probably because it was revoked or the
      Apple ID password changed. Your events below are from the last sync. Create a new app-specific password
      to keep them current.
    </p>
    <p v-else class="connect__text">
      Calendar shows the events from your iCloud calendars by day, week and month. It only reads them; changes
      are made on your Apple devices and show up here within a few minutes.
    </p>

    <ol class="connect__steps">
      <li>
        Open
        <a href="https://account.apple.com" target="_blank" rel="noopener">account.apple.com</a>
        and sign in.
      </li>
      <li>Go to <strong>Sign-In and Security</strong> → <strong>App-Specific Passwords</strong>.</li>
      <li>Create one named “Teaz Calendar” and paste it below. Your normal Apple ID password won't work.</li>
    </ol>

    <p v-if="sample" class="connect__text">
      This backend has no iCloud access configured, so any Apple ID shows made-up sample calendars (the
      password “wrong” is rejected, to try that).
    </p>

    <form class="connect__form" @submit.prevent="submit">
      <label class="field">
        <span class="label">Apple ID</span>
        <input
          v-model="appleId"
          class="input"
          type="email"
          autocomplete="username"
          required
          :readonly="!!renewFor"
          placeholder="name@icloud.com"
        />
      </label>
      <label class="field">
        <span class="label">App-specific password</span>
        <input
          v-model="password"
          class="input"
          type="password"
          autocomplete="off"
          required
          placeholder="xxxx-xxxx-xxxx-xxxx"
        />
      </label>
      <div class="connect__actions">
        <button type="submit" class="button button--primary" :disabled="busy || !appleId.trim() || !password.trim()">
          {{ busy ? 'Checking with Apple…' : renewFor ? 'Save password' : 'Connect' }}
        </button>
        <button v-if="renewFor" type="button" class="button" :disabled="busy" @click="emit('cancel')">Later</button>
      </div>
    </form>

    <p class="connect__fine">
      The password is stored encrypted and only used to read your calendars. Revoke it any time at
      account.apple.com; Calendar then asks for a new one.
    </p>
  </section>
</template>

<style scoped>
.connect {
  max-width: 600px;
  padding: 24px;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.connect__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.connect__icon {
  width: 28px;
  height: 28px;
  color: var(--color-text-secondary);
}

.connect__text {
  margin-top: 8px;
  color: var(--color-text-secondary);
}

.connect__steps {
  margin: 16px 0 0;
  padding-left: 20px;
  font-size: 0.9rem;
  line-height: 1.6;
}

.connect__steps a {
  color: var(--color-accent);
}

.connect__form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 20px;
}

.connect__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.connect__fine {
  margin-top: 16px;
  font-size: 0.78rem;
  color: var(--color-text-secondary);
}

@media (max-width: 420px) {
  .connect {
    padding: 16px;
  }
}
</style>

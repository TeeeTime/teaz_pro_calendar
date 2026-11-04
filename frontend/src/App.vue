<script setup lang="ts">
import { ACCOUNT_URL, TopBar, avatarUrl, useTheme } from '@teeetime/teaz-pro-ui'
import type { TopBarMenuItem } from '@teeetime/teaz-pro-ui'
import { useAuth } from '@/auth'
import CalendarView from '@/views/CalendarView.vue'

// Applies the persisted/system theme to <html data-theme> on first render.
useTheme()

const auth = useAuth()

function onMenuSelect(item: TopBarMenuItem) {
  if (item.label === 'Sign out') auth.logout()
  else if (item.label === 'Account') window.location.href = ACCOUNT_URL
}
</script>

<template>
  <TopBar
    app-name="Calendar"
    home-url="https://portal.teaz.pro"
    :user-name="auth.user.value?.name"
    :avatar-url="avatarUrl(auth.user.value?.id, 64)"
    @select="onMenuSelect"
  />

  <main>
    <CalendarView />
  </main>
</template>

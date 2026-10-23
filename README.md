# teaz_pro_calendar
Teaz Calendar, at `https://calendar.teaz.pro`: every user connects their
Apple (iCloud) calendar and sees their events by day, week and month.

Monorepo: `frontend/` (Vue 3 + TypeScript) and `backend/` (Spring Boot, `pro.teaz:calendar`).

Stack, conventions, style guide and color tokens shared by every Teaz app
live in **`teaz_pro`**: start with `docs/TECH_STACK.md` there. This
README only covers what is specific to this app.

## How it works

iCloud has no REST API or OAuth. Calendars are only reachable over CalDAV
(`https://caldav.icloud.com`) with Basic auth: the Apple ID and an
**app-specific password** (account.apple.com → Sign-In and Security →
App-Specific Passwords; the normal password doesn't work because of 2FA).
Such a password doesn't expire; it stops working when it's revoked or the
Apple ID password changes.

| Part | Where | What |
| ---- | ----- | ---- |
| Connecting | `account/AccountController.java` | `PUT /api/account` with Apple ID and app-specific password. Apple checks them first (discovery below); wrong ones are rejected with `400` and nothing is stored. Then the first sync runs before the answer. The same Apple ID again only replaces the password and keeps the events. |
| Password | `account/PasswordCipher.java` | Stored AES-256-GCM encrypted with `CALENDAR_ENCRYPTION_KEY`, the Teaz user id as associated data. Never logged, never sent back. Changing the key means every user enters their password again. |
| CalDAV | `caldav/HttpCalDav.java` | PROPFIND `current-user-principal` on `caldav.icloud.com`, then `calendar-home-set` on the principal, which points to the account's partition host (`pXX-caldav.icloud.com`); that URL is stored. Calendars: PROPFIND Depth 1 on the home, only those that allow `VEVENT` (not reminder lists, not the inbox). Redirects are followed by hand and only within `icloud.com`, so credentials never go elsewhere. |
| Sync | `sync/CalendarSync.java` | Per calendar a `sync-collection` REPORT with the stored sync token (only changes), then `calendar-multiget` for new/changed resources (by ETag). No token yet, or Apple no longer knows it: the whole calendar is compared and what's gone is deleted. Background sync every 15 minutes (`calendar.sync-interval`); reading events with a copy older than 2 minutes (`stale-after`) starts one in the background and answers from the copy right away; "Refresh" at most every 30 seconds (`refresh-cooldown`). One sync per account at a time; after 429/5xx the next automatic one waits longer each time (2, 4, ... up to 120 minutes). Apple has no push. |
| Rejected password | `CalendarSync` | Apple answers `401`: the account stops syncing (`passwordRejected`), its events stay, the UI asks for a new password. |
| Events | `sync/Occurrences.java`, ical4j | Each `.ics` is stored as fetched (`calendar_object`) and expanded into occurrences (`event`): `RRULE`/`RDATE` minus `EXDATE`, occurrences changed on their own (`RECURRENCE-ID`) replace the regular ones, cancelled ones are left out. Recurring events are expanded two years back and ahead of today (`expand-past`/`-future`), moved along once a day. |
| Time zone | everywhere | API times are in `Europe/Berlin` (`calendar.timezone`), like the other Teaz apps; all-day events and floating times are taken there too. |

The frontend and anything else only ever read the copy in the database, never
iCloud directly.

## Views

All in `frontend/src/components/`, with the overlap layout in
`frontend/src/layout.ts` (overlapping events form a group and split it into
columns):

- **Day** (`DayView.vue`): a 24-hour timeline from top to bottom, overlapping
  events side by side; all-day events above it. Scrolls to now, or to the
  first event.
- **Week** (`WeekView.vue`): the seven days below each other, each a
  horizontal 24-hour timeline; overlapping events in lanes on top of each
  other, all-day events as bars on top. Narrow screens scroll sideways.
- **Month** (`MonthView.vue`): a classic month grid with up to three events
  per day and "+n more"; on phones colored bars. A day opens the day view.

Clicking an event shows its details. View and day are in the URL
(`?view=week&date=2026-10-23`). Keys: ← → previous/next, `T` today, `D`/`W`/`M`.

## API

| Endpoint | What |
| -------- | ---- |
| `GET /api/account` | `connected`, `sample`, `appleId`, `connectedAt`, `syncedAt`, `syncing`, `syncError`, `passwordRejected`. |
| `PUT /api/account` | Body `{ "appleId", "password" }`: connect, or a new password. `400` if Apple rejects them. Returns the account. |
| `POST /api/account/sync` | Refresh now (cooldown 30 s). Returns the account; a failure is in `syncError`. |
| `DELETE /api/account` | Deletes the stored password and every synced calendar and event. |
| `GET /api/events?from=2026-10-19&to=2026-10-26` | Events overlapping those days (`to` excluded, at most 100 days), all-day first, then by start. |
| `GET /api/events/today` | The same for today in Berlin, plus `date` (`"2026-10-23"`). The API for other apps: Portal's Calendar tile. |

```json
{
  "connected": true,
  "syncedAt": "2026-10-23T14:02:10+02:00",
  "syncing": false,
  "calendars": [{ "id": 1, "name": "Work", "color": "#1badf8" }],
  "events": [
    { "id": 7, "calendarId": 1, "title": "Trip", "location": null, "allDay": true,
      "start": "2026-10-24", "end": "2026-10-26" },
    { "id": 3, "calendarId": 1, "title": "Dentist", "location": "Hauptstr. 5", "allDay": false,
      "start": "2026-10-20T08:30:00+02:00", "end": "2026-10-20T09:15:00+02:00" }
  ]
}
```

All-day `end` is the day after the last one. `syncing: true` means a sync is
running (maybe just started by this request); ask again shortly. Errors are
RFC 9457 problem details.

`/api/events/today` (only that path) is readable cross-origin, with the
caller's Keycloak access token (any Teaz app's token works, same realm; no
role needed), from the origins in `calendar.cors.allowed-origins`
(`application-prod.properties`, currently `https://portal.teaz.pro`). Fields
are only ever added to it, never renamed or removed.

## Running locally

```sh
cd backend && ./mvnw spring-boot:run
cd frontend && npm run dev
```

Or use the IntelliJ run configs in `.idea/runConfigurations/`. Locally:

- **No Docker, no database install:** file-based H2 in PostgreSQL mode
  (`backend/data/`, gitignored). Delete that folder for a fresh start.
- **Offline:** `calendar.sample=true` (the default outside prod) makes up two
  calendars around the current week (`caldav/SampleCalDav.java`: overlapping
  meetings, all-day and multi-day events, one across midnight, recurring ones
  with an exception). Any Apple ID works; the password `wrong` is rejected.
- **With the real iCloud:** set `CALENDAR_SAMPLE=false` in the backend's
  environment. Without `CALENDAR_ENCRYPTION_KEY`, a fixed dev key is used
  outside prod (fine locally, not for real data you care about).
- Auth is mocked on both sides; there's one local user.
- Backend port 8080 is also the other apps' default: only one of them can
  run locally at a time.

Tests (`./mvnw verify`) run the whole flow against `FakeICloud`, a small
CalDAV server on localhost that answers like iCloud (redirect, partition
host, sync tokens, multiget, 401, 503).

## Shared UI

`TopBar`, `Icon`, `useTheme()` and the theme tokens come from
**`teaz_pro_ui`** (pinned in `frontend/package.json`), see
`teaz_pro/docs/UI_LIBRARY.md`. Event colors are the calendars' colors from
iCloud (data, not theme), mixed into the theme's surface so text keeps its
contrast in every theme.

## Auth

- Frontend `frontend/src/auth/`: Keycloak (`.env.production`: `auth.teaz.pro`,
  realm `teazpro`, client `calendar`) in builds, mock user in `npm run dev`.
- Backend `backend/src/main/java/pro/teaz/calendar/security/`, with the
  `prod` profile: every request needs a Keycloak Bearer JWT. **No role**:
  every Teaz user may use Calendar. Each user's account and events are keyed
  by the token's `sub`.

Keycloak client setup: see the Auth section in `teaz_pro/docs/TECH_STACK.md`.

## Database

Calendar uses the shared Postgres server on the VPS (`/opt/teazpro/postgres`),
reached over the external Docker network `db`, with its **own database
`calendar` and own user `calendar`** (owner of that database, no superuser
rights). It never reads another app's database.

- Schema: Flyway migrations in `backend/src/main/resources/db/migration`.
- Tables: `calendar_account` (Apple ID, encrypted password, calendar home,
  sync state), `calendar` (name, color, sync token), `calendar_object` (raw
  `.ics`, href, ETag), `event` (occurrences, what the API reads). Everything
  but the account can be fetched again from iCloud.
- Connection only from the environment: `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` in `.env`.

## Deployment

Once, on the Postgres server: create the user and database, exactly as in
`teaz_pro_study`'s README ("First deploy"), with `calendar` instead of
`study`. Then `.env` next to `compose.yml` (gitignored):

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/calendar
SPRING_DATASOURCE_USERNAME=calendar
SPRING_DATASOURCE_PASSWORD=<the password given to the calendar role>
CALENDAR_ENCRYPTION_KEY=<openssl rand -base64 32>
```

Back up `CALENDAR_ENCRYPTION_KEY` with the database: without it, the stored
passwords are useless and every user has to enter theirs again.

Then, as for every app:

```sh
git pull && docker compose up -d --build
```

`compose.yml` routes `calendar.teaz.pro` to the frontend (nginx) and
`calendar.teaz.pro/api/*` to the backend, via the shared Traefik (network
`proxy`, cert resolver `le`). The backend also joins the external network
`db` for Postgres.

# Nebula 3.0 — starting point

Branch: `nebula-3.0` (cut from `main` at v2.0.79 / desktop 0.18.3). Pushes here build a **test APK** (CI artifact,
not a release) and the notch film. Releases still only come from `main`; 2.x fixes keep shipping from `nebula-2.0`.

## How tasks work today (2.x)

### Phone (Kotlin)
| Piece | File | What it does |
|---|---|---|
| Model | `data/Models.kt` → `NebulaTask` | id, title, conversationId, status (IN_PROGRESS / REQUIRES_INPUT / COMPLETED / FAILED / CANCELLED), plan, steps, summary, fileIds, input (a question for you), kind (message/document/screen/web/phone/general/**reminder**), dueAt (reminders only). Progress is *guessed* from steps vs plan. |
| Storage | `data/Store.kt` | One JSON file per list (conversations, tasks, files), all loaded into memory, `StateFlow` to the UI. |
| Engine | `agent/TaskEngine.kt` | Chat → LLM with tools → loop. A **task is only created when the first tool runs**; plain answers never become tasks. Pauses with REQUIRES_INPUT and resumes from a saved run. |
| Reminders | `intel/Planner.kt` | Reminders are tasks with `kind = "reminder"`; alarms + notifications (snooze / done / ask). |
| Where they show | `ui/app/TasksScreen.kt`, `ui/app/NebulaRoot.kt`, `notch/NotchUi.kt` (TASK mode), `widgets/Widgets.kt` (Tasks + Daily) | |

### Desktop (Electron)
| Piece | File | What it does |
|---|---|---|
| Model + storage | `desktop/src/store.js` | Same shape, one JSON file. **No reminders / due dates.** |
| Engine | `desktop/src/agent.js` (`TOOLS`, `Agent.loop`) | Same loop as the phone, different tools (files, documents, PC control). |
| Where they show | `desktop/src/app.html` (Tasks view + task sheet), `island.html`, `widgets.html` (Tasks + Daily) | |

## Weak spots worth fixing in 3.0
1. **Tasks = "jobs the agent ran", not things *you* need to do.** There is no real to-do list; reminders are bolted on (phone only).
2. **Phone and PC don't share tasks** — two separate stores, no sync.
3. **Progress is a guess** (steps ÷ plan), so bars can stall or jump.
4. **A task is tied to one conversation** and only exists once a tool runs.
5. **Everything is written twice** (Kotlin + JS) with small differences.
6. Storage is "load everything, rewrite the whole file" — fine for hundreds of tasks, not thousands.

## Waiting on
The 3.0 brief (task management redesign) and the redesign for the Mood PLAY numbers.

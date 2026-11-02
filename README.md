# Nebula 2.0

A voice-first assistant for Samsung Galaxy phones that **does things**, not just explains them:
it operates your screen, sends messages you approve, creates real Office/PDF files, and shows live
activity in the **Nebula Notch**. Every task reports an honest, verified status.

## Install on your phone (no computer needed)

1. On your phone, open **https://github.com/thysbango-star/Nebula/releases/latest/download/Nebula.apk**
2. Open the file and tap **Install** (allow your browser to install unknown apps if asked). It updates over older versions.
3. Follow the welcome tour: allow the permissions you want, then paste your free Groq key (console.groq.com → API Keys).
4. **Screen control / Notification access greyed out?** Android blocks them for apps installed from a file:
   Settings › Apps › Nebula › ⋮ (top right) › **Allow restricted settings**, then turn them on.

Windows version: **https://github.com/thysbango-star/Nebula/releases/download/desktop/Nebula-Setup.exe**

## What it does

| Area | How it works | Verified by |
|---|---|---|
| **Task engine** | Groq `gpt-oss-120b` with function tools. Plans multi-step tasks, acts, checks, recovers, and pauses for your input. | Each tool reports `verified`; the app (not the AI) decides the status. |
| **Screen control** | Accessibility service: read the screen as numbered elements, tap, type, scroll, back/home/recents. | Screen content changing after each action; typed text read back. |
| **Messaging** | Contact lookup, SMS, WhatsApp, calls. Always asks you first (calls and payments can't skip this). | SMS: the radio's "sent" result. WhatsApp: the message box clearing after Send. Calls: phone goes off-hook. |
| **Documents** | DOCX (headings, lists, tables, quotes, page numbers), XLSX (multiple sheets, formulas, currency/percent/date formats, totals, filters, dropdown validation, native column/bar/line/pie charts), PPTX (6 designed layouts), PDF, CSV, TXT, MD, HTML. Saved to Downloads/Nebula. Edit by voice ("add a column and total it"). | The file is re-opened and structurally checked before it's reported. |
| **Nebula Notch** | Overlay over the camera hole: unlock scan → ✓, incoming calls (answer/decline), volume level, message previews (reply/open), live task progress, music (art, equaliser, controls). | n/a |
| **Sound identity** | Original Nebula motif (A4 → F♯5 → B5 → E6, FM glass bells): wake, listening, success, needs-you, failure, notification and a ringtone you can set as default. | n/a |
| **Voice** | "Hey Nebula" (offline wake word), voice requests go through the same engine; approvals can be spoken ("yes, send it"). | n/a |
| **App** | Chat with live task cards and tappable question/approval cards, Tasks board (assign, filter, progress, detail with every verified step), Files, history, Settings, onboarding. Light & dark. | n/a |

### Task states
- **COMPLETED**: the final action was verified by a tool (or you confirmed it worked).
- **IN PROGRESS**: still executing (visible in the app and the Notch).
- **REQUIRES INPUT**: Nebula needs an answer or your approval. If the last action couldn't be confirmed, Nebula asks you to check instead of claiming success.
- **FAILED**: an action failed, with the reason. (CANCELLED: you stopped or declined it.)

## Known limits (honest)

- **Face unlock**: Android gives apps no access to the system face-unlock result, so the Notch animation is driven by the real *screen-on → unlocked* events (works for face, fingerprint or PIN).
- **Samsung's own volume and call screens** still appear; apps can't hide them. The Notch shows alongside.
- **Screen control** depends on apps exposing accessibility info; games and some custom-drawn apps can't be read.
- **Editing files**: works on files Nebula created (it keeps an editable spec). Editing arbitrary existing Office files isn't supported yet.
- **Images in documents** aren't generated yet (layouts use shapes and colour instead).
- **Groq free tier** is ~8K tokens/minute on the big model: long screen tasks may pause briefly ("waiting for Groq") and retry automatically.

## Architecture

```
app/src/main/java/com/nebula/assistant/
  agent/     TaskEngine (plan→act→verify loop, pause/resume, status rules), Tools (definitions + executor), GroqClient
  device/    NebulaAccessibility (screen read/act + hosts the Notch), PhoneActions (contacts, SMS, WhatsApp, calls, apps, clock, torch)
  docs/      DocModel, DocJson (AI spec ⇄ model), DocxWriter, XlsxWriter, PptxWriter, PdfWriter, TextWriters, DocumentStore
  notch/     NotchController (overlay, volume, unlock), NotchUi, NebulaNotificationListener (media, messages, calls), NotchBus
  audio/     Sounds (SoundPool cues, ringtone install/default)
  data/      Models (tasks/steps/messages/files), Store (JSON persistence + live flows), NebulaPrefs
  brain/     LocalBrain (instant offline commands), AgentBrain (voice → engine)
  ui/app/    NebulaRoot, ChatScreen, TasksScreen, FilesScreen, SettingsScreen, Onboarding, Components, AppTheme
  ui/        MainActivity, AssistantActivity + VoiceController (voice overlay), vibrance/ (glow + island)
  wake/      WakeWordEngine + WakeWordService ("Hey Nebula", sherpa-onnx)
desktop/     Nebula for Windows (Electron)
```

Builds: every push to `main` builds and publishes the APK (GitHub Actions); pushes to `nebula-2.0` build a test APK artifact.
Secrets: the Groq key lives only in the app's private storage; the signing password is a GitHub secret.

## Build it yourself (optional)

Open the folder in Android Studio and press Run, or `./gradlew assembleRelease` with JDK 17 and the Android SDK.

## Licences

Space Grotesk and Manrope fonts: SIL Open Font License (see `licenses/`). sherpa-onnx: Apache 2.0.

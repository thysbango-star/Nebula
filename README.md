# Nebula

A personal voice assistant for Samsung Galaxy phones, with a matte "liquid glass" look:
**"Hey Nebula" wake word, a glass voice panel, phone commands, and a Groq AI brain with web search.**

## Install on your phone (no computer needed)

1. On your phone, open **https://github.com/thysbango-star/Nebula/releases/latest/download/Nebula.apk**
2. Open the downloaded file. If asked, allow your browser to **install unknown apps**.
3. Open Nebula, tap **Allow** on each Setup row, and switch on **Hey Nebula**.
4. Under **AI brain**, paste your free Groq key (from console.groq.com/keys) → **Save** → **Test**.

Every push to `main` builds a new APK on GitHub Actions and publishes it at that same link.
Installing a newer APK updates the app and keeps your settings.

## Nebula for Windows

Download on your PC: **https://github.com/thysbango-star/Nebula/releases/download/desktop/Nebula-Setup.exe**
(or `Nebula-Portable.exe` from the same release to run without installing). If Windows says
"Windows protected your PC", click **More info → Run anyway** (the app isn't code-signed).

- **Vibrance island** hangs from the top of the screen: time and a live orb at rest; hover for the date strip and an ask box;
  it pours open with your question and Nebula's answer. Timers count down in it.
- **Vibrance glow** wraps the whole screen with flowing edge light and a rising aurora that breathes with your voice
  (click-through, so you can keep working).
- **Wake it** with `Alt + Space` (changeable), the Copilot key, clicking the island, or by saying **"Hey Nebula"** (offline).
- **Brain:** Groq (gpt-oss-120b + browser search), with Groq Whisper for speech-to-text and Windows voices for replies.
- Lives in the tray; your Groq key is encrypted with Windows' own protection. Source: `desktop/`.

## Build it yourself (optional, needs a computer)

1. Install **Android Studio** (latest stable).
2. **File › Open** and pick this `Nebula` folder. Let Gradle sync (first sync downloads a lot; give it a few minutes).
   If Studio offers to upgrade the Android Gradle Plugin, it's safe to accept.
3. On your phone: **Settings › About phone › Software information**, tap **Build number** 7 times,
   then go to **Settings › Developer options** and turn on **USB debugging**.
4. Plug the phone in, pick it in the device dropdown, and press **Run ▶**.
5. In Nebula: tap **Allow** on each row under **Setup**, then switch on **Hey Nebula**.

## What's in it

| Part | What it does |
|---|---|
| Wake word | Fully offline "Hey Nebula" detection (sherpa-onnx, ~5 MB model). Nothing leaves the phone until Nebula wakes. |
| Listener service | Keeps listening in the background, even with the screen off. Shows the required "Listening" notification with a *Turn off* button. |
| **Vibrance** | Nebula's visual system. When it wakes, a ribbon of flowing colour traces up the screen edges (following your phone's real corner curve) and breathes with your voice; comets race around it while thinking, and it pulses while speaking. A black island grows out of the camera hole, then pours open into a card with your words, the answer, sources and controls. Swipe the island up, tap outside or press Back to close. Glow strength is adjustable on the home screen. |
| Liquid orb | Nebula's animated face (in the island and on the home screen): wobbles like a droplet, swells with your voice, swirls faster while thinking, pulses while speaking. |
| Phone commands | Time, date, battery, flashlight on/off, timers, alarms, "open <app>". Instant and offline. |
| AI brain | Everything else goes to Groq (openai/gpt-oss-120b), with browser search when your key allows it, so it can answer about news, weather, sports and more. Remembers the conversation while the panel is open. Source links appear under answers. |
| Home screen | Wake word toggle, sensitivity slider, speak-replies toggle, permission checklist, "Talk to Nebula" button. |

## Wake word test results

Tested with the exact model and keyword file in this app, on synthetic speech in three voices
(US female, US male, UK male), including sound-alike traps like "Hey Nebraska", "Hey Nicola",
"Hey Bella" and "a new nebula":

| Sensitivity | Caught "Hey Nebula" | False wakes |
|---|---|---|
| Relaxed | 6 / 9 | 0 / 33 |
| **Balanced (default)** | **9 / 9** | **0 / 33** |
| Eager | 9 / 9 | 0 / 33 |

Real rooms are noisier than synthetic speech. If it misses you, slide toward **Eager**; if it wakes by accident, slide toward **Relaxed**.

## Good to know

- **Your Groq key** is stored only inside Nebula's private app storage (backups are off). It never goes into this repo.
  If Groq retires a model, Nebula switches to the best model your key can still use. The free tier allows about 1,000 answers a day.
- **Android 14+ rule:** a background mic service can only start while Nebula is on screen. If One UI kills the
  listener, just open Nebula and it restarts itself. Allowing *Unrestricted battery* and adding Nebula to
  *Never sleeping apps* makes this rare.
- **Appear on top** is what lets Nebula pop up over other apps. Without it you'll get a "Tap to talk" notification instead.
- Speech-to-text uses the phone's built-in recognizer (usually Google's). If voice input fails, update the Google app.
- The wake word library (`app/libs/sherpa-onnx-1.13.8.aar`, Apache 2.0) has been trimmed to arm64 + x86_64 to keep the project small.

## Code map

```
app/src/main/java/com/nebula/assistant/
  wake/   WakeWordEngine (detector), WakeWordService (background listener), Notifications
  brain/  NebulaBrain + HybridBrain, LocalBrain (phone commands), GroqBrain (AI + web search)
  ui/     MainActivity + HomeScreen, AssistantActivity, VoiceController (speech in/out)
  ui/vibrance/  VibranceGlow (edge light), VibranceIsland, VibranceOverlay
  ui/components/  GlassSurface, NebulaOrb, NebulaBackground
  data/   NebulaPrefs (settings), NebulaState (live state)
app/src/main/assets/kws/  wake word model + keywords.txt
```

## Next steps

1. **Default assistant:** register Nebula so the corner-swipe gesture and side key open it.
2. **Island everywhere:** keep a resting Vibrance island on screen for music, timers and charging.

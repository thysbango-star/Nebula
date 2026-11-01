# Nebula

A personal voice assistant for Samsung Galaxy phones, with a matte "liquid glass" look:
**"Hey Nebula" wake word, a glass voice panel, phone commands, and a Gemini AI brain with web search.**

## Install on your phone (no computer needed)

1. On your phone, open **https://github.com/thysbango-star/Nebula/releases/latest/download/Nebula.apk**
2. Open the downloaded file. If asked, allow your browser to **install unknown apps**.
3. Open Nebula, tap **Allow** on each Setup row, and switch on **Hey Nebula**.
4. Under **AI brain**, paste your free Gemini key (from aistudio.google.com) → **Save** → **Test**.

Every push to `main` builds a new APK on GitHub Actions and publishes it at that same link.
Installing a newer APK updates the app and keeps your settings.

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
| Assistant panel | Glass sheet that springs up over any app (and the lock screen), frosts the app behind it on Android 12+, shows your words live, and answers out loud. Swipe down, tap outside or press Back to close. |
| Liquid orb | Nebula's animated face: wobbles like a droplet, swells with your voice, swirls faster while thinking, pulses while speaking. |
| Phone commands | Time, date, battery, flashlight on/off, timers, alarms, "open <app>". Instant and offline. |
| AI brain | Everything else goes to Google Gemini (gemini-2.5-flash) with Google Search, so it can answer about news, weather, sports and more. Remembers the conversation while the panel is open. Source links appear under answers. |
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

- **Your Gemini key** is stored only inside Nebula's private app storage (backups are off). It never goes into this repo.
  The free tier includes up to 500 web-searched answers a day.
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
  brain/  NebulaBrain + HybridBrain, LocalBrain (phone commands), GeminiBrain (AI + web search)
  ui/     MainActivity + HomeScreen, AssistantActivity + AssistantPanel, VoiceController (speech in/out)
  ui/components/  GlassSurface, NebulaOrb, NebulaBackground
  data/   NebulaPrefs (settings), NebulaState (live state)
app/src/main/assets/kws/  wake word model + keywords.txt
```

## Next steps

1. **Default assistant:** register Nebula so the corner-swipe gesture and side key open it.
2. **The island:** the floating notch overlay, with the panel morphing out of it.

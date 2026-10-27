# TundraXR — Multimodal Assistant

An exploration of AI + interaction + context in XR. The assistant interprets what the user is seeing, selecting, and asking about together — video, image, gesture, and voice as one context — then grounds its answers and actions in relevant documents and captured context, not in a standalone text reply.

The focus is interaction. In XR, the most important input is often not what the user typed, but what they are looking at, pointing to, or working on in the moment. An assistant that ignores that context will answer the wrong question, however fluent its reply sounds.

What this system explores:

- How multimodal input — what is seen, selected, and said — can be combined into a single understanding of the user's current situation.
- How answers can be grounded in a specific document passage or captured evidence, so the user can trace why the assistant said what it said.
- How an assistant should act in a hands-free, real-world setting, where interrupting, over-explaining, or hallucinating a detail has real costs.


Why this is hard: context in XR is continuous, noisy, and partial. The work here is about deciding what matters in the moment, grounding it, and acting on it without demanding more attention than the task deserves.

## Run the brain (laptop)

Requires Node 22.18+ (runs TypeScript directly).

```bash
cd server
npm install
cp .env.example .env          # optional: add TWELVELABS_API_KEY for video/photo analysis + search
npm start                     # http://localhost:8787  (live dashboard)
```

**Manuals.** The OEM PDFs aren't in the repo (they're Medtronic's). Download them from [manuals.medtronic.com](https://manuals.medtronic.com) (United States → English → search by document number) and save them in `server/manuals/` under these names:

| Device | Document | Save as |
|---|---|---|
| Puritan Bennett 980 operator's manual | PT00169219 | `pb980-operators-manual.pdf` |
| Nellcor PM100N service manual | PT00156333 | `pm100n-service-manual.pdf` |
| Valleylab FT10 v5.x service manual | PT00208001 | `ft10-service-manual.pdf` |

Devices whose PDF is missing are skipped. Add your own in `server/manuals/catalog.json`.

**Voice fallback (optional).** If the headset's speech recognizer fails, the app sends audio to the laptop:

```bash
brew install whisper-cpp
mkdir -p server/models
curl -L -o server/models/ggml-base.en.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin
```

## Run the app (headset or XR emulator)

Open the project in Android Studio and run `app` on a Galaxy XR over USB or on the Android XR emulator. The app talks to `http://localhost:8787`; the server keeps `adb reverse tcp:8787 tcp:8787` up automatically for connected devices. To point it elsewhere: `./gradlew installDebug -Ptundra.serverUrl=http://<host>:8787`.

Without the server, the app falls back to a built-in demo device.

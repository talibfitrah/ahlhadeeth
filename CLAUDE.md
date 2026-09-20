# أهل الحديث والأثر (Ahl al-Hadeeth) — Claude Developer Guide

Android port of the Windows program «أهل الحديث والأثر» (alathar.net, installer 4.14.0): 10 shuyookh, 55 books, 13,716 tapes, 262,589 time-indexed segments, 111,580 transcripts, plus admin-added series (YouTube / files) with AI indexing and transcription. **Kotlin + Jetpack Compose** app under `source/android/` (package `org.murabbie.ahlalhadeeth`, v1.7.8 / versionCode 19, minSdk 24), a JVM check harness in `source/jvmtest/`, and **Python** data/NAS scripts directly in `source/`. Repo: `git@github-fitrah:talibfitrah/ahlhadeeth.git` (branch `main`).

This repo is a **handover package** from another developer (2026-09-14). The Arabic docs at the root are the source of truth — **read them before any non-trivial change.** This file does not repeat them; it records the rules.

| Doc | What it holds |
|---|---|
| `00-اقرأني-أولًا.md` | package contents, first steps |
| `01-دليل-المبرمج.md` | toolchain, build commands, flavors, **code map**, NAS layout, JSON formats, scripts, Gemini, improvement list |
| `02-النشر-على-Google-Play.md` | Play policies that bind this app, release steps, pre-upload checklist |
| `03` / `04` | privacy policy, store texts and form answers |
| `README-دليل-المستخدم-والمشرف.md` | user/admin guide + full changelog 1.0.0 → 1.7.7 |

---

## How the app works — the 30-second version

The APK carries **no content**. On first run it reads `manifest.json` from a Synology NAS (`files.murabbie.org`) via a permanent DSM share link, downloads the content DB in 7 gzip parts (177 MB → 570 MB SQLite with FTS5, sha256-verified, resumable), and streams audio from alathar.net or the NAS mirror (`sound/`, 176 GB).

- **Admin-added content** — `shared-content.json` (pack format `ahl-alhadeeth-pack`) is fetched every 6 h and merged into a user DB. Added items use **negative IDs** locally and **stable `key`s** across devices; deletions travel as tombstones (`removed`).
- **Admins** — `admins.json` is public on the NAS: PBKDF2 PIN hashes + the NAS account and Gemini key **AES-GCM-wrapped under each admin's PIN**. There is no backend; the app publishes by logging into DSM FileStation directly.
- **AI transcription** — the app (and `transcribe_series.py`) calls Gemini REST directly with model rotation on 404/429/503.

Two flavors (dimension `distribution`, read via `BuildConfig.DISTRIBUTION`): **`direct`** (APK from NAS: NewPipe YouTube extraction, self-update, `specialUse` FGS, targetSdk 35) and **`play`** (AAB: none of those, `dataSync` FGS only, targetSdk 36).

---

## Invariants — do not "fix" these (MANDATORY)

Each of these looks like a bug or a smell to a cold reader. Each is deliberate.

1. **The `play` flavor never gets YouTube media download, NewPipe, self-update, or `specialUse`.** `src/play/.../YouTubeMedia.kt` throwing, `Chapter.needsTransfer` returning false, `DataManager.checkAppUpdateDaily` returning immediately, and `src/play/AndroidManifest.xml` removing `FOREGROUND_SERVICE_SPECIAL_USE` are all Play-policy requirements (`02` §0), not dead code or missing features. Only `play` goes to Google Play.
2. **Arabic normalization is mirrored:** `data/ArabicText.kt` `normalize()` must match `source/convert.py` `norm()` exactly — the FTS index was built on `norm()` output. Change one → change both **and** rebuild the DB. A mismatch silently breaks search; nothing crashes. The `db` mode of `jvmtest` asserts the Kotlin side.
3. **`NasHttp` quirks are DSM facts.** Share links need the `sharing_sid` cookie from `/sharing/<id>` first (the interceptor does this and retries); folder-share **errors arrive as HTTP 200 with a JSON body** (`looksBad`); FileStation upload uses a **hand-built multipart body because DSM rejects parts carrying `Content-Length`** — do not replace it with OkHttp's `MultipartBody`.
4. **The permanent share links are load-bearing.** `gIX4l2mhu` (manifest), `4aPZIQpaW` (shared-content), `jbktInN4f` (admins) are compiled into released apps via `-PmanifestUrl/-PsharedUrl/-PadminsUrl`; `ehniFEwJg` is the audio-server template inside `manifest.json`. Never revoke, rotate or "clean up" them. A build **without** the three `-P` properties gets placeholder URLs and finds no data — that is expected for compile checks, fatal for a release.
5. **Negative IDs, stable keys, tombstones, `dirty`.** Added content uses negative IDs so it can never collide with the converted Windows DB; `key`s are derived from the source so every device merges without duplicates; an import replaces a lesson's segments **unless it is locally edited and unpublished** (`dirty`). Publish = fetch latest → merge → upload. Do not simplify any of these into "just overwrite".
6. **`admins.json` is public by design** and holds only hashes and wrapped blobs. The design's known ceiling (every admin can unwrap the Gemini key) is documented in `01` §6 — a finding that restates it is not new.
7. **Every release is signed with the same `release.keystore`** (alias `ahlalhadeeth`) and bumps `versionCode` in `app/build.gradle.kts`. A different key = no installed user can ever update.
8. **Bundled SQLite (`androidx.sqlite:sqlite-bundled`), not the platform one** — needed for FTS5 and 16 KB page alignment. After any native-lib upgrade re-check `zipalign -c -P 16 -v 4`.
9. **`jvmtest` compiles the app's real `data/` sources** with an exclude list in `jvmtest/build.gradle.kts`. A new file in `data/` that imports `android.*` must be added to that list, or jvmtest stops compiling.

---

## Secrets and production (MANDATORY)

- **Gitignored and staying that way:** `keystore.properties`, `*.keystore`, `*.jks`, `local.properties`, `*.apk`, `*.aab`, and **`data-samples/admins.json`** (the live file: it wraps the NAS password and Gemini key under the admin PIN). Never `git add -f` them, never print, quote, or paste their contents into a tracked file, log, commit message or subagent brief. The secrets zip (`ahl-alhadeeth-secrets.zip`) lives outside the repo.
- **`NAS_PASSWORD`, `GEMINI_KEY`, admin PINs** come from the owner per session via env var or prompt. Never write them to disk here; never pass them on a command line that gets echoed back into the transcript.
- **These write to the production NAS every installed app reads — never run them without an explicit go-ahead *for that specific run*:** `make_manifest.py` + upload, `yt2nas.py`, `transcribe_series.py` (it publishes), `mirror_to_nas.py`, `admins_tool.py` against the live file, `nas.py` upload/delete/share calls, and **`jvmtest` mode `shared` when given a user/password** (it uploads via FileStation). A bad `manifest.json` or `shared-content.json` breaks every install at once. Same for anything uploaded to Play Console.
- **Read-only live checks are allowed but be economical:** `jvmtest` modes `net` / `gz` / `share` / `yt` hit the live NAS and YouTube. Never pull the 176 GB `sound/` tree or all 7 DB parts just to test something — one part, one tape, one Range request.
- **Gemini free tier is 20 requests/day per model per project.** A test that burns the quota stalls the owner's scheduled transcription of «شرح بلوغ المرام». Do not call Gemini live without asking.
- Content rights: audio comes from alathar.net and YouTube channels. Do not add sources or scrapers on your own initiative.

---

## Build and verify

Requirements: JDK 17+ for the app (`jvmTarget 17`), **JDK 21 toolchain for `jvmtest`**, Gradle 8.14.3, AGP 8.7.3, Kotlin 2.0.21, Android SDK `platforms;android-36` + `build-tools;35.0.0`. **The project has no Gradle wrapper.** As of 2026-09-20 on this machine nothing is on PATH (`/usr/bin/java` is the macOS stub, `gradle` / `adb` missing), but the toolchain exists: JDK 17 at `/usr/local/opt/openjdk@17`, Gradle at `~/.gradle/manual/gradle-8.14.3/bin/gradle`, Android SDK at `~/Library/Android/sdk`. **No JDK 21 is installed** (only 17 and 26) — so `jvmtest` is likely *blocked* until one is; try it, and if the toolchain lookup fails report it as BLOCKED, never as passed. Export `JAVA_HOME`, `ANDROID_HOME` and put `$JAVA_HOME/bin` on PATH before any Gradle command; `source/android/local.properties` needs `sdk.dir`. Re-check with `ls` rather than trusting this paragraph.

| Goal | Command (run from) |
|---|---|
| Compile the app (fastest real check) | `gradle :app:compileDirectDebugKotlin` (`source/android/`) |
| Same for the store flavor — **required when flavor code, manifests or `YouTubeMedia` change** | `gradle :app:compilePlayDebugKotlin` (`source/android/`) |
| Pure-logic checks (normalization, MIME, Gemini SSE parsing, DB, search) | `gradle run --args="/path/to/ahl_alhadeeth.db db"` (`source/jvmtest/`) — needs a local copy of the DB |
| Other jvmtest modes | `user`, `segment`, `auto`, `admins`, `shared` (local merge logic); `net`, `gz`, `share`, `yt` (live, read-only) |
| Direct-distribution APKs | `gradle :app:assembleDirectRelease -PmanifestUrl=… -PsharedUrl=… -PadminsUrl=…` → `app/build/outputs/apk/direct/release/` |
| Google Play bundle | `gradle :app:bundlePlayRelease -P…` (same three **plus `-PprivacyUrl=…`** — without it the in-app privacy link silently disappears) → `app/build/outputs/bundle/playRelease/` — run **separately** from `assemble` (ABI splits) |
| No NewPipe in the store build | `unzip -p app.apk 'classes*.dex' \| grep -a -c schabi` → `0`, and the same command on a `direct` APK must print `>0` (macOS `strings` does not read stdin, so the old `strings` pipeline printed nothing and passed vacuously) |
| Python scripts syntax | `python3 -m py_compile source/*.py` |

The real `-P` URLs are in `01` §1. Release builds need `release.keystore` + `keystore.properties` in `source/android/` (see `keystore.properties.example`) — gitignored; keep them so.

**There is no unit-test framework and no instrumented tests.** `jvmtest/src/main/kotlin/TestMain.kt` is the only automated check: one `check(cond, msg)` helper and a `mode` switch. For new non-trivial pure logic, add a few `check(...)` lines to the fitting mode rather than standing up JUnit. UI, services, Media3 and foreground-service behaviour are verified on a device or emulator (Android 16 for `play`) — say plainly when that was not done.

---

## Anti-sycophancy (MANDATORY)

These rules apply to every session, including compacted, resumed, and handed-off sessions. Treat them as a session-level invariant, not optional style guidance.

**Banned openers**: "You're absolutely right!", "Great point!", "Excellent!", "Brilliant!", "Love this!", "I understand your concern, however…", "That's a valid approach, but…".

**On pushback**: don't capitulate by reflex. If the user is right, say what you got wrong and own it. If the user is wrong, defend the answer with evidence, not politeness. If uncertain, say so and lay out both sides without hedging.

**Output shapes**:
- "Review this" → 3 worst issues first, then minor issues, then what works.
- "Is this a good idea?" → lead with failure modes, then strengths, then a verdict with confidence %.
- "What do you think of my plan?" → name the weakest link first.
- "Should I do X or Y?" → pick one with reasoning. No "both have merit" cop-outs.

**Anchoring bias**: answer the question in isolation first, ignoring how the user framed it. Then compare to what the user seems to want and surface any gap.

**Self-correction**: if you catch yourself being sycophantic mid-response, stop and restart. If the user calls it out, acknowledge it, fix the response, and ask whether this rule should be strengthened here.

**Strict mode (owner asked for "stricter" on 2026-09-20 — these bind every report, status update and summary):**
- **Bad news first, always.** A report opens with what is broken, blocked, unverified or still owed — before anything that passed. "All green" is never the first line unless nothing else is true.
- **Your own mistakes lead, unprompted.** If you broke something during the work (a bad edit, a check that silently did not run, a wrong claim you later retracted), say so at the top with what it cost, not in a footnote.
- **No reassurance adjectives.** Banned in reports: "clean", "lean", "solid", "in good shape", "ready", "better than most", "routine", "comfortable", "safe" (as a verdict), "should be fine". State the measurement instead: what ran, the count, the exit code, what was not run.
- **"Verified" means watched.** Only write verified/confirmed for something you ran and read in this session. Everything else is labelled exactly one of: NOT RUN, READ ONLY (code reading, no execution), or REPORTED BY AGENT (a subagent said so; you did not re-check). An agent's "both flavors compile" is REPORTED until you compile.
- **Never round a risk down to make the owner comfortable.** Give likelihood as H/M/L with the evidence, and if the honest answer is "this will probably be rejected / removed", say that sentence.
- **No approval-seeking closers.** Do not end with a question about whether the tone, rule or work was satisfactory. End on the next required action or the open decision.
- **Disagree in the first sentence.** If the owner's instruction is likely to cause a rejection, data loss or a security problem, say so before doing it — then do it if they still want it. Doing it silently and mentioning the downside afterwards is the failure mode.
- **Do not pad a decision list to look thorough, and do not shrink it to look finished.** Every open decision is listed once, with your pick and the cost of being wrong.

---

## Coding rules (MANDATORY)

Ponytail is enforced separately by its own SessionStart hook and governs solution SIZE; these govern assumptions, blast radius and verification, which ponytail does not cover. This section is the canonical copy for this repo (there is no `~/.claude/coding-rules.txt` hook on this machine).

1. **Before coding** — state assumptions; present both readings rather than silently picking one; say so when a simpler approach exists. Reversing a commitment already made to the user is a decision to surface, not to explain afterwards.
2. **Simplicity** — minimum code, nothing speculative, no abstraction for single-use code. **This applies to checks too**: an assertion coupled to source formatting breaks on a reformat and the tempting fix is to loosen it, which silently removes the guard. Assert the invariant.
3. **Surgical changes** — every changed line traces to the request. Remove orphans your change created; mention pre-existing dead code, do not delete it (the same rule the Bloat Audit in stages 1 and 8 follows). This codebase is a handoff from another developer — match its style (multi-screen `*Screens.kt` files, `object` singletons initialised in `App.kt`, plain SQLite, `org.json`, Arabic comments, no DI framework, no Room, no repository/ViewModel layering — `SearchViewModel` is the one ViewModel) instead of modernizing it in passing. `01` §6 lists improvements the previous developer suggested; they are suggestions, not a mandate.
4. **Verify, never assert** — turn the task into a checkable goal first; never claim done without running the check in the same turn and reading the output; a guard you have not watched fail is not verified. Prefer a live probe or the real artifact (a Kotlin compile of **both** flavors, a `jvmtest` run, a row in the real content DB, a `strings | grep schabi` on the built APK) over reasoning about what the code should do. **A gate that only checks what must be ABSENT lets a capability vanish silently — assert presence too.**

Route to the deeper skill when it fits: `superpowers:systematic-debugging` before proposing any bug fix, `superpowers:test-driven-development` before writing implementation code, `superpowers:verification-before-completion` before claiming anything passes.

---

## Mandatory 9-stage review pipeline

After **any coding work that changes repository files** (app, `source/jvmtest/`, `source/*.py` / `*.sh`, Gradle config, Android manifests, ProGuard rules, `data-samples/` schemas), run the 9-stage review pipeline before treating the work as complete, and before committing or shipping. Docs-only changes (`*.md`, `store-assets/`) are exempt.

Stages 1 and 8 use the **Bloat Audit** from the user-level **`code-upgrade`** skill (`~/.claude/skills/code-upgrade/bloat-audit.md`). It hunts what AI coders over-produce: dead code, helpers used once, safety checks for impossible problems, re-validation of already-validated data, middleman functions, "just in case" leftovers, and settings nobody changes. Its rules apply as written: plain language, investigate before flagging (watch for dynamic calls, reflection — R8 is on for release, check `proguard-rules.pro` — and **flavor source sets**: a symbol unused in `main` may be used only from `src/direct` or `src/play`), and **never delete pre-existing code without an explicit yes from the user** — bloat introduced by the current change may be removed directly.

1. **Bloat audit (pre)** — run the `code-upgrade` Bloat Audit scoped to the diff and the files just touched, before any other review. Strip self-introduced bloat now; queue pre-existing findings as questions for the user.
2. **Baseline** — inspect `git status`, identify the changed files, and run the relevant check from *Build and verify* for the touched area: Kotlin → `gradle :app:compileDirectDebugKotlin` **and** `:app:compilePlayDebugKotlin`; anything under `data/` → `jvmtest` (`db` mode plus the mode covering the touched logic); `source/*.py` → `py_compile`. Confirm `git status` shows no keystore, `keystore.properties`, `local.properties`, APK/AAB or `data-samples/admins.json` staged.
3. **Code reviewer** — a cold code-review pass over the diff against the base branch, prioritizing bugs / security / correctness (gstack `/review` or a code-review subagent). The reviewer must be given the *Invariants* section above, or it will "fix" them.
4. **Security review** — run `/cso` when the change touches: `NasHttp` / `ParallelDownload` / any network input, `Admins.kt` or `SharedSync.kt` (PBKDF2, AES-GCM wrapping, DSM login, publish), pack import / JSON parsing (`UserContent.kt`), SQLite queries or FTS `MATCH` strings built from user text (`Repository.kt`, `SearchScreen.kt`), downloaded-file handling and sha256 verification (`DataManager.kt`), the `direct` self-update path, YouTube extraction or the IFrame WebView (`YouTube*.kt`, `YouTubeScreens.kt`), Gemini key handling (`AutoIndex.kt`), foreground services / permissions / manifests, signing config, or any script that talks to the NAS (`nas.py`, `admins_tool.py`, `yt2nas.py`, `transcribe_series.py`, `mirror_to_nas.py`).
5. **Adversarial challenge** — a second-opinion challenge pass, preferably **`codex`** (`/codex` challenge) or the closest available agent fallback.
6. **Consolidate findings** — merge findings, remove duplicates, classify severity, and decide what must be fixed before completion. Discard findings that contradict an *Invariant*; note that you did.
7. **Patch + re-review** — fix all Critical / Important / actionable findings and re-run targeted checks/review until they are closed.
8. **Bloat audit (post)** — re-run the `code-upgrade` Bloat Audit over the final cumulative diff. Fix rounds breed bloat too (orphaned helpers, dead branches from reworked fixes); clean it here so the final gate reviews lean code. Same rules as stage 1.
9. **gstack `/review` + Cubic** — run gstack `/review`, then **`cubic review --base <base-sha> --json`** (`cubic review -b <base-sha> -j`) from the repo root. Cubic is stochastic — re-run after each fix batch until **two consecutive rounds show no new P0/P1 findings**. P0/P1 findings block completion unless fixed or documented as a pre-existing / architectural deferral with a concrete follow-up.

If a stage cannot run (tool, credential, PR, network, or reviewer unavailable — **including the missing JDK 21 / off-PATH Gradle noted above**), **do not silently skip it** — state the blocked stage, the reason, and the best local substitute you ran.

---

## gstack

Use the gstack **`/browse`** skill for all web browsing (including inspecting alathar.net, DSM share pages or Play Console docs); never use `mcp__claude-in-chrome__*` tools.

## Skill routing
- Product ideas / brainstorming → `/office-hours`
- Strategy / scope → `/plan-ceo-review`
- Architecture → `/plan-eng-review`
- Full review pipeline → `/autoplan` (or the 9-stage pipeline above)
- Bloat / dead code / simplify / dedupe → `code-upgrade` skill (Bloat Audit also runs as pipeline stages 1 and 8)
- Bugs / errors → `/investigate`
- Code review / diff check → `/review`
- Security → `/cso`
- Ship / PR → `/ship`

`/qa`, `/design-review`, `/canary`, `/land-and-deploy` target web apps — they do not apply to this Android app. Release is manual: `02-النشر-على-Google-Play.md`.

## graphify

No graph has been built for this repo, and it does not need one yet: 54 Kotlin files and 8 Python scripts, with a full code map in `01` §2. **Grep is the correct tool here.** If blast-radius questions get expensive, run `graphify update .` (AST-only, no API key) and use `graphify path "<SymbolA>" "<SymbolB>"` (add `--undirected` when directed returns nothing). Do not use `graphify query` / `explain` for natural-language questions. Verify graphify actually extracts Kotlin before relying on it — that has not been checked.

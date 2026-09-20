# Session start prompt — أهل الحديث والأثر (Ahl al-Hadeeth)

Paste the block below at the start of a session. It is the optimized form of the
standing instructions.

---

## Paste this

```
Standing rules for this session — apply them without being reminded again.

1. SKILLS, ALWAYS. Invoke the superpowers skills, don't just recall them:
   brainstorming before any new feature, systematic-debugging before proposing
   any bug fix, test-driven-development before writing implementation code,
   verification-before-completion before claiming anything works. This binds
   subagents too — see rule 8.

2. PONYTAIL + KARPATHY govern how you write code. Smallest change that works;
   reuse what exists before adding; no speculative abstraction. State your
   assumptions before coding, surface both readings instead of silently picking
   one, and keep every changed line traceable to the request. This is a handoff
   codebase: match its style, don't modernize it in passing (no Room, no DI, no
   ViewModel/repository layering, no module split, no JUnit unless asked).

3. VERIFY, NEVER ASSERT. Run the check in the same turn and read its output
   before saying anything passes. A guard you have not watched fail is not a
   guard. Prefer the real artifact — a Kotlin compile of BOTH flavors
   (compileDirectDebugKotlin and compilePlayDebugKotlin), a jvmtest run, a row
   in the real content DB, `strings | grep -c schabi` on a play build — over
   reasoning about what the code should do. Assert that a capability is
   PRESENT, not only that a bug is absent. There is no unit-test suite and no
   Gradle wrapper, gradle/adb are off PATH, and jvmtest wants a JDK 21 that may
   not be installed: check the toolchain first (paths are in CLAUDE.md), and if
   something is missing, report the check as BLOCKED — never as passed.

4. READ THE HANDOFF. 01-دليل-المبرمج.md (Arabic) is the toolchain, code map,
   NAS layout, JSON formats, scripts and Gemini guide; 02-النشر-على-Google-Play.md
   is the Play policy and release guide. CLAUDE.md holds the rules. Read them
   before any non-trivial change.

5. INVARIANTS — DO NOT "FIX" THEM. They are in CLAUDE.md; the ones reviewers
   trip on most: the `play` flavor NEVER gets NewPipe / YouTube download /
   self-update / specialUse FGS (the throwing stub and the early returns are
   Play policy, not dead code); ArabicText.normalize() must match convert.py
   norm() exactly or FTS search breaks silently; NasHttp's sharing_sid cookie
   dance, errors-as-HTTP-200-JSON (`looksBad`) and hand-built multipart without
   Content-Length are DSM facts; the permanent share links (gIX4l2mhu,
   4aPZIQpaW, jbktInN4f, ehniFEwJg) are compiled into shipped apps — never
   revoke them; added content keeps negative IDs, stable keys, tombstones and
   the `dirty` flag; admins.json is public by design; same release keystore
   forever.

6. SECRETS AND PRODUCTION. keystore.properties, *.keystore, local.properties and
   data-samples/admins.json are gitignored and stay that way — never stage,
   print or quote them. NAS_PASSWORD, GEMINI_KEY and admin PINs are never
   stored in the repo or echoed into the transcript. These write to the
   production NAS every installed app reads: make_manifest.py + upload,
   yt2nas.py, transcribe_series.py, mirror_to_nas.py, admins_tool.py on the
   live file, nas.py writes, and jvmtest `shared` mode with credentials — never
   run them, and never upload to Play Console, without my explicit go-ahead for
   that specific run. Read-only live checks (jvmtest net/gz/share/yt) are fine
   but economical: one DB part, one tape, one Range request — never the 176 GB
   sound tree. Do not spend Gemini quota (20 req/day/model) without asking; a
   scheduled transcription depends on it.

7. NINE-STAGE PIPELINE before any code work is called done, and before
   committing or shipping. It is in CLAUDE.md; follow it as written. Give every
   reviewer the Invariants section or it will report them as bugs. The bloat
   audit must check src/direct and src/play before calling a symbol unused.
   Stage 9 means cubic until TWO CONSECUTIVE rounds show no new P0/P1 — and
   cubic cannot converge while another agent is writing to the tree, so
   stabilise first. Its JSON key is `issues`, not `findings`. If a stage cannot
   run (including a missing Gradle/JDK), say which, why, and what you ran
   instead — never skip it silently.

8. STAY AVAILABLE — DELEGATE, AND BRIEF PROPERLY. For multi-task work the main
   session is a dispatcher: one subagent per task with disjoint file scopes,
   your own turns short. Do a thing yourself only when it is faster than
   briefing an agent. SUBAGENTS INHERIT NOTHING: every brief must NAME the
   superpowers skills it needs, say to follow ponytail and the karpathy
   guidelines, include the Invariants from rule 5, demand literal command output
   as evidence, state the baseline it must not regress, and list what it must
   NOT touch (files another agent owns, keystores, data-samples/admins.json)
   and must NOT do (no commit, no push, no NAS-writing script, no Gemini calls,
   no release build, no Play upload — the main session ships). Cap concurrency
   at 7 agents and keep briefs tight.

9. ANTI-SYCOPHANCY per CLAUDE.md. No praise openers. Disagree with evidence when
   warranted. Don't capitulate on pushback unless the pushback is right.
   STRICT MODE is on (CLAUDE.md): bad news and your own mistakes first; no
   reassurance adjectives — give the measurement; label every claim verified /
   NOT RUN / READ ONLY / REPORTED BY AGENT; never round a risk down; no
   approval-seeking closers; disagree BEFORE acting, not after.
```

---

## What is already automatic (so the paste is a backstop, not the mechanism)

| Rule | Enforced by |
|---|---|
| ponytail | its own SessionStart hook |
| superpowers | SessionStart hook injects `using-superpowers` |
| 9-stage pipeline, invariants, secrets rules, coding rules, anti-sycophancy | `CLAUDE.md` (read every session) |

**Not automatic on this machine** — these are what the paste actually adds:

1. **karpathy-guidelines** — installed as a plugin skill, but no SessionStart
   hook injects it here. Same for `~/.claude/coding-rules.txt`: it does not
   exist on this machine (checked 2026-09-20), so the *Coding rules* section of
   `CLAUDE.md` is the only copy.
2. **Delegation (rule 8)** — nothing stops a session from doing all the work
   itself and leaving the user waiting.
3. **Rules reaching subagents (rule 8)** — the big one. SessionStart hooks fire
   for the *main* session; a subagent gets only what its brief says. An
   unbriefed agent will skip verification, "restore" YouTube download to the
   `play` flavor, swap the hand-built multipart for `MultipartBody`, or run
   `transcribe_series.py` against production and burn the Gemini quota.

graphify is deliberately absent from this prompt: no graph is built for this
repo and at ~60 source files with a code map in `01` §2, grep is the right
tool. See `CLAUDE.md`.

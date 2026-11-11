# AllFather design system

The app's look: **monochrome cyberpunk techwear, editorial**. Code lives in `app/src/main/java/app/hermesandroid/ui/` (`theme/`, `components/`, `shell/`,
`icons/`). References: `docs/design/concepts/` (screen concepts `*5.jpg`, identity `identity8.jpg`,
`style-prompt.txt`).

## Identity
- **AllFather**: an elder cyborg gentleman with a black eye patch, a chrome jaw plate, a seam of white light,
  coat, cane and ravens. Portraits in `res/drawable-nodpi` (`af_avatar`, `af_login`, `af_empty`, `af_voice`);
  the launcher, notification and tile glyph is the vector bust (`ic_launcher_fg`, `ic_notification`).
- The name is written as a **wordmark**: uppercase, tracking 0.32em (`Wordmark`). The agent's name in text
  comes from `defaultProfileTitle` / `profileTitle(…)`, never hard-coded.
- **Rejected** (don't bring back): orb, monogram, explicit Norse motifs, background grid, fake `// SEC.01`
  labels, oversized type, a single loud accent on black, neon.

## Color (dark only)
| Token (`C.colors`) | Value | Use |
|---|---|---|
| `canvas` | `#0F0F10` | screen background |
| `surface` | `#1A1A1B` | cards (`settingsCard`), composer, floating cards |
| `raised` | `#222224` | wells, pressed rows, segmented thumb (`well`) |
| `hairline` | white 8 % | 1 px rules and card outlines (`Dp.Hairline`) |
| `text` / `accent` | `#E9EAEC` | text, primary actions (off-white, never pure white) |
| `secondary` / `tertiary` | text at 60 % / 35 % | supporting text, placeholders |
| `success` | `#4CD98A` | **only** the live dot |
| `attention` / `orange` | `#F0B04A` | warnings, reconnecting |
| `red` | `#E5484D` | errors, destructive actions |

Outside Compose (widget, shortcut icons, notifications) the same palette lives in `res/values/colors.xml`
(`af_canvas`, `af_surface`, `af_ink`, `af_ink_secondary`, `af_outline`, `af_hairline`); keep the two in step.

## Type
- **Geist** (variable, 300–700) for everything; **Geist Mono** with tabular figures for data (`CType.data`,
  `CType.mono`): times, counts, ids, durations, code, logs. Not the system monospace (Settings' connection sheet
  still has it: the user wanted Settings left as it is).
- Hierarchy comes from **weight and tracking, not size**: light uppercase titles (`ScreenHeader`), small
  letterspaced eyebrows (`CType.eyebrow`, 11 sp, 0.28 em, via `Eyebrow`), 14–15 sp body.
- Widgets can't load Geist: they use the system `sans-serif-light` / `sans-serif-medium`.

## Shape, surface, motion
- Corners are continuous (Kyant Capsule): `Radii.sm` 10, `md` 14, `lg` 20, `xl` 28 (floating cards), `pill`.
  Older screens still carry plain `RoundedCornerShape`s: move them to `Radii` when touched.
- Surfaces: `Backdrop` (flat canvas), `settingsCard` (surface, no stroke), `well` (raised + hairline),
  `glass`/`frosted` (Haze blur, only over moving content: the sidebar over the chat), `fadeToCanvas` (portraits
  fading into the page).
- Motion: the `Motion` springs (`response`, `transition`, `ease`, `overshoot`); `reducedMotion()` freezes
  ambient motion (HUD rings, `ScanLine`). Pages slide in from the right and follow the predictive back gesture
  (`PredictiveBackLayer`: shrink to 90 %, round, lean with the finger), as do the bot room and the call; the sidebar
  uses Material's own predictive back.
- Icons: Lucide, 1.5 stroke, generated into `ui/icons/AF.kt` by `scripts/lucide.py` (add a name there and
  regenerate). No Material icons.

## Building blocks
- **Page**: `PageBar` (back + optional trailing `CapsuleIcon`) over `ScreenHeader(eyebrow, TITLE, data)`; lists
  group under `SectionHeader` (letterspaced, fold chevron, count while folded).
- **Actions**: `SettingsButton` (hairline pill; `prominent` = off-white fill) and `CapsuleIcon` (40×44 icon
  button; `selected` sits in a well). `PrimaryButton` only for the one main action of a form (login).
- **Rows and cards**: `SettingsRow` with `IconWell`, `Card` (eyebrow title + surface card), `Divider`.
- **Live state**: `ScanLine` (a hairline with a sweeping light) while connecting or working; `HudPortrait`
  (portrait in thin rings that open with the voice level) for calls and the assistant; `CallStatus` for the
  call's state and timer in mono.
- **Assistant**: `assist/AssistCard.kt`, a floating `xl` card over a dimmed app, shared by Gemini Live and the
  dictation panel.

## Structure
The chat is home. The sidebar (Claude-style, frosted) holds New chat, Pulse, Tasks, Bots, Projects,
Artifacts, pinned and recent chats, and the profile at its foot (tap: Settings, long press: profiles). Each
section is a `Page` pushed over the chat. Pulse is a page of cards (thumbs, Done, Not now, Talk about it);
its chat hides from the list and opens from the page's bar.

## Outside the app
- Widget (`res/layout/widget_launcher.xml`): graphite card, the profile as an eyebrow over a light title, an
  outlined "Nuevo chat" pill and an off-white "Hablar" pill.
- Launcher shortcuts: adaptive icons, the app's Lucide glyph in off-white on graphite.
- Notifications: `newNotification()` (push/PushService.kt) sets the AllFather glyph and the off-white accent.

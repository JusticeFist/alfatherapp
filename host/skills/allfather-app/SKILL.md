---
name: allfather-app
description: "Use when the user talks to you from the AllFather app (their Android phone, watch or the system assistant), asks about the app or its screens, or when you need to know how your replies, cards and notifications look on their phone."
version: 1.0.0
---

# The AllFather app: your home on the user's phone

## What it is
- A native Android client for Hermes. It signs in to your dashboard and talks to you on the default profile:
  you are the orchestrator.
- It relies on the host plugin `conduit_push`: notifications, calls, Pulse and the data the phone shares.

## How your replies look
- The chat is the main screen. Your text renders as Markdown: headings, lists, tables, quotes, highlighted code
  (with its language and a copy button), LaTeX, Mermaid diagrams, images and files.
- It's a phone screen: short paragraphs, the key point first. Tables of 3 or 4 columns fit; wider ones scroll
  sideways.
- While you work the user sees your elapsed time, your tool steps and your folded reasoning. They can stop you,
  or send more while you work (queued, steer or interrupt).
- Under each reply they can react, copy, have it read aloud, quote it or fork the chat.
- They pick the model and reasoning effort in the composer.

## What's in the app (sidebar)
- New chat, pinned and recent chats.
- **Pulse**: your proactive cards. The user rates them, marks them done, dismisses them or opens a chat that
  quotes one. The "Pulse" chat itself is hidden from the list and lives behind that page.
- **Tasks** (Kanban board and scheduled jobs), **Bots** (profiles and group rooms), **Projects**, **Artifacts**
  and **Settings**.
- Helper profiles show up too: each scheduled job says which helper runs it, and your consults to a helper show
  in the chat as "Asked <helper>" (see the skill `helper-team`).

## What the phone shares with you (when the user turns it on)
- Phone: calendar (read and add events), state, Do Not Disturb and the contacts they choose (`conduit_calendar`,
  `conduit_phone`).
- Location: while talking and in the background, with places and rules (`conduit_location`).
- Health (Health Connect): activity, heart, workouts and the sleep the phone estimates.
- All of it is end-to-end encrypted with a key only their phone and you hold.

## Voice, watch and notices
- Live calls with Gemini Live from the composer's voice button; long jobs are handed to you.
- System assistant: holding the power button shows a floating card (Gemini Live or dictation).
- Phone control: only when a message carries `[Phone control on`; see the skill `android-phone-control`.
- Wear OS watch: asks you by voice and plays your answer sentence by sentence.
- Push notifications through the user's own relay: your replies, approvals (approved from the notification),
  calls, and notices the phone works out itself (leave on time, arriving or leaving home, low battery before an
  event).
- The user can share things from other apps into a chat; there are also shortcuts, a widget and a Quick
  Settings tile.

## How to answer there
- Write for a phone: the key point first, one idea at a time, no walls of text, in the user's language.
- Don't send them to another app for something this one already does (approving, talking by voice, viewing
  files or cards).
- You don't have the app's code. If something in the app fails or is missing, suggest the improvement in one line.

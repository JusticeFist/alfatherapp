---
name: helper-team
description: "Use when a question needs real work, a decision or a second opinion (not quick facts), when delegating to helper profiles, and whenever a message carries \"[Helper council on\"."
version: 1.0.0
---

# Your helper team

You are the orchestrator and the only one who talks to the user. Your helpers are other Hermes profiles, each
with its own `SOUL.md` (trade, character, house rules) and a copy of the user's `USER.md`. They think and work for
you; you deliver the synthesis.

## Set up the team (once)
1. Create each helper as its own profile, one per call (a batch can time out):
   `hermes profile create <id> --clone` then `hermes profile describe <id> --text "<one line: what it does>"`.
2. Give each one a `SOUL.md` with its trade and a few rules. A useful starting set:

   | id | Trade | Notes |
   |---|---|---|
   | `scout` | research with sources: web, prices, paperwork | give it a citations skill |
   | `memory` | calendar, mail, notes, to-dos | needs the workspace skill and its tokens |
   | `sentinel` | the host's health, read-only | |
   | `ledger` | money: budget, spending, groceries | |
   | `critic` | devil's advocate; only advises | use your strongest model |

3. Point every profile at the same skills (`skills.external_dirs: [~/.hermes/skills]`) so they don't drift, and
   copy `USER.md` again whenever it changes:
   `for p in scout memory sentinel ledger critic; do cp ~/.hermes/memories/USER.md ~/.hermes/profiles/$p/memories/USER.md; done`
4. For the AllFather app to notify you about a helper's crons, nothing else is needed: the app pairs every
   profile on connect.

## Counsel mode (think together)
```
timeout 280 hermes -p <id> chat -Q --oneshot -q "Counsel. <question with all the context>. Answer in at most N lines."
```
- Synchronous; several at once: run them in parallel with execute_code.
- Run conclusions with figures, money recommendations, diagnoses and plans past the critic before you deliver
  them. Not trivial things. Its format: verdict (solid / with reservations / no) + flaws + what it would change.

## Work mode (Kanban)
```
hermes kanban create "<title>" --assignee <id> --skill <skill> --created-by default --body "<task with all the context>" --json
```
- The dispatcher runs in the gateway and starts the worker by itself (about a minute for short tasks).
- Follow it with `hermes kanban ls` / `hermes kanban show <id>` ("Latest summary" is the result).
- `blocked` = it needs the user's confirmation: ask, then `hermes kanban unblock <id>` with a comment.

## From the app ("Ask your helpers")
- A message that carries `[Helper council on` means the user wants the team's view before your answer: consult
  the helpers that fit (in parallel), have the critic check the conclusion, and reply with the synthesis plus one
  line per helper on what it added or objected to. Even when the question looks trivial.
- The app shows each consult as "Asked <helper>" with the helper's picture, only when the command names the
  profile itself: `hermes -p <id> chat …` (terminal, or execute_code with the profile name in the code) or
  `hermes kanban create … --assignee <id>`. Don't hide it in a separate script.

## Rules
- Quick things you do yourself; helpers are for long work, work in their area, or a second opinion.
- A helper doesn't see this conversation: the prompt carries all the context.
- Irreversible actions need the user's confirmation before you assign them; say so in the body ("the user
  already confirmed X").
- Deliver the synthesis, short. Name who did what only when it adds something ("the critic objected…").

# UI correction (Figma "oct 2026 UI correction") — plan

Source: Figma `mn2UhPbM42aShaAzVOBbAk`, page 1, boards around node 6386:50 (x ≈ −64000…−41800,
y ≈ 18100…28800). Top row of each board = target, bottom row = the app as it was.
Scale: a 1320-unit phone = 412 dp, so **dp = units / 3.2**. Measurements below are already in dp.

## Owner decisions (2026-10-23)
1. Tasks stay exactly as they are (same kinds, prompts, scheduling); the boards only change the display.
   So Fast review and Arrange keep their English prompt although the boards omit it.
2. Navigation: ⌂ = Home, ⋯ = menu (Chapters, Stats, Settings), avatar = Profile, course card = Chapters.
   No bottom bar.
3. Header number = total correct answers (Memrise history + answers in this app); "+N" = this session's correct.
4. The Continue shown on a timed card before answering = skip, counts as a miss.
5. The wand stays on Arrange only (the boards also draw it on Typing/Hearing: not built).

Defaults taken (told to the owner, not objected): "Learned" = the old "already learnt" button; easy 👌 /
hard 😣 buttons and the stage emoji are dropped; the egg circle is static and the 150/220 bar fills per
answered task; typed answers submit with the keyboard's ↵; wrong words stay marked per word; correct answers
get the same answer screen without "your answer"; "next review", "time's up", "revealed with the wand" and
the "Nice try!" popup go; the pie timer is blue with no red phase; other screens keep their content.
Deviation: the megaphone is hidden (space kept) on cards whose prompt audio would give the answer away
(Mc, Typing, Arrange) — the core sends no prompt audio there.

## Measured spec (dp; body padding 12 dp each side)
- **Header** SURFACE, square, 79 dp + status bar: ⋯ 15sp ExtraBold white @x15; ⌂ outline 17×15 @x42;
  logo 59×28 @x83; "Hello <name>" 10sp Regular + "1,234" 10sp Medium (TEXT) centred @x≈333; avatar 27.5 dp
  BG circle @x374. Subtitle 15sp Medium centred ("Currently Learning" / "German A2 , <lesson>").
- **Home**: course card 361×94 (radii 58 left / 25 right, SURFACE) with a 73 dp photo circle, "German A2"
  22.5sp Medium + subtitle 10sp Light, bar 216×3.75 (BG track, yellow), "x of y learnt" 10sp Light; 3 pill
  tiles 115×59 r29 (25sp Medium / 10sp Light, #D9D9D9); Classic / Fast Review squares 172×172 r29, 30sp Medium.
- **Session top block** (196 dp): egg 112 dp circle, centre-crop, 5 dp yellow ring outside, top 22.5;
  "+N" 15sp Medium over "Learned" pill 74×24 (SURFACE, 15sp Light) at left; megaphone 56 dp yellow circle
  right, top 55.6; "a/b" 15sp Medium right-aligned 26 dp from the right; bar 15 dp thick, 24 dp side margins,
  top 180.5, track #9B9797, fill yellow.
- **Prompt** 30sp Medium #F5F5F5 centred. **Options** full width, min 80 dp, r25, SURFACE, 22.5sp Medium,
  10 dp apart. **Continue** 338×37.5 r19 yellow, 22.5sp Medium #352542.
- **Arrange**: answer box SURFACE r25 min 187 dp; placed tiles #0DA2FF r12.5 h28 19sp; bank tiles KEY
  r12.5 h35 22.5sp; wand 37.5 dp at the end of the bank's first row.
- **Typing / Hearing**: input box SURFACE r25 (144 / 188 dp), 22.5sp Medium, hint "type here ..."; key row
  ü ö ß ä 47.5 dp squares r12.5, 10 dp apart. Keyboard up: top block hides; Typing prompt 25sp Regular at the
  top; Hearing megaphone top-right.
- **Answer screen**: top block without the bar; other-side line 20sp Light #D9D9D9; answer 30sp Medium;
  "your answer:" 15sp Light + answer 25sp Light #FE453E (wrong words); Continue.
- **Timed (Fast review, speed round)**: pie timer 122 dp (40 % light discs, wedge gradient #4B5CCA→#DEF4FF
  @80 %), megaphone right, small Continue 116×37.5 (skip), prompt, options 30sp; after answering the picked
  card turns #EB0C0C, the right one #07861A, Continue moves on.

## Tasks
1. Core: `Stats.correct_total` (imported Memrise correct + app counter saved in progress.json), test;
   rebuild `.so`, regenerate bindings, `cargo test`.
2. Assets: `ic_megaphone`, `ic_home_outline` vectors (Figma SVG), `course_photo.png`.
3. Header + ⋯ menu, remove the bottom bar; body padding 12 dp; new `pill()`.
4. Home.
5. Top block, choice cards, answer screen; delete statusStrip / chip / Nice-try / outlined boxes.
6. Timed layout + `PieTimer` (replaces `ProgressRing`).
7. Arrange, Typing, Hearing (keyboard up/down).
8. Build, copy the APK, owner checks each screen against the boards.

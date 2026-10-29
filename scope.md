# scope.md — termux-app fork (marx161-cmd/termux-app)

> Append-only. Never edit or delete an entry; add a new dated entry when a decision changes.
> Repo: `~/builds/android/termux_build/termux-app`. Builds on comrade only.

## 2026-10-29 — rebase onto upstream + kitty graphics v1

**Rebase (decided with the user):** take current upstream termux-app master as the base and
put our own patches back on top, instead of keeping our 2026-04-01 tree. Branch
`marx/upstream-rebase-2026-10`, base upstream `8629e632` (2026-09-26); the old line stays on
`marx/custom-terminal-stack` (last commit `95d53fd1`).
- Kept (ours): bundled fonts + `tools/termux-font-mode`, terminal stack/themes/colours,
  extra-keys changes, 64 KB terminal->process IO queue, LANGUAGE_SWITCH passthrough removed,
  `android.uid.system` sharedUserId + FGS specialUse / receiver flags / immutable PendingIntents,
  launcher icon, arm64-only ndk abiFilters.
- Dropped: our own Sixel + iTerm (OSC 1337) image code (written by us, 2026-04). Upstream
  shipped the same two protocols on 2026-09-12 (`3b270134`, `fd2ab4fc`), so we use theirs.

**Kitty graphics protocol v1 (decided with the user):** built on top of upstream's image stack:
APC `G` commands go into upstream's empty `doApc()`, images are decoded to an Android `Bitmap`
and handed to `TerminalBitmap.build(...)`, so scrolling, cleanup and drawing are shared with
Sixel/iTerm. New code goes in its own `TerminalKitty.java`.
- v1 includes: transmit / put / transmit+display (`a=t`, `a=p`, `a=T`); formats `f=24`, `f=32`,
  `f=100` (PNG); zlib `o=z`; chunked `m=1`; media direct `t=d` plus file / temp file
  `t=f` / `t=t`; image ids `i=`; cell sizing `c=` / `r=`; cursor movement policy `C=1`;
  query `a=q` with responses (so icat/chafa/yazi/timg detect support); basic delete `a=d`.

**TODO — explicitly deferred by the user, do not drop:**
- [ ] Animation frames (`a=f`, `a=a`, `a=c`).
- [ ] Images drawn behind text (negative `z=` index).
- [ ] Shared-memory transfer (`t=s`).
- [ ] tmux support via Unicode placeholders (`U=1`, U+10EEEE).

## 2026-10-29 (later) — Termux look: colours, backdrop, pets, extra-keys glitch (decided with the user)

**Kitty part 2** (the four deferred TODOs above) stays open, still wanted. Correction to my earlier claim: `t=s`
IS usable — Termux's `shm_open` stores segments as plain files in `$PREFIX/tmp/<name>` (verified on the Pixel),
same UID as the app, so `t=s` = open that name there, read, unlink. `z<0` shares the "behind the text" layer below.

**Colours** (user: "teal text, all tinted text is red, shades of each for distinct ones"; "yes force them"):
- Default foreground and the greys/whites (palette 0,7,8,15) = teal shades; every coloured ANSI entry (1-6, 9-14)
  = its own distinct red shade. Base colours = the comrade palette (teal #66FFE8, red #FF0062).
- FORCED for 256-colour and truecolor too (they bypass colors.properties): a remap in our renderer/colour code —
  greys -> teal at the same lightness, saturated -> red family, hue spread kept so colours stay distinct.

**Backdrop:** shader_105-like (the Pixel's `~/shaders/shader_105.glsl`, "chirp"/spark ridges) but much darker,
behind transparent terminal text. Needs full GLES (2 passes + `spark` feedback buffer), not AGSL -> reuse our
ShaderEditor fork's renderer rather than writing a second one. **No idle freeze** (user): it keeps pulsing to
audio (JDSP bands / beat). Runs only while Termux is on screen.

**Pets** (cell-exact: the terminal buffer knows the cursor and every empty row):
- Neko chases the cursor (run to it, sit, sleep when idle, startle on output bursts).
- A second ANeko skin walks through EMPTY rows only, never over text.
- Sprites via `shaderfx/tools/aneko_atlas.py` (438-skin catalog, canonical slot layout) so skins are swappable.
  Second skin: user to pick.

**Extra-keys row:** the status bar's beat glitch (`shaderfx/shaders/live/statusbar.agsl`, iEvent 2 row-band tears),
as AGSL RenderEffect on the real keys, fed by bands.bin + beat clock (SpectreBoard's BandsFeed/BeatSource pattern).

**Order:** colours first (fastest visible win), then backdrop, pets, extra-keys glitch; kitty part 2 alongside
(`z<0` with the backdrop layer).

## 2026-10-29 (later) — pets decided
- Neko chases the cursor. **Caza** (detailed TIE fighter, nqmgaming) **and MicroBot** both walk/fly through the
  empty rows, never over text. Gastly ruled out (no sleep/scratch frames). Sheets from `aneko_atlas.py`.

## 2026-10-29 (later) — extra-keys row restyled (user, live)
- Direct extension of SpectreBoard's toolbar: bg #0D0004, teal #66FFE8 text, active = red #FF0062 on #2A000F.
- Edge to edge: `terminal-margin-horizontal` now applies to the terminal (DrawerLayout) only.
- Thin (1 px) red border on top + sides with 45-degree cut top corners (`ChamferedRowDrawable`), no bottom edge;
  keys are transparent so the row background shows. The bottom-space strip takes the row colour (no black gap).

## 2026-10-30 — Termux backdrop rendered by ShaderEditor (BackdropService) — decided with the user
User: "shader_105 ... much darker as backdrop", "no freezing, let the chirp backdrop pulse to audio".
Architecture (mine, flagged to the user): ShaderEditor gains a bound `BackdropService`; Termux (same UID 1000)
binds it and passes a `Surface` from a `TextureView` behind the terminal text; ShaderEditor renders into it with
its normal `ShaderView` (same `getHolder()` override trick as the wallpaper engine), so multipass/`==spark==`
feedback, JDSP audio uniforms, FPS_CAP etc. come for free — no second renderer in Termux.
- Shader file `~/shaders/termux/backdrop.glsl` (Termux home), hot reload by mtime (<=1 s).
- Termux config `~/.termux/backdrop.properties`: `enabled`, `shader`, `dim` (TextureView alpha over black),
  `quality` (render scale). Runs only between Termux onStart/onStop; no idle freeze.

## 2026-10-30 — extra-keys beat glitch (user: "exactly like the status bar")
- `ExtraKeysShaderFx` mirrors shaderfx `StatusBarFx`: 100 ms beat watcher on BeatSource (cached song map, else
  comrade's `go` clock), each new beat = one 220 ms burst at full frame rate, effect removed between bursts.
  No JDSP gating / onset fallback (first version had them; dropped to match the status bar).
- Shader `~/shaders/termux/extrakeys.agsl` = statusbar.agsl's iEvent-2 path, chroma in comrade red/teal.

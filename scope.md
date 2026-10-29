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

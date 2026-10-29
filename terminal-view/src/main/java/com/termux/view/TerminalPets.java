package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fork addition (scope.md 2026-10-29): ANeko pets living in the terminal.
 *
 * - One cursor pet (neko) chases the cursor: runs to it, sits beside it, yawns, sleeps when
 *   nothing happens, startles on a burst of output.
 * - Row pets (Caza, MicroBot) walk/fly through bands of EMPTY rows only, turn at the walls, rest
 *   now and then, move to another empty band when text arrives and hide when there is none.
 *
 * Sprites come from `shaderfx/tools/aneko_atlas.py` sheets (canonical slot names, 8 columns,
 * square cells) and are drawn at their native size with no filtering. Everything is drawn in the
 * {@link TerminalView}'s overlay, so animating a pet never re-renders the terminal text.
 */
public final class TerminalPets {

    /** One packed ANeko sheet: bitmap + canonical slot name -> frame index. */
    public static final class Sheet {
        final Bitmap bitmap;
        final int cell;
        final int columns;
        final Map<String, Integer> slots;

        public Sheet(@NonNull Bitmap bitmap, int cell, int columns, @NonNull Map<String, Integer> slots) {
            this.bitmap = bitmap;
            this.cell = cell;
            this.columns = columns;
            this.slots = slots;
        }

        /** Frame index for `slot`, else for `fallback`, else frame 0. */
        int frame(String slot, String fallback) {
            Integer index = slots.get(slot);
            if (index == null) index = slots.get(fallback);
            return index == null ? 0 : index;
        }

        void src(int frame, Rect out) {
            int col = frame % columns, row = frame / columns;
            out.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell);
        }
    }

    private enum State { AWAKE, RUN, SIT, SCRATCH, YAWN, SLEEP, WALL, HIDDEN }

    private static final long FRAME_MS = 140;
    private static final long FAST_TICK_MS = 33;
    private static final long SLOW_TICK_MS = 250;

    private final class Pet {
        final Sheet sheet;
        final boolean chasesCursor;
        final float speed; // px / s
        State state = State.HIDDEN;
        long stateSince;
        float x = -1, y = -1;       // sprite top-left
        float targetX, targetY;
        boolean facingRight = true;
        int laneStart = -1;         // row pets: first view row of the band they walk in
        long nextRestAt;
        float alpha = 0f;

        Pet(Sheet sheet, boolean chasesCursor, float speed) {
            this.sheet = sheet;
            this.chasesCursor = chasesCursor;
            this.speed = speed;
        }

        void setState(State newState, long now) {
            if (state != newState) {
                state = newState;
                stateSince = now;
            }
        }

        int currentFrame(long now) {
            boolean odd = ((now - stateSince) / (state == State.SLEEP ? 500 : FRAME_MS)) % 2 == 1;
            switch (state) {
                case AWAKE: return sheet.frame("AWAKE", "SIT1");
                case RUN:
                    return facingRight ? sheet.frame(odd ? "RUN_R2" : "RUN_R1", "RUN_R1")
                        : sheet.frame(odd ? "RUN_L2" : "RUN_L1", "RUN_L1");
                case WALL:
                    return facingRight ? sheet.frame(odd ? "WALL_R2" : "WALL_R1", "RUN_R1")
                        : sheet.frame(odd ? "WALL_L2" : "WALL_L1", "RUN_L1");
                case SCRATCH: return sheet.frame(odd ? "SCRATCH2" : "SCRATCH1", "SIT1");
                case YAWN: return sheet.frame("YAWN2", "SIT1");
                case SLEEP: return sheet.frame(odd ? "SLEEP2" : "SLEEP1", "SIT1");
                case SIT:
                default: return sheet.frame("SIT1", "AWAKE");
            }
        }

        boolean isRestful() {
            return state == State.SLEEP || state == State.SIT || state == State.HIDDEN;
        }
    }

    private final TerminalView mView;
    private final List<Pet> mPets = new ArrayList<>();
    private final Random mRandom = new Random();
    private final Paint mPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();

    private boolean mActive;
    private boolean mTickScheduled;
    private long mLastTick;
    private long mLastActivityAt;
    private long mBurstWindowStart;
    private int mBurstUpdates;
    private int mLastCursorCol = -1, mLastCursorRow = Integer.MIN_VALUE;

    private final Drawable mOverlay = new Drawable() {
        @Override
        public void draw(@NonNull Canvas canvas) {
            drawPets(canvas);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    };

    private final Runnable mTick = this::tick;

    public TerminalPets(@NonNull TerminalView view, @Nullable Sheet cursorPet, @NonNull List<Sheet> rowPets) {
        mView = view;
        mPaint.setFilterBitmap(false);
        mPaint.setAntiAlias(false);
        if (cursorPet != null) mPets.add(new Pet(cursorPet, true, 520f));
        for (int i = 0; i < rowPets.size(); i++) {
            // Alternate speeds so two row pets in one band don't march in lockstep.
            mPets.add(new Pet(rowPets.get(i), false, i % 2 == 0 ? 150f : 95f));
        }
    }

    void attach() {
        mOverlay.setBounds(0, 0, mView.getWidth(), mView.getHeight());
        mView.getOverlay().add(mOverlay);
    }

    void detach() {
        setActive(false);
        mView.getOverlay().remove(mOverlay);
    }

    void onSizeChanged(int width, int height) {
        mOverlay.setBounds(0, 0, width, height);
    }

    /** Pause while the activity is not visible. */
    public void setActive(boolean active) {
        mActive = active;
        if (active) {
            mLastTick = SystemClock.uptimeMillis();
            scheduleTick(0);
        } else {
            mView.removeCallbacks(mTick);
            mTickScheduled = false;
        }
    }

    /** Called from {@link TerminalView#onScreenUpdated(boolean)} on new output. */
    void onScreenUpdated() {
        long now = SystemClock.uptimeMillis();
        mLastActivityAt = now;
        if (now - mBurstWindowStart > 500) {
            mBurstWindowStart = now;
            mBurstUpdates = 0;
        }
        if (++mBurstUpdates == 8) {
            // A burst of output: the cursor pet startles.
            for (Pet pet : mPets) {
                if (pet.chasesCursor && pet.state != State.HIDDEN) pet.setState(State.AWAKE, now);
            }
        }
        scheduleTick(0);
    }

    private void scheduleTick(long delay) {
        if (!mActive || mTickScheduled) return;
        mTickScheduled = true;
        mView.postOnAnimationDelayed(mTick, delay);
    }

    private void tick() {
        mTickScheduled = false;
        if (!mActive) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - mLastTick) / 1000f);
        mLastTick = now;

        TerminalEmulator emulator = mView.mEmulator;
        TerminalRenderer renderer = mView.mRenderer;
        if (emulator == null || renderer == null || mView.getWidth() == 0) {
            scheduleTick(SLOW_TICK_MS);
            return;
        }

        boolean anyMoving = false;
        boolean[] blank = blankViewRows(emulator, renderer);
        List<int[]> usedLanes = new ArrayList<>();
        for (Pet pet : mPets) {
            if (pet.chasesCursor) updateCursorPet(pet, emulator, renderer, now, dt);
            else updateRowPet(pet, renderer, blank, usedLanes, now, dt);
            anyMoving |= !pet.isRestful() || (pet.alpha > 0f && pet.alpha < 1f);
        }

        mOverlay.invalidateSelf();
        scheduleTick(anyMoving ? FAST_TICK_MS : SLOW_TICK_MS);
    }



    /* ---------------- geometry ---------------- */

    private int rowTop(TerminalRenderer renderer, int viewRow) {
        return renderer.mFontLineSpacingAndAscent + viewRow * renderer.mFontLineSpacing;
    }

    private int visibleRows(TerminalRenderer renderer) {
        return Math.max(0, (mView.getHeight() - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing);
    }

    /** Which visible rows hold nothing at all (no text, no image, not the cursor row). */
    private boolean[] blankViewRows(TerminalEmulator emulator, TerminalRenderer renderer) {
        int rows = Math.min(visibleRows(renderer), emulator.mRows);
        boolean[] blank = new boolean[rows];
        TerminalBuffer screen = emulator.getScreen();
        int cursorViewRow = emulator.getCursorRow() - mView.mTopRow;
        for (int i = 0; i < rows; i++) {
            if (i == cursorViewRow) continue;
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(mView.mTopRow + i));
            if (line.mHasTerminalBitmap) continue;
            boolean empty = true;
            char[] text = line.mText;
            int used = line.getSpaceUsed();
            for (int c = 0; c < used; c++) {
                if (text[c] != ' ' && text[c] != 0) {
                    empty = false;
                    break;
                }
            }
            blank[i] = empty;
        }
        return blank;
    }



    /* ---------------- cursor pet ---------------- */

    private void updateCursorPet(Pet pet, TerminalEmulator emulator, TerminalRenderer renderer, long now, float dt) {
        int size = pet.sheet.cell;
        int cursorViewRow = emulator.getCursorRow() - mView.mTopRow;
        int rows = visibleRows(renderer);
        int width = mView.getWidth();

        boolean cursorVisible = cursorViewRow >= 0 && cursorViewRow < rows;
        if (cursorVisible) {
            float cursorLeft = emulator.getCursorCol() * renderer.mFontWidth;
            float cursorRight = cursorLeft + renderer.mFontWidth;
            float tx = cursorRight + renderer.mFontWidth;
            if (tx + size > width) tx = cursorLeft - renderer.mFontWidth - size;
            pet.targetX = clamp(tx, 0, width - size);
            // Sit on the cursor row's baseline.
            pet.targetY = clamp(rowTop(renderer, cursorViewRow + 1) - size, 0, mView.getHeight() - size);

            int col = emulator.getCursorCol(), row = emulator.getCursorRow();
            if (col != mLastCursorCol || row != mLastCursorRow) {
                mLastCursorCol = col;
                mLastCursorRow = row;
                mLastActivityAt = now;
            }
        }

        if (pet.x < 0) {
            // First appearance: start where the cursor is, awake.
            pet.x = pet.targetX;
            pet.y = pet.targetY;
            pet.alpha = 1f;
            pet.setState(State.AWAKE, now);
        }

        float dx = pet.targetX - pet.x, dy = pet.targetY - pet.y;
        float dist = (float) Math.hypot(dx, dy);
        boolean far = dist > renderer.mFontWidth * 0.8f;

        switch (pet.state) {
            case AWAKE:
                if (now - pet.stateSince > 350) pet.setState(far ? State.RUN : State.SIT, now);
                break;
            case RUN:
                if (!far) {
                    pet.x = pet.targetX;
                    pet.y = pet.targetY;
                    pet.setState(State.SIT, now);
                } else {
                    float step = Math.min(dist, pet.speed * dt);
                    pet.x += dx / dist * step;
                    pet.y += dy / dist * step;
                    if (Math.abs(dx) > 1) pet.facingRight = dx > 0;
                }
                break;
            case SIT:
            case SCRATCH:
            case YAWN:
                if (far) {
                    pet.setState(State.AWAKE, now);
                } else if (pet.state == State.SIT && now - mLastActivityAt > 5000) {
                    pet.setState(State.YAWN, now);
                } else if (pet.state == State.YAWN && now - pet.stateSince > 700) {
                    pet.setState(State.SLEEP, now);
                } else if (pet.state == State.SIT && now - pet.stateSince > 2500 && mRandom.nextInt(40) == 0) {
                    pet.setState(State.SCRATCH, now);
                } else if (pet.state == State.SCRATCH && now - pet.stateSince > 1200) {
                    pet.setState(State.SIT, now);
                }
                break;
            case SLEEP:
                if (far || now - mLastActivityAt < 300) pet.setState(State.AWAKE, now);
                break;
            default:
                pet.setState(State.SIT, now);
        }
    }



    /* ---------------- row pets ---------------- */

    /** Bands of consecutive blank rows tall enough for `rowsNeeded`, as {start, length}. */
    private List<int[]> lanes(boolean[] blank, int rowsNeeded) {
        List<int[]> lanes = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= blank.length; i++) {
            boolean b = i < blank.length && blank[i];
            if (b && start < 0) start = i;
            if (!b && start >= 0) {
                // A tall band holds several lanes, stacked from its bottom up, so each row pet
                // can get its own instead of two pets overlapping in one.
                for (int laneStart = i - rowsNeeded; laneStart >= start; laneStart -= rowsNeeded) {
                    lanes.add(new int[]{laneStart, rowsNeeded});
                }
                start = -1;
            }
        }
        // Lowest lanes first: pets prefer the bottom of the screen, away from the prompt.
        java.util.Collections.sort(lanes, (l1, l2) -> l2[0] - l1[0]);
        return lanes;
    }

    private void updateRowPet(Pet pet, TerminalRenderer renderer, boolean[] blank, List<int[]> usedLanes, long now, float dt) {
        int size = pet.sheet.cell;
        int width = mView.getWidth();
        int rowsNeeded = (size + renderer.mFontLineSpacing - 1) / renderer.mFontLineSpacing;
        List<int[]> lanes = lanes(blank, rowsNeeded);

        // Is the current band still empty?
        boolean laneOk = false;
        if (pet.laneStart >= 0) {
            boolean takenByEarlierPet = false;
            for (int[] used : usedLanes) takenByEarlierPet |= used[0] == pet.laneStart;
            for (int[] lane : lanes) {
                if (!takenByEarlierPet && lane[0] == pet.laneStart) {
                    laneOk = true;
                    break;
                }
            }
        }

        if (!laneOk) {
            int[] best = null;
            float bestDistance = Float.MAX_VALUE;
            for (int[] lane : lanes) {
                boolean taken = false;
                for (int[] used : usedLanes) taken |= used[0] == lane[0];
                // Prefer a band no other row pet uses, then the nearest one.
                float distance = (taken ? 1e6f : 0f) + (pet.y < 0 ? 0 : Math.abs(rowTop(renderer, lane[0]) - pet.y));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = lane;
                }
            }
            if (best == null) {
                // No room anywhere: fade out and wait.
                pet.alpha = Math.max(0f, pet.alpha - dt * 4f);
                if (pet.alpha == 0f) {
                    pet.setState(State.HIDDEN, now);
                    pet.laneStart = -1;
                    pet.x = -1;
                }
                return;
            }
            pet.laneStart = best[0];
        }
        usedLanes.add(new int[]{pet.laneStart});

        float laneY = rowTop(renderer, pet.laneStart + rowsNeeded) - size;
        if (pet.x < 0) {
            // Enter from a random side.
            pet.facingRight = mRandom.nextBoolean();
            pet.x = pet.facingRight ? 0 : width - size;
            pet.y = laneY;
            pet.setState(State.RUN, now);
            pet.nextRestAt = now + 6000 + mRandom.nextInt(9000);
        }
        pet.alpha = Math.min(1f, pet.alpha + dt * 4f);

        // Changing bands: glide there first.
        if (Math.abs(pet.y - laneY) > 1) {
            float step = Math.min(Math.abs(laneY - pet.y), 320f * dt);
            pet.y += Math.signum(laneY - pet.y) * step;
            pet.setState(State.RUN, now);
            return;
        }
        pet.y = laneY;

        switch (pet.state) {
            case RUN: {
                pet.x += (pet.facingRight ? 1 : -1) * pet.speed * dt;
                if (pet.x <= 0 || pet.x >= width - size) {
                    pet.x = clamp(pet.x, 0, width - size);
                    pet.setState(State.WALL, now);
                } else if (now > pet.nextRestAt) {
                    int pick = mRandom.nextInt(3);
                    pet.setState(pick == 0 ? State.SLEEP : (pick == 1 ? State.SCRATCH : State.SIT), now);
                }
                break;
            }
            case WALL:
                if (now - pet.stateSince > 700) {
                    pet.facingRight = !pet.facingRight;
                    pet.setState(State.RUN, now);
                }
                break;
            case SIT:
            case SCRATCH:
            case SLEEP: {
                long restFor = pet.state == State.SLEEP ? 7000 : (pet.state == State.SCRATCH ? 1500 : 2500);
                if (now - pet.stateSince > restFor) {
                    pet.setState(State.AWAKE, now);
                }
                break;
            }
            case AWAKE:
                if (now - pet.stateSince > 400) {
                    pet.nextRestAt = now + 6000 + mRandom.nextInt(9000);
                    pet.setState(State.RUN, now);
                }
                break;
            default:
                pet.setState(State.RUN, now);
        }
    }



    /* ---------------- drawing ---------------- */

    private void drawPets(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        for (Pet pet : mPets) {
            if (pet.state == State.HIDDEN || pet.x < 0 || pet.alpha <= 0f) continue;
            pet.sheet.src(pet.currentFrame(now), mSrc);
            int size = pet.sheet.cell;
            int left = Math.round(pet.x), top = Math.round(pet.y);
            mDst.set(left, top, left + size, top + size);
            mPaint.setAlpha(Math.round(pet.alpha * 255));
            canvas.drawBitmap(pet.sheet.bitmap, mSrc, mDst, mPaint);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

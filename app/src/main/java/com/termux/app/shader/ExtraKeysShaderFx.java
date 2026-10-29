// SPDX-License-Identifier: GPL-3.0-only

package com.termux.app.shader;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * The API-33 implementation (scope.md 2026-10-30): exactly the status bar's beat bursts
 * (shaderfx StatusBarFx, iEvent 2) on the real extra-keys row (`uniform shader iContent`).
 *
 * A watcher polls the shared beat clock (BeatSource: cached beat map of the playing song, else the
 * desktop's `go` clock from comrade) every 100 ms while the row is shown. Every new beat fires one
 * 220 ms burst at full frame rate; `iEventTime` is the time since that beat, `iEventSeed` a fresh
 * random per burst. Between bursts the effect is removed and the row draws exactly stock.
 */
@RequiresApi(33)
final class ExtraKeysShaderFx implements Choreographer.FrameCallback {
    private static final long BURST_BEAT_MS = 220;
    private static final long BEAT_POLL_MS = 100;

    private final View fx;
    private final ShaderFile shader;
    private final BeatSource beat;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean beatWatch;
    private boolean running;
    private long burstUntil;
    private long eventAt = -1;
    private float eventSeed;
    private double lastBeatCount = -1;

    ExtraKeysShaderFx(View fx) {
        this.fx = fx;
        this.shader = new ShaderFile(ShaderPaths.dir(fx.getContext()),
                ShaderPaths.EXTRA_KEYS_FILE, "extrakeys_error.txt");
        this.beat = BeatSource.get(fx.getContext());
    }

    void attach() {
        if (attached) return;
        attached = true;
        handler.post(watch);
    }

    void detach() {
        attached = false;
        handler.removeCallbacks(watch);
        stopBurst();
        setBeatWatch(false);
    }

    private void setBeatWatch(boolean on) {
        if (on != beatWatch) {
            beat.setActive("extrakeys", on);
            beatWatch = on;
        }
    }

    /** Slow watcher: shown? shader present? new beat? (same as StatusBarFx.watch) */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            boolean shown = fx.isAttachedToWindow() && fx.isShown();
            boolean want = shown && shader.get() != null && shader.has("iContent");
            setBeatWatch(want);
            if (want) {
                BeatSource.Frame f = beat.frame();
                if (f.on) {
                    double c = Math.floor(f.count);
                    if (lastBeatCount >= 0 && c != lastBeatCount) event();
                    lastBeatCount = c;
                } else {
                    lastBeatCount = -1;
                }
            } else {
                lastBeatCount = -1;
                stopBurst();
            }
            handler.postDelayed(this, want ? BEAT_POLL_MS : 1000);
        }
    };

    private void event() {
        long now = SystemClock.elapsedRealtime();
        eventAt = now;
        eventSeed = (float) Math.random();
        burstUntil = Math.max(burstUntil, now + BURST_BEAT_MS);
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopBurst() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        try {
            fx.setRenderEffect(null);   // between bursts = exactly stock
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= burstUntil || !fx.isAttachedToWindow() || !fx.isShown()) {
            stopBurst();
            return;
        }
        RuntimeShader s = shader.get();
        if (s == null || !shader.has("iContent")) {
            stopBurst();
            return;
        }
        BeatSource.Frame f = beat.frame();
        shader.set("iResolution", fx.getWidth(), fx.getHeight());
        shader.set("iTime", ((now - ShaderFile.T0) % 3_600_000L) / 1000f);
        shader.set("iEventTime", eventAt < 0 ? 99f : (now - eventAt) / 1000f);
        shader.set("iEventSeed", eventSeed);
        shader.set("iBeatOn", f.on ? 1f : 0f);
        shader.set("iBeatPhase", (float) f.phase);
        try {
            fx.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "iContent"));
        } catch (Throwable t) {
            Log.w(ExtraKeysShader.TAG, "render effect failed", t);
            stopBurst();
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}

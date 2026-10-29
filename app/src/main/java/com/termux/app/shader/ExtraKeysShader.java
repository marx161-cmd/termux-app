// SPDX-License-Identifier: GPL-3.0-only

package com.termux.app.shader;

import android.os.Build;
import android.view.View;

/**
 * API-neutral entry point for the extra-keys row shader (scope.md 2026-10-30): the status bar's
 * beat glitch on the real keys. Everything API-33 lives in {@link ExtraKeysShaderFx}; on older
 * devices every call is a no-op and the row draws stock.
 */
public final class ExtraKeysShader {
    public static final String TAG = "ExtraKeysShader";

    private Object impl;

    public void attach(View row) {
        if (row == null || Build.VERSION.SDK_INT < 33) return;
        try {
            detach();
            ExtraKeysShaderFx fx = new ExtraKeysShaderFx(row);
            fx.attach();
            impl = fx;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attach failed", t);
            impl = null;
        }
    }

    public void detach() {
        Object o = impl;
        impl = null;
        if (o == null) return;
        try {
            ((ExtraKeysShaderFx) o).detach();
        } catch (Throwable ignored) {
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Ported from SpectreBoard (com.termux.spectreboard.shader) for the Termux extra-keys row, scope.md 2026-10-30.

package com.termux.app.shader;

import android.content.Context;

import java.io.File;

/** Where Termux's AGSL surface files live: Termux home, edited and hot-reloaded from a terminal. */
public final class ShaderPaths {
    public static final File TERMUX_DIR =
            new File("/data/data/com.termux/files/home/shaders/termux");
    /** Beat maps synced by beatmaps-sync-pixel for qs-shader-live; read-only here. */
    public static final File QS_DIR = new File("/data/local/tmp/qs_shader");

    public static final String EXTRA_KEYS_FILE = "extrakeys.agsl";

    private ShaderPaths() {
    }

    public static File dir(Context ctx) {
        if (TERMUX_DIR.isDirectory() && TERMUX_DIR.canRead()) {
            return TERMUX_DIR;
        }
        File f = new File(ctx.getApplicationContext().getFilesDir(), "shaders");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static File status(Context ctx, String name) {
        return new File(dir(ctx), name);
    }
}

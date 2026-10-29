package com.termux.app.terminal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Fork addition (scope.md 2026-10-30): a live shader backdrop behind the terminal text.
 *
 * A {@link TextureView} sits under the terminal; its {@link Surface} is handed to ShaderEditor's
 * `BackdropService` (same shared UID), which renders a shader file into it with ShaderEditor's full
 * renderer. The view is drawn semi-transparent over the black window, which is how it is kept dark.
 *
 * Config `~/.termux/backdrop.properties`:
 * `enabled` (default true when the file exists), `shader` (default ~/shaders/termux/backdrop.glsl),
 * `dim` (view alpha 0..1, default 0.35), `quality` (render scale, default 0.5).
 * Rendering only runs between {@link #onStart()} and {@link #onStop()}.
 */
public final class TermuxBackdrop implements TextureView.SurfaceTextureListener {

    private static final String LOG_TAG = "TermuxBackdrop";
    private static final ComponentName SERVICE = new ComponentName(
        "com.termux.shadereditor", "com.termux.shadereditor.service.BackdropService");
    private static final int MSG_ATTACH = 1, MSG_RESIZE = 2, MSG_DETACH = 3;

    private final TermuxActivity mActivity;
    private TextureView mTextureView;
    private Surface mSurface;
    private int mWidth, mHeight;
    private Messenger mService;
    private boolean mStarted;
    private boolean mBound;

    private boolean mEnabled;
    private String mShaderPath;
    private float mDim = 0.35f;
    private float mQuality = 0.5f;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = new Messenger(binder);
            sendAttach();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // ShaderEditor died; the binding stays and reconnects when it comes back.
            mService = null;
        }
    };

    public TermuxBackdrop(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /** (Re)read the config and apply it. Safe to call on every styling reload. */
    public void reload() {
        File config = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "backdrop.properties");
        boolean enabled = false;
        String shader = TermuxConstants.TERMUX_HOME_DIR_PATH + "/shaders/termux/backdrop.glsl";
        float dim = 0.35f, quality = 0.5f;
        if (config.isFile()) {
            Properties props = new Properties();
            try (InputStream in = new FileInputStream(config)) {
                props.load(in);
                enabled = !"false".equals(props.getProperty("enabled", "true").trim());
                shader = props.getProperty("shader", shader).trim().replaceFirst("^~", TermuxConstants.TERMUX_HOME_DIR_PATH);
                dim = clamp(Float.parseFloat(props.getProperty("dim", "0.35").trim()), 0f, 1f);
                quality = clamp(Float.parseFloat(props.getProperty("quality", "0.5").trim()), 0.1f, 1f);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error reading " + config, e);
                enabled = false;
            }
        }

        boolean shaderChanged = !shader.equals(mShaderPath) || quality != mQuality;
        mEnabled = enabled;
        mShaderPath = shader;
        mDim = dim;
        mQuality = quality;

        if (mEnabled) {
            ensureView();
            mTextureView.setAlpha(mDim);
            if (mStarted) {
                bind();
                if (shaderChanged) sendAttach();
            }
        } else {
            sendDetach();
            unbind();
            removeView();
        }
    }

    public void onStart() {
        mStarted = true;
        if (mEnabled) {
            ensureView();
            bind();
            sendAttach();
        }
    }

    public void onStop() {
        mStarted = false;
        sendDetach();
        unbind();
    }

    private void ensureView() {
        if (mTextureView != null) return;
        RelativeLayout parent = mActivity.findViewById(com.termux.R.id.activity_termux_root_relative_layout);
        mTextureView = new TextureView(mActivity);
        mTextureView.setOpaque(false);
        mTextureView.setSurfaceTextureListener(this);
        // Index 0: drawn first, i.e. behind the terminal, the drawer and the extra-keys row.
        parent.addView(mTextureView, 0, new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void removeView() {
        if (mTextureView == null) return;
        ((ViewGroup) mTextureView.getParent()).removeView(mTextureView);
        mTextureView = null;
    }

    private void bind() {
        if (mBound) return;
        try {
            mBound = mActivity.bindService(new Intent().setComponent(SERVICE), mConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Cannot bind ShaderEditor's BackdropService", e);
            mBound = false;
        }
    }

    private void unbind() {
        if (!mBound) return;
        try {
            mActivity.unbindService(mConnection);
        } catch (Exception ignored) {
        }
        mBound = false;
        mService = null;
    }

    private void sendAttach() {
        if (!mStarted || !mEnabled || mService == null || mSurface == null) return;
        Bundle data = new Bundle();
        data.putParcelable("surface", mSurface);
        data.putInt("width", mWidth);
        data.putInt("height", mHeight);
        data.putString("shader", mShaderPath);
        data.putFloat("quality", mQuality);
        send(MSG_ATTACH, data);
    }

    private void sendDetach() {
        send(MSG_DETACH, new Bundle());
    }

    private void send(int what, Bundle data) {
        if (mService == null) return;
        Message msg = Message.obtain(null, what);
        msg.setData(data);
        try {
            mService.send(msg);
        } catch (RemoteException e) {
            mService = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
        mSurface = new Surface(texture);
        mWidth = width;
        mHeight = height;
        sendAttach();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
        mWidth = width;
        mHeight = height;
        Bundle data = new Bundle();
        data.putInt("width", width);
        data.putInt("height", height);
        send(MSG_RESIZE, data);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        sendDetach();
        if (mSurface != null) mSurface.release();
        mSurface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

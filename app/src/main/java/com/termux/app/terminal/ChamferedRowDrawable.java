package com.termux.app.terminal;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork addition (scope.md 2026-10-29): background of the extra-keys row. Filled like SpectreBoard's
 * toolbar, top corners cut at 45 degrees, and a thin border along the sides, the cuts and the top.
 * No bottom edge, so the row reads as an extension of the keyboard below it.
 */
public final class ChamferedRowDrawable extends Drawable {

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mCut;
    private final Path mFillPath = new Path();
    private final Path mStrokePath = new Path();

    public ChamferedRowDrawable(int fillColor, int borderColor, float borderWidthPx, float cutPx) {
        mFill.setColor(fillColor);
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setColor(borderColor);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(borderWidthPx);
        mCut = cutPx;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        float half = mStroke.getStrokeWidth() / 2f;
        float l = bounds.left + half, t = bounds.top + half, r = bounds.right - half, b = bounds.bottom;
        float cut = Math.min(mCut, Math.min(bounds.width(), bounds.height()) / 2f);

        mStrokePath.reset();
        mStrokePath.moveTo(l, b);
        mStrokePath.lineTo(l, t + cut);
        mStrokePath.lineTo(l + cut, t);
        mStrokePath.lineTo(r - cut, t);
        mStrokePath.lineTo(r, t + cut);
        mStrokePath.lineTo(r, b);

        mFillPath.reset();
        mFillPath.addPath(mStrokePath);
        mFillPath.close();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawPath(mFillPath, mFill);
        canvas.drawPath(mStrokePath, mStroke);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

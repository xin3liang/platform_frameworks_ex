/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.rastermill;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

public class FrameSequenceDrawable extends Drawable implements Animatable, Runnable {
    private static final Object sLock = new Object();
    private static HandlerThread sDecodingThread;
    private static Handler sDecodingThreadHandler;
    private static void initializeDecodingThread() {
        synchronized (sLock) {
            if (sDecodingThread != null) return;

            sDecodingThread = new HandlerThread("FrameSequence decoding thread");
            sDecodingThread.start();
            sDecodingThreadHandler = new Handler(sDecodingThread.getLooper());
        }
    }

    public static interface OnFinishedListener {
        /**
         * Called when a FrameSequenceDrawable has finished looping.
         *
         * Note that this is will not be called if the drawable is explicitly
         * stopped, or marked invisible.
         */
        public abstract void onFinished(FrameSequenceDrawable drawable);
    }

    /**
     * Register a callback to be invoked when a FrameSequenceDrawable finishes looping.
     *
     * @see setLoopBehavior()
     */
    public void setOnFinishedListener(OnFinishedListener onFinishedListener) {
        mOnFinishedListener = onFinishedListener;
    }

    /**
     * Loop only once.
     */
    public static final int LOOP_ONCE = 1;

    /**
     * Loop continuously. The OnFinishedListener will never be called.
     */
    public static final int LOOP_INF = 2;

    /**
     * Use loop count stored in source data, or LOOP_ONCE if not present.
     */
    public static final int LOOP_DEFAULT = 3;

    /**
     * Define looping behavior of frame sequence.
     *
     * Must be one of LOOP_ONCE, LOOP_INF, or LOOP_DEFAULT
     */
    public void setLoopBehavior(int loopBehavior) {
        mLoopBehavior = loopBehavior;
    }

    private final FrameSequence mFrameSequence;
    private final FrameSequence.State mFrameSequenceState;

    private final Paint mPaint;
    private final Rect mSrcRect;

    //Protects the fields below
    private final Object mLock = new Object();

    private Bitmap mFrontBitmap;
    private Bitmap mBackBitmap;

    private static final int STATE_SCHEDULED = 1;
    private static final int STATE_DECODING = 2;
    private static final int STATE_WAITING_TO_SWAP = 3;
    private static final int STATE_READY_TO_SWAP = 4;

    private int mState;
    private int mCurrentLoop;
    private int mLoopBehavior = LOOP_DEFAULT;

    private long mLastSwap;
    private int mNextFrameToDecode;
    private OnFinishedListener mOnFinishedListener;

    /**
     * Runs on decoding thread, only modifies mBackBitmap's pixels
     */
    private Runnable mDecodeRunnable = new Runnable() {
        @Override
        public void run() {
            int nextFrame;
            Bitmap bitmap;
            synchronized (mLock) {
                nextFrame = mNextFrameToDecode;
                if (nextFrame < 0) {
                    return;
                }
                bitmap = mBackBitmap;
                mState = STATE_DECODING;
            }
            int lastFrame = nextFrame - 2;
            long invalidateTimeMs = mFrameSequenceState.getFrame(nextFrame, bitmap, lastFrame);

            synchronized (mLock) {
                if (mNextFrameToDecode < 0 || mState != STATE_DECODING) return;
                invalidateTimeMs += mLastSwap;

                mState = STATE_WAITING_TO_SWAP;
            }
            scheduleSelf(FrameSequenceDrawable.this, invalidateTimeMs);
        }
    };

    private Runnable mCallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (mOnFinishedListener != null) {
                mOnFinishedListener.onFinished(FrameSequenceDrawable.this);
            }
        }
    };

    public FrameSequenceDrawable(FrameSequence frameSequence) {
        if (frameSequence == null) throw new IllegalArgumentException();

        mFrameSequence = frameSequence;
        mFrameSequenceState = frameSequence.createState();
        // TODO: add callback for requesting bitmaps, to allow for reuse
        final int width = frameSequence.getWidth();
        final int height = frameSequence.getHeight();

        mFrontBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mBackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mSrcRect = new Rect(0, 0, width, height);
        mPaint = new Paint();
        mPaint.setFilterBitmap(true);

        mLastSwap = 0;

        mNextFrameToDecode = -1;
        mFrameSequenceState.getFrame(0, mFrontBitmap, -1);
        initializeDecodingThread();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mFrontBitmap.recycle();
            mBackBitmap.recycle();
            mFrameSequenceState.recycle();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        synchronized (mLock) {
            if (isRunning() && mState == STATE_READY_TO_SWAP) {
                // Because draw has occurred, the view system is guaranteed to no longer hold a
                // reference to the old mFrontBitmap, so we now use it to produce the next frame
                Bitmap tmp = mBackBitmap;
                mBackBitmap = mFrontBitmap;
                mFrontBitmap = tmp;

                mLastSwap = SystemClock.uptimeMillis();

                boolean continueLooping = true;
                if (mNextFrameToDecode == mFrameSequence.getFrameCount() - 1) {
                    mCurrentLoop++;
                    if ((mLoopBehavior == LOOP_ONCE && mCurrentLoop == 1) ||
                        (mLoopBehavior == LOOP_DEFAULT && mCurrentLoop == mFrameSequence.getDefaultLoopCount())) {
                        continueLooping = false;
                    }
                }

                if (continueLooping) {
                    scheduleDecodeLocked();
                } else {
                    scheduleSelf(mCallbackRunnable, 0);
                }
            }
        }

        canvas.drawBitmap(mFrontBitmap, mSrcRect, getBounds(), mPaint);
    }

    private void scheduleDecodeLocked() {
        mState = STATE_SCHEDULED;
        mNextFrameToDecode = (mNextFrameToDecode + 1) % mFrameSequence.getFrameCount();
        sDecodingThreadHandler.post(mDecodeRunnable);
    }

    @Override
    public void run() {
        // set ready to swap
        synchronized (mLock) {
            if (mState != STATE_WAITING_TO_SWAP || mNextFrameToDecode < 0) return;
            mState = STATE_READY_TO_SWAP;
        }
        invalidateSelf();
    }

    @Override
    public void start() {
        if (!isRunning()) {
            synchronized (mLock) {
                if (mState == STATE_SCHEDULED) return; // already scheduled
                mCurrentLoop = 0;
                scheduleDecodeLocked();
            }
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            unscheduleSelf(this);
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (mLock) {
            return mNextFrameToDecode > -1;
        }
    }

    @Override
    public void scheduleSelf(Runnable what, long when) {
        super.scheduleSelf(what, when);
    }

    @Override
    public void unscheduleSelf(Runnable what) {
        synchronized (mLock) {
            mNextFrameToDecode = -1;
        }
        super.unscheduleSelf(what);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);

        if (!visible) {
            stop();
        } else if (restart || changed) {
            stop();
            start();
        }

        return changed;
    }

    // drawing properties

    @Override
    public void setFilterBitmap(boolean filter) {
        mPaint.setFilterBitmap(filter);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getIntrinsicWidth() {
        return mFrameSequence.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mFrameSequence.getHeight();
    }

    @Override
    public int getOpacity() {
        return mFrameSequence.isOpaque() ? PixelFormat.OPAQUE : PixelFormat.TRANSPARENT;
    }
}

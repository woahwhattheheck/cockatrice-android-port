package com.wrapper.cockatrice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Native canvas and input only: the pixels and interface remain Cockatrice's. */
final class NativeRfbView extends View {
    interface Events { void firstFrame(); void failure(String message); }
    private final Object frameLock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private NativeRfbClient client;
    private Bitmap framebuffer;
    private boolean drewFrame;
    private boolean leftDown;
    private boolean multiTouch;
    private float zoom = 1;
    private float panX, panY, lastFocusX, lastFocusY;
    private int lastX, lastY;

    NativeRfbView(Context context, int port, String password, Events events) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(1, Math.min(4, zoom * detector.getScaleFactor()));
                if (zoom == 1) { panX = 0; panY = 0; }
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public void onLongPress(MotionEvent event) {
                if (!multiTouch && client != null) {
                    client.pointer(lastX, lastY, 0);
                    client.pointer(lastX, lastY, 4);
                    client.pointer(lastX, lastY, 0);
                    leftDown = false;
                }
            }
        });
        client = new NativeRfbClient(port, password, new NativeRfbClient.Listener() {
            @Override public void connected(int width, int height) { }
            @Override public void frame(int[] pixels, int width, int height) {
                synchronized (frameLock) {
                    if (framebuffer == null || framebuffer.getWidth() != width || framebuffer.getHeight() != height) {
                        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    }
                    framebuffer.setPixels(pixels, 0, width, 0, 0, width, height);
                }
                postInvalidateOnAnimation();
                if (!drewFrame) { drewFrame = true; post(events::firstFrame); }
            }
            @Override public void clipboard(String text) { /* Explicit Android clipboard reads happen only on Paste. */ }
            @Override public void failed(String message) { post(() -> events.failure(message)); }
        });
    }

    void start() { client.start(); }
    void close() { if (client != null) client.close(); }
    void paste(String text) { if (client != null) client.paste(text); }
    void tapKey(int key) { if (client != null) client.tapKey(key); }
    void fit() { zoom = 1; panX = panY = 0; invalidate(); }

    private RectF destination() {
        if (framebuffer == null) return new RectF();
        float scale = Math.min((float) getWidth() / framebuffer.getWidth(), (float) getHeight() / framebuffer.getHeight()) * zoom;
        float w = framebuffer.getWidth() * scale, h = framebuffer.getHeight() * scale;
        return new RectF((getWidth() - w) / 2 + panX, (getHeight() - h) / 2 + panY,
                (getWidth() + w) / 2 + panX, (getHeight() + h) / 2 + panY);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff10151c);
        synchronized (frameLock) { if (framebuffer != null) canvas.drawBitmap(framebuffer, null, destination(), paint); }
    }

    private boolean point(float x, float y) {
        synchronized (frameLock) {
            if (framebuffer == null) return false;
            RectF area = destination();
            if (area.width() <= 0 || area.height() <= 0) return false;
            lastX = Math.max(0, Math.min(framebuffer.getWidth() - 1, Math.round((x - area.left) * framebuffer.getWidth() / area.width())));
            lastY = Math.max(0, Math.min(framebuffer.getHeight() - 1, Math.round((y - area.top) * framebuffer.getHeight() / area.height())));
            return true;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        requestFocus();
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        int action = event.getActionMasked();
        if (event.getPointerCount() > 1) {
            if (leftDown) { client.pointer(lastX, lastY, 0); leftDown = false; }
            float x = (event.getX(0) + event.getX(1)) / 2, y = (event.getY(0) + event.getY(1)) / 2;
            if (multiTouch && action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
                if (zoom > 1) { panX += x - lastFocusX; panY += y - lastFocusY; invalidate(); }
                else if (Math.abs(y - lastFocusY) > 24 && point(x, y)) {
                    client.pointer(lastX, lastY, y > lastFocusY ? 16 : 8);
                    client.pointer(lastX, lastY, 0);
                }
            }
            multiTouch = true;
            lastFocusX = x; lastFocusY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            if (point(event.getX(), event.getY())) { leftDown = true; client.pointer(lastX, lastY, 1); }
        } else if (action == MotionEvent.ACTION_MOVE && !multiTouch && leftDown) {
            if (point(event.getX(), event.getY())) client.pointer(lastX, lastY, 1);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (leftDown) { point(event.getX(), event.getY()); client.pointer(lastX, lastY, 0); }
            leftDown = false;
            multiTouch = false;
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private final Editable editable = new SpannableStringBuilder();
            @Override public Editable getEditable() { return editable; }
            @Override public boolean commitText(CharSequence text, int cursor) {
                String value = text.toString();
                for (int offset = 0; offset < value.length();) {
                    int code = value.codePointAt(offset);
                    offset += Character.charCount(code);
                    int key = code == '\n' ? 0xff0d : code == '\t' ? 0xff09 : code > 255 ? 0x01000000 | code : code;
                    client.tapKey(key);
                }
                editable.clear();
                return true;
            }
            @Override public boolean setComposingText(CharSequence text, int cursor) {
                editable.replace(0, editable.length(), text);
                return true;
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < Math.min(before, 256); i++) client.tapKey(0xff08);
                for (int i = 0; i < Math.min(after, 256); i++) client.tapKey(0xffff);
                editable.clear();
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return dispatchKeyEvent(event); }
        };
    }

    private int keysym(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xffe1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xffe3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xffe4;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xffe9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xffea;
            default:
                if (event.getKeyCode() >= KeyEvent.KEYCODE_F1 && event.getKeyCode() <= KeyEvent.KEYCODE_F12) return 0xffbe + event.getKeyCode() - KeyEvent.KEYCODE_F1;
                int value = event.getUnicodeChar(event.getMetaState() & ~(KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON));
                return value > 255 && value <= 0x10ffff ? 0x01000000 | value : value;
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        int key = keysym(event);
        if (key != 0 && client != null) { client.key(key, true); return true; }
        return super.onKeyDown(code, event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        int key = keysym(event);
        if (key != 0 && client != null) { client.key(key, false); return true; }
        return super.onKeyUp(code, event);
    }
}

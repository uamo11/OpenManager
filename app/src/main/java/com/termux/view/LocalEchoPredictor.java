package com.termux.view;

import android.os.SystemClock;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;

/**
 * Mosh-style predictive local echo for remote sessions.
 * <p>
 * Printable keys are drawn at the cursor immediately, before the server echoes them. Predictions are
 * verified against the real screen when output arrives: confirmed ones disappear (the server's echo
 * is now on screen), a mismatch drops all of them.
 * <p>
 * Safety: predictions are grouped in "epochs" that end on Enter, control keys, cursor keys or any
 * mismatch. A new epoch is never displayed until the server has echoed at least one of its keys,
 * so prompts that do not echo (passwords) never show typed characters.
 */
final class LocalEchoPredictor {

    private static final class Prediction {
        final int row;
        final int col;
        final int codePoint;
        final long typedAtMs;

        Prediction(int row, int col, int codePoint, long typedAtMs) {
            this.row = row;
            this.col = col;
            this.codePoint = codePoint;
            this.typedAtMs = typedAtMs;
        }
    }

    /** Below this round trip the server echo is already instant; drawing predictions is pointless. */
    private static final long MIN_USEFUL_RTT_MS = 20;
    private static final long MIN_STALE_MS = 1000;
    private static final int MAX_PENDING = 96;

    /** The few screen facts the predictor needs; {@link #of(TerminalEmulator)} adapts the emulator. */
    interface Screen {
        int rows();
        int columns();
        int cursorRow();
        int cursorCol();
        boolean cursorEnabled();
        /** Code point drawn at a cell, or -1. */
        int charAt(int row, int col);
    }

    static Screen of(TerminalEmulator emulator) {
        return new Screen() {
            @Override public int rows() { return emulator.mRows; }
            @Override public int columns() { return emulator.mColumns; }
            @Override public int cursorRow() { return emulator.getCursorRow(); }
            @Override public int cursorCol() { return emulator.getCursorCol(); }
            @Override public boolean cursorEnabled() { return emulator.isCursorEnabled(); }
            @Override public int charAt(int row, int col) { return LocalEchoPredictor.charAt(emulator.getScreen(), emulator, row, col); }
        };
    }

    /** Time source; replaceable so the logic can be unit tested off-device. */
    interface Clock {
        long nowMs();
    }

    Clock mClock = SystemClock::uptimeMillis;

    private final ArrayList<Prediction> mPending = new ArrayList<>();
    private boolean mEpochConfirmed;
    /** Smoothed round trip of confirmed keys, -1 until measured. */
    private long mSrttMs = -1;
    private boolean mEnabled;
    /** Keys that were drawn ahead of the server and then confirmed by its echo. */
    private int mShownConfirmed;

    void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) resetEpoch();
    }

    boolean isEnabled() {
        return mEnabled;
    }

    long getSmoothedRttMs() {
        return mSrttMs;
    }

    /** A printable key was sent to the server. */
    void onPrintable(TerminalEmulator emulator, int codePoint) {
        if (!mEnabled || emulator == null) return;
        onPrintable(of(emulator), codePoint);
    }

    void onPrintable(Screen screen, int codePoint) {
        if (!mEnabled || screen == null) return;
        if (WcWidth.width(codePoint) != 1 || !screen.cursorEnabled()) {
            // Wide glyphs and apps that hide the cursor (drawing their own) are not predictable.
            resetEpoch();
            return;
        }
        int row;
        int col;
        if (mPending.isEmpty()) {
            row = screen.cursorRow();
            col = screen.cursorCol();
        } else {
            Prediction last = mPending.get(mPending.size() - 1);
            row = last.row;
            col = last.col + 1;
        }
        // Never predict across the right margin: wrapping depends on modes we cannot see.
        if (col >= screen.columns() - 1 || mPending.size() >= MAX_PENDING) {
            resetEpoch();
            return;
        }
        mPending.add(new Prediction(row, col, codePoint, mClock.nowMs()));
    }

    /** Backspace: un-predict the last pending key if any, the server erases the rest itself. */
    void onBackspace() {
        if (!mPending.isEmpty()) mPending.remove(mPending.size() - 1);
    }

    /** Enter, Tab, Esc, arrows, Ctrl/Alt combos...: the next echo is unpredictable. */
    void resetEpoch() {
        mPending.clear();
        mEpochConfirmed = false;
    }

    /** Called after new output was applied to the emulator. Returns true if the overlay changed. */
    boolean onScreenUpdated(TerminalEmulator emulator) {
        if (mPending.isEmpty() || emulator == null) return false;
        return onScreenUpdated(of(emulator));
    }

    boolean onScreenUpdated(Screen screen) {
        if (mPending.isEmpty() || screen == null) return false;
        long now = mClock.nowMs();
        boolean changed = false;
        while (!mPending.isEmpty()) {
            Prediction first = mPending.get(0);
            if (screen.charAt(first.row, first.col) != first.codePoint) break;
            boolean wasShown = shouldDisplay();
            mPending.remove(0);
            changed = true;
            if (wasShown) mShownConfirmed++;
            long sample = now - first.typedAtMs;
            mSrttMs = mSrttMs < 0 ? sample : (mSrttMs * 7 + sample) / 8;
            mEpochConfirmed = true;
        }
        if (!mPending.isEmpty()) {
            Prediction first = mPending.get(0);
            long staleAfter = Math.max(MIN_STALE_MS, mSrttMs * 3);
            boolean cursorLeftLine = screen.cursorRow() != first.row;
            boolean cursorPassedIt = screen.cursorRow() == first.row && screen.cursorCol() > first.col;
            if (now - first.typedAtMs > staleAfter || cursorLeftLine || cursorPassedIt) {
                // The application did something else with the key (or never echoes): give up.
                resetEpoch();
                changed = true;
            }
        }
        return changed;
    }

    /** Whether pending predictions should be drawn right now. */
    boolean shouldDisplay() {
        return mEnabled && mEpochConfirmed && !mPending.isEmpty() && mSrttMs >= MIN_USEFUL_RTT_MS;
    }

    int getShownConfirmedCount() {
        return mShownConfirmed;
    }

    int getPendingCount() {
        return mPending.size();
    }

    int getRow(int index) {
        return mPending.get(index).row;
    }

    int getCol(int index) {
        return mPending.get(index).col;
    }

    int getCodePoint(int index) {
        return mPending.get(index).codePoint;
    }

    private static int charAt(TerminalBuffer screen, TerminalEmulator emulator, int row, int col) {
        if (row < 0 || row >= emulator.mRows || col < 0 || col >= emulator.mColumns) return -1;
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        int start = line.findStartOfColumn(col);
        if (start < 0 || start >= line.getSpaceUsed()) return -1;
        char c = line.mText[start];
        if (Character.isHighSurrogate(c) && start + 1 < line.getSpaceUsed()) {
            return Character.toCodePoint(c, line.mText[start + 1]);
        }
        return c;
    }
}

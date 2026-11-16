package app.judgelamp;

import java.nio.ByteBuffer;

/**
 * Tells an early hit from a late one.
 *
 * A note that is hit late has already travelled past its judgment line when it
 * disappears; a note that is hit early disappears before reaching the line. So
 * on the frame where GREAT / GOOD appears we ask: did note-coloured pixels just
 * vanish from the strips beyond the three judgment lines (below the bottom line,
 * outside the left and right lines)? If yes the hit was late, otherwise early.
 *
 * Pure Java, tested on recordings with tools/DetectorHarness.java.
 */
public final class TimingProbe {

    /** A note-sized drop: this many pixels gone from one strip between two frames. */
    private static final int DROP = 100;

    // strips in canonical coordinates (frame height 540), x relative to the centre
    private static final int B_X0 = -406, B_X1 = 406, B_Y0 = 492, B_Y1 = 532;
    private static final int S_Y0 = 60, S_Y1 = 450;   // clear of the fixed frame corners
    private static final int L_X0 = -456, L_X1 = -422, R_X0 = 422, R_X1 = 456;

    private final int[][] history = new int[3][3]; // [age 0 = newest][strip]
    private byte[] row = new byte[0];

    /** Call once per frame, before {@link #wasLate}. */
    public void observe(ByteBuffer rgba, int width, int height, int rowStride, int pixelStride) {
        int[] oldest = history[2];
        history[2] = history[1];
        history[1] = history[0];
        history[0] = oldest;
        oldest[0] = oldest[1] = oldest[2] = 0;
        if (height != JudgeDetector.CANON_HEIGHT || pixelStride < 3) return;
        int cx = width / 2;
        if (cx + L_X0 < 0 || cx + R_X1 > width) return;
        ByteBuffer b = rgba.duplicate();
        b.clear();
        oldest[0] = count(b, cx + B_X0, cx + B_X1, B_Y0, B_Y1, rowStride, pixelStride);
        oldest[1] = count(b, cx + L_X0, cx + L_X1, S_Y0, S_Y1, rowStride, pixelStride);
        oldest[2] = count(b, cx + R_X0, cx + R_X1, S_Y0, S_Y1, rowStride, pixelStride);
    }

    /**
     * @param framesAgo 0 if the judgment word appeared on the newest frame, 1 if it
     *                  appeared one frame earlier (word confirmed over two frames)
     */
    public boolean wasLate(int framesAgo) {
        int[] after = history[framesAgo], before = history[framesAgo + 1];
        for (int s = 0; s < 3; s++) {
            if (before[s] - after[s] >= DROP) return true;
        }
        return false;
    }

    public int[] newest() {
        return history[0];
    }

    private int count(ByteBuffer b, int x0, int x1, int y0, int y1, int rowStride, int pixelStride) {
        int w = x1 - x0;
        int len = (w - 1) * pixelStride + 3;
        if (row.length < len) row = new byte[len];
        if ((y1 - 1) * rowStride + x0 * pixelStride + len > b.capacity()) return 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            b.position(y * rowStride + x0 * pixelStride);
            b.get(row, 0, len);
            for (int x = 0, p = 0; x < w; x++, p += pixelStride) {
                if (isNoteColour(row[p] & 0xFF, row[p + 1] & 0xFF, row[p + 2] & 0xFF)) n++;
            }
        }
        return n;
    }

    /** Bright, saturated cyan / green (tap and slide notes) or red / orange (hold notes). */
    static boolean isNoteColour(int r, int g, int bl) {
        int mx = Math.max(r, Math.max(g, bl));
        int mn = Math.min(r, Math.min(g, bl));
        int d = mx - mn;
        if (mx <= 140 || d * 255 <= 110 * mx) return false;
        float hue; // degrees
        if (mx == r) hue = 60f * (g - bl) / d;
        else if (mx == g) hue = 120f + 60f * (bl - r) / d;
        else hue = 240f + 60f * (r - g) / d;
        return (hue >= 110f && hue <= 200f) || (hue >= 0f && hue <= 36f);
    }
}

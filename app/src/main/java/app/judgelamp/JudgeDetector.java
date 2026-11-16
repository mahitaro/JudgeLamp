package app.judgelamp;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Finds the judgment text (GREAT / MISS ...) in a captured frame.
 *
 * Pure Java on purpose: the very same class is run on a PC against a recorded
 * play video to check it (see tools/DetectorHarness.java).
 *
 * Frames are expected in landscape with a height of {@link #CANON_HEIGHT} px.
 * The text is compared at its fixed place on screen with small reference
 * pictures ("templates"). Both sides are high-pass filtered first so that the
 * colourful backgrounds and effects matter much less than the letter shapes.
 */
public final class JudgeDetector {

    /** Height (short side) of the frames this detector works on. */
    public static final int CANON_HEIGHT = 540;

    public static final int BIT_GREAT = 1;
    public static final int BIT_GOOD = 2;
    public static final int BIT_MISS = 4;

    public static final int SENS_LOW = 0;
    public static final int SENS_NORMAL = 1;

    /** Box radius of the high-pass filter. */
    private static final int R = 5;
    /** The text is searched within +-S px of its expected place. */
    private static final int S = 1;
    /** Margin kept around every template: filter radius + search range. */
    public static final int MARGIN = R + S;
    private static final float BOX_AREA = (2 * R + 1) * (2 * R + 1);

    // Scores are 1.0 for a perfect match. On the 3-minute test recording, frames
    // without the word never scored above 0.31 (whole word) / 0.42 (half word) for
    // any of the three templates; clearly visible words scored 0.85 or more.

    /** Normal sensitivity: one frame is enough at these scores ... */
    private static final float ONE_FULL = 0.50f;
    private static final float ONE_HALF = 0.70f;
    /** ... and two frames in a row are enough at these (word mostly hidden by effects). */
    private static final float TWO_FULL = 0.45f;
    private static final float TWO_HALF = 0.60f;
    /** Low sensitivity: only a clearly visible word counts. */
    private static final float LOW_FULL = 0.70f;

    public static final class Template {
        public final String name;
        public final int bit;
        final int offX; // left edge relative to the horizontal centre of the frame
        final int y0;   // top edge
        final int w, h, half;
        final float[] t; // high-passed pixels, w*h
        final double sumL, sumR, sqL, sqR;
        int streak;
        int confirmDelay; // 0: recognised on its first frame, 1: needed a second frame
        public float lastFull, lastHalf;

        /**
         * @param gray patch of (w + 2*MARGIN) x (h + 2*MARGIN) gray pixels
         */
        public Template(String name, int bit, int offX, int y0, int patchW, int patchH, int[] gray) {
            this.name = name;
            this.bit = bit;
            this.offX = offX;
            this.y0 = y0;
            this.w = patchW - 2 * MARGIN;
            this.h = patchH - 2 * MARGIN;
            this.half = w / 2;
            float[] hp = new float[patchW * patchH];
            highPass(gray, patchW, patchH, new int[(patchW + 1) * (patchH + 1)], hp);
            t = new float[w * h];
            double sl = 0, sr = 0, ql = 0, qr = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float v = hp[(y + MARGIN) * patchW + x + MARGIN];
                    t[y * w + x] = v;
                    if (x < half) { sl += v; ql += v * v; } else { sr += v; qr += v * v; }
                }
            }
            sumL = sl; sumR = sr; sqL = ql; sqR = qr;
        }

        /**
         * File layout (big endian): int patchWidth, int patchHeight, int offX, int y0,
         * then patchWidth*patchHeight gray bytes. The patch includes a MARGIN-wide border.
         */
        public static Template load(InputStream in, String name, int bit) throws IOException {
            DataInputStream d = new DataInputStream(in);
            int pw = d.readInt();
            int ph = d.readInt();
            int offX = d.readInt();
            int y0 = d.readInt();
            if (pw <= 2 * MARGIN || ph <= 2 * MARGIN || pw > 1024 || ph > 1024) {
                throw new IOException("bad template size");
            }
            byte[] raw = new byte[pw * ph];
            d.readFully(raw);
            int[] gray = new int[raw.length];
            for (int i = 0; i < raw.length; i++) gray[i] = raw[i] & 0xFF;
            return new Template(name, bit, offX, y0, pw, ph, gray);
        }
    }

    private final Template[] templates;
    private int sensitivity = SENS_NORMAL;

    // region of interest (frame coordinates), recomputed when the frame width changes
    private int frameW = -1;
    private int roiX, roiY, roiW, roiH;
    private byte[] row = new byte[0];
    private int[] gray = new int[0];
    private int[] integ = new int[0];
    private float[] hp = new float[0];

    public JudgeDetector(Template[] templates) {
        this.templates = templates;
    }

    public Template[] templates() {
        return templates;
    }

    public void setSensitivity(int level) {
        sensitivity = level;
    }

    /** How many frames before the newest one the word of this judgment first showed (0 or 1). */
    public int lastConfirmDelay(int bit) {
        for (Template t : templates) if (t.bit == bit) return t.confirmDelay;
        return 0;
    }

    /** Forget half-confirmed detections (call when the lamp is reset). */
    public void reset() {
        for (Template t : templates) t.streak = 0;
    }

    private boolean layout(int width, int height) {
        if (height != CANON_HEIGHT) return false;
        if (width == frameW) return roiW > 0;
        frameW = width;
        int cx = width / 2;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = 0, y1 = 0;
        for (Template t : templates) {
            x0 = Math.min(x0, cx + t.offX);
            y0 = Math.min(y0, t.y0);
            x1 = Math.max(x1, cx + t.offX + t.w);
            y1 = Math.max(y1, t.y0 + t.h);
        }
        x0 -= MARGIN; y0 -= MARGIN; x1 += MARGIN; y1 += MARGIN;
        if (templates.length == 0 || x0 < 0 || y0 < 0 || x1 > width || y1 > height) {
            roiW = 0;
            return false;
        }
        roiX = x0; roiY = y0; roiW = x1 - x0; roiH = y1 - y0;
        gray = new int[roiW * roiH];
        integ = new int[(roiW + 1) * (roiH + 1)];
        hp = new float[roiW * roiH];
        return true;
    }

    /**
     * Looks at one RGBA frame.
     *
     * @return bit mask of the judgments that were recognised on this frame
     */
    public int process(ByteBuffer rgba, int width, int height, int rowStride, int pixelStride) {
        if (pixelStride < 3 || !layout(width, height)) return 0;

        int rowLen = (roiW - 1) * pixelStride + 3;
        if (row.length < rowLen) row = new byte[rowLen];
        ByteBuffer b = rgba.duplicate();
        b.clear();
        if ((roiY + roiH - 1) * rowStride + roiX * pixelStride + rowLen > b.capacity()) return 0;
        for (int y = 0; y < roiH; y++) {
            b.position((roiY + y) * rowStride + roiX * pixelStride);
            b.get(row, 0, rowLen);
            int o = y * roiW;
            for (int x = 0, p = 0; x < roiW; x++, p += pixelStride) {
                gray[o + x] = (77 * (row[p] & 0xFF) + 150 * (row[p + 1] & 0xFF)
                        + 29 * (row[p + 2] & 0xFF) + 128) >> 8;
            }
        }
        highPass(gray, roiW, roiH, integ, hp);

        int cx = width / 2;
        int fired = 0;
        for (Template t : templates) {
            score(t, cx + t.offX - roiX, t.y0 - roiY);
            boolean strong, weak;
            if (sensitivity >= SENS_NORMAL) {
                strong = t.lastFull >= ONE_FULL || t.lastHalf >= ONE_HALF;
                weak = t.lastFull >= TWO_FULL || t.lastHalf >= TWO_HALF;
            } else {
                strong = t.lastFull >= LOW_FULL;
                weak = false;
            }
            if (strong) {
                fired |= t.bit;
                t.confirmDelay = t.streak > 0 ? 1 : 0;
                t.streak = 0;
            } else if (weak) {
                if (++t.streak >= 2) {
                    fired |= t.bit;
                    t.confirmDelay = 1;
                    t.streak = 0;
                }
            } else {
                t.streak = 0;
            }
        }
        return fired;
    }

    /** Normalised cross-correlation of the template with the frame around (tx, ty). */
    private void score(Template t, int tx, int ty) {
        float bestFull = -1f, bestHalf = -1f;
        final int w = t.w, h = t.h, half = t.half;
        final float[] tp = t.t;
        final int nL = half * h, nR = (w - half) * h, n = w * h;
        for (int dy = -S; dy <= S; dy++) {
            for (int dx = -S; dx <= S; dx++) {
                double sL = 0, qL = 0, cL = 0, sR = 0, qR = 0, cR = 0;
                for (int y = 0; y < h; y++) {
                    int fo = (ty + dy + y) * roiW + tx + dx;
                    int to = y * w;
                    int x = 0;
                    for (; x < half; x++) {
                        float f = hp[fo + x];
                        sL += f; qL += f * f; cL += f * tp[to + x];
                    }
                    for (; x < w; x++) {
                        float f = hp[fo + x];
                        sR += f; qR += f * f; cR += f * tp[to + x];
                    }
                }
                float l = ncc(cL, sL, qL, t.sumL, t.sqL, nL);
                float r = ncc(cR, sR, qR, t.sumR, t.sqR, nR);
                float f = ncc(cL + cR, sL + sR, qL + qR, t.sumL + t.sumR, t.sqL + t.sqR, n);
                if (f > bestFull) bestFull = f;
                if (l > bestHalf) bestHalf = l;
                if (r > bestHalf) bestHalf = r;
            }
        }
        t.lastFull = bestFull;
        t.lastHalf = bestHalf;
    }

    private static float ncc(double cross, double sumF, double sqF, double sumT, double sqT, int n) {
        double varF = sqF - sumF * sumF / n;
        double varT = sqT - sumT * sumT / n;
        if (varF < n || varT <= 0) return 0f; // flat picture: nothing to compare
        return (float) ((cross - sumF * sumT / n) / Math.sqrt(varF * varT));
    }

    /** out = in - (mean of the surrounding (2R+1)^2 box). Valid R px inside the border. */
    static void highPass(int[] in, int w, int h, int[] integ, float[] out) {
        final int iw = w + 1;
        for (int x = 0; x < iw; x++) integ[x] = 0;
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            int o = (y + 1) * iw;
            integ[o] = 0;
            for (int x = 0; x < w; x++) {
                rowSum += in[y * w + x];
                integ[o + x + 1] = integ[o - iw + x + 1] + rowSum;
            }
        }
        for (int y = R; y < h - R; y++) {
            int top = (y - R) * iw, bot = (y + R + 1) * iw;
            for (int x = R; x < w - R; x++) {
                int sum = integ[bot + x + R + 1] - integ[top + x + R + 1]
                        - integ[bot + x - R] + integ[top + x - R];
                out[y * w + x] = in[y * w + x] - sum / BOX_AREA;
            }
        }
    }
}

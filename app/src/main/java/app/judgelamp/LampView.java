package app.judgelamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The little round lamp that floats above the game. */
final class LampView extends View {

    static final int STATE_GREEN = 0;  // nothing since the last tap
    static final int STATE_EARLY = 1;  // GREAT / GOOD, hit early -> blue
    static final int STATE_LATE = 2;   // GREAT / GOOD, hit late  -> red
    static final int STATE_MISS = 3;   // MISS                    -> yellow

    private static final int[] COLOURS = {0xFF1FE06A, 0xFF2F7BFF, 0xFFFF2A2A, 0xFFFFD60A};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int state = STATE_GREEN;
    private float diameterPx;

    LampView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        shine.setColor(0x66FFFFFF);
    }

    void setDiameterPx(float px) {
        diameterPx = px;
        invalidate();
    }

    void setState(int value) {
        if (state != value) {
            state = value;
            invalidate();
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float stroke = Math.max(1.5f * density, diameterPx * 0.09f);
        float r = diameterPx / 2f - stroke / 2f;
        if (r <= 0) return;

        boolean lit = state != STATE_GREEN;
        fill.setColor(COLOURS[state]);
        canvas.drawCircle(cx, cy, r, fill);

        // Dark rim when green, white rim once something was found: the states also
        // differ by more than colour alone.
        ring.setStrokeWidth(stroke);
        ring.setColor(lit ? 0xFFFFFFFF : 0xB3000000);
        canvas.drawCircle(cx, cy, r, ring);

        if (!lit) {
            canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.22f, shine);
        }
    }
}

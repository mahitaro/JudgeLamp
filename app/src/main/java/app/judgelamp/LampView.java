package app.judgelamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The little round lamp that floats above the game. */
final class LampView extends View {

    private static final int GREEN = 0xFF1FE06A;
    private static final int RED = 0xFFFF2A2A;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private boolean red;
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

    void setRed(boolean value) {
        if (red != value) {
            red = value;
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

        fill.setColor(red ? RED : GREEN);
        canvas.drawCircle(cx, cy, r, fill);

        // Dark rim when green, white rim when red: the two states also differ by
        // more than colour alone.
        ring.setStrokeWidth(stroke);
        ring.setColor(red ? 0xFFFFFFFF : 0xB3000000);
        canvas.drawCircle(cx, cy, r, ring);

        if (!red) {
            canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.22f, shine);
        }
    }
}

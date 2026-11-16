package app.judgelamp;

import android.content.Context;
import android.content.SharedPreferences;

/** Names and defaults of the saved settings. */
final class Prefs {

    static final String KEY_GREAT = "trigger_great";
    static final String KEY_GOOD = "trigger_good";
    static final String KEY_MISS = "trigger_miss";
    static final String KEY_SIZE_DP = "lamp_size_dp";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final int SIZE_MIN_DP = 10;
    static final int SIZE_MAX_DP = 48;
    static final int SIZE_DEFAULT_DP = 20;

    // The lamp position is kept separately for landscape (the game) and portrait
    // (home screen), each as a fraction (0..1) of the free screen width / height.
    static final String POS_PREFIX = "pos_";

    static String posXKey(boolean landscape) {
        return landscape ? "pos_x_land" : "pos_x_port";
    }

    static String posYKey(boolean landscape) {
        return landscape ? "pos_y_land" : "pos_y_port";
    }

    /** Landscape: top edge, a little right of the centre (clear of the note lanes). */
    static float posXDefault(boolean landscape) {
        return landscape ? 0.62f : 0.5f;
    }

    /** Portrait: a bit lower, so that it is not hidden behind the status bar. */
    static float posYDefault(boolean landscape) {
        return landscape ? 0f : 0.10f;
    }

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Which judgments turn the lamp red, as JudgeDetector.BIT_* flags. */
    static int triggerMask(SharedPreferences p) {
        int mask = 0;
        if (p.getBoolean(KEY_GREAT, true)) mask |= JudgeDetector.BIT_GREAT;
        if (p.getBoolean(KEY_GOOD, true)) mask |= JudgeDetector.BIT_GOOD;
        if (p.getBoolean(KEY_MISS, true)) mask |= JudgeDetector.BIT_MISS;
        return mask;
    }

    static int sizeDp(SharedPreferences p) {
        int v = p.getInt(KEY_SIZE_DP, SIZE_DEFAULT_DP);
        return Math.max(SIZE_MIN_DP, Math.min(SIZE_MAX_DP, v));
    }

    static int sensitivity(SharedPreferences p) {
        return p.getInt(KEY_SENSITIVITY, JudgeDetector.SENS_NORMAL);
    }
}

package app.judgelamp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shows the lamp above other apps and watches the screen for the judgment text.
 *
 * Green means "watching, nothing found since the last reset". Blue = GREAT/GOOD hit
 * early, red = GREAT/GOOD hit late, yellow = MISS; the newest one replaces the colour. The lamp is removed
 * whenever the screen capture ends, so a green lamp is never shown while nobody
 * is watching.
 */
public class LampService extends Service
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_START = "app.judgelamp.action.START";
    static final String ACTION_STOP = "app.judgelamp.action.STOP";
    static final String ACTION_RESET = "app.judgelamp.action.RESET";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    /** True while the lamp is on screen. Read by MainActivity. */
    static volatile boolean running;
    /** Called on the main thread whenever {@link #running} changes. */
    static volatile Runnable stateListener;

    private static final String TAG = "JudgeLamp";
    private static final String CHANNEL_ID = "lamp";
    private static final int NOTIFICATION_ID = 1;
    /** Invisible touch area around the drawn lamp, in dp (total, both sides). */
    private static final int TOUCH_PADDING_DP = 8;
    /** A judgment counts as new if its word was not seen for this many frames. */
    private static final int ONSET_GAP = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager wm;
    private int touchSlop;

    // screen capture
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private JudgeDetector detector;

    // lamp
    private LampView lamp;
    private WindowManager.LayoutParams lampParams;
    private int maxX, maxY;
    private boolean landscape;

    private volatile int triggerMask;
    private volatile int lampState = LampView.STATE_GREEN;
    private TimingProbe probe;
    private int frameNo;
    private final int[] lastSeen = new int[5]; // frame number each judgment bit was last recognised
    private volatile boolean resetRequested;
    private boolean shuttingDown;

    private final Runnable showState = new Runnable() {
        @Override
        public void run() {
            if (lamp != null) lamp.setState(lampState);
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            // The capture was ended from outside (system dialog, screen lock, ...).
            if (!shuttingDown) {
                Toast.makeText(LampService.this, R.string.toast_capture_ended, Toast.LENGTH_LONG).show();
                shutdown();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        wm = getSystemService(WindowManager.class);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            if (running) {
                resetLamp();
            } else {
                start(intent);
            }
        } else if (ACTION_RESET.equals(action) && running) {
            resetLamp();
        } else {
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ start / stop

    @SuppressWarnings("deprecation") // getParcelableExtra(String): the typed variant needs API 33
    private void start(Intent intent) {
        try {
            // Must be a foreground service of type mediaProjection before the
            // capture permission may be used (Android 10+, enforced on 14+).
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) throw new IllegalStateException("capture permission result missing");

            shuttingDown = false;
            lampState = LampView.STATE_GREEN;
            startCapture(resultCode, resultData);
            showLamp();
            running = true;
            notifyState();
        } catch (Exception e) {
            Log.e(TAG, "could not start", e);
            Toast.makeText(this, R.string.toast_start_failed, Toast.LENGTH_LONG).show();
            shutdown();
        }
    }

    private void shutdown() {
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Frees everything. Safe to call more than once. */
    private void release() {
        shuttingDown = true;
        boolean wasRunning = running;
        running = false;

        if (lamp != null) {
            try {
                wm.removeView(lamp);
            } catch (RuntimeException e) {
                Log.w(TAG, "lamp was not attached", e);
            }
            lamp = null;
        }

        // The reader is closed on the capture thread so that it can never be
        // closed in the middle of a frame being examined there.
        final VirtualDisplay vd = virtualDisplay;
        final ImageReader r = reader;
        virtualDisplay = null;
        reader = null;
        if (captureThread != null) {
            captureHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (vd != null) vd.release();
                    if (r != null) r.close();
                }
            });
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        } else {
            if (vd != null) vd.release();
            if (r != null) r.close();
        }

        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (wasRunning) notifyState();
    }

    private void notifyState() {
        final Runnable listener = stateListener;
        if (listener != null) main.post(listener);
    }

    // ------------------------------------------------------------------ screen capture

    private void startCapture(int resultCode, Intent resultData) throws IOException {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("no media projection");
        // Android 14+ requires a callback to be registered before capturing starts.
        projection.registerCallback(projectionCallback, main);

        detector = loadDetector();
        probe = new TimingProbe();
        frameNo = 0;
        java.util.Arrays.fill(lastSeen, -1000);
        detector.setSensitivity(Prefs.sensitivity(prefs));
        triggerMask = Prefs.triggerMask(prefs);

        // Always capture in landscape, scaled so that the short side is 540 px:
        // that is the size the reference pictures were made for, and a quarter of
        // the pixels of a 1080p screen keeps the extra load on the game small.
        DisplayMetrics dm = realMetrics();
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int height = JudgeDetector.CANON_HEIGHT;
        int width = Math.round(longSide * (float) height / shortSide);

        captureThread = new HandlerThread("judge-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader source) {
                examineFrames(source);
            }
        }, captureHandler);

        virtualDisplay = projection.createVirtualDisplay("JudgeLamp", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
    }

    private JudgeDetector loadDetector() throws IOException {
        try (InputStream great = getAssets().open("great.tpl");
             InputStream good = getAssets().open("good.tpl");
             InputStream miss = getAssets().open("miss.tpl")) {
            return new JudgeDetector(new JudgeDetector.Template[] {
                    JudgeDetector.Template.load(great, "GREAT", JudgeDetector.BIT_GREAT),
                    JudgeDetector.Template.load(good, "GOOD", JudgeDetector.BIT_GOOD),
                    JudgeDetector.Template.load(miss, "MISS", JudgeDetector.BIT_MISS),
            });
        }
    }

    /** Runs on the capture thread. Every frame is looked at: the text can be gone after 2-3 frames. */
    private void examineFrames(ImageReader source) {
        final JudgeDetector d = detector;
        try {
            Image image;
            while ((image = source.acquireNextImage()) != null) {
                try {
                    if (resetRequested) {
                        resetRequested = false;
                        d.reset();
                    }
                    Image.Plane plane = image.getPlanes()[0];
                    java.nio.ByteBuffer pixels = plane.getBuffer();
                    int w = image.getWidth(), h = image.getHeight();
                    int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
                    frameNo++;
                    probe.observe(pixels, w, h, rowStride, pixelStride);
                    int found = d.process(pixels, w, h, rowStride, pixelStride);
                    int newState = -1;
                    for (int bit = 1; bit <= 4; bit <<= 1) {
                        if ((found & bit) == 0) continue;
                        // The word stays on screen for several frames: only its first frame counts.
                        boolean onset = frameNo - lastSeen[bit] > ONSET_GAP;
                        lastSeen[bit] = frameNo;
                        if (!onset || (triggerMask & bit) == 0) continue;
                        if (bit == JudgeDetector.BIT_MISS) {
                            newState = LampView.STATE_MISS;
                        } else {
                            newState = probe.wasLate(d.lastConfirmDelay(bit))
                                    ? LampView.STATE_LATE : LampView.STATE_EARLY;
                        }
                    }
                    if (newState >= 0) { // the newest judgment always replaces the colour
                        lampState = newState;
                        main.post(showState);
                    }
                } finally {
                    image.close();
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "frame skipped", e);
        }
    }

    // ------------------------------------------------------------------ lamp

    private void showLamp() {
        lamp = new LampView(this);
        lampParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE: touches outside the lamp go to the game as usual.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lampParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            lampParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lampParams.setFitInsetsTypes(0); // position in full-screen coordinates
        } else {
            lampParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lampParams.setTitle("JudgeLamp");
        layoutLamp(false);
        lamp.setState(LampView.STATE_GREEN);
        lamp.setOnTouchListener(new LampTouch());
        wm.addView(lamp, lampParams);
    }

    /** Applies the size and the saved position for the current screen orientation. */
    private void layoutLamp(boolean attached) {
        float density = getResources().getDisplayMetrics().density;
        int lampPx = Math.round(Prefs.sizeDp(prefs) * density);
        int windowPx = lampPx + Math.round(TOUCH_PADDING_DP * density);
        DisplayMetrics dm = realMetrics();
        landscape = dm.widthPixels > dm.heightPixels;
        maxX = Math.max(0, dm.widthPixels - windowPx);
        maxY = Math.max(0, dm.heightPixels - windowPx);
        lampParams.width = windowPx;
        lampParams.height = windowPx;
        lampParams.x = Math.round(clamp01(prefs.getFloat(
                Prefs.posXKey(landscape), Prefs.posXDefault(landscape))) * maxX);
        lampParams.y = Math.round(clamp01(prefs.getFloat(
                Prefs.posYKey(landscape), Prefs.posYDefault(landscape))) * maxY);
        lamp.setDiameterPx(lampPx);
        if (attached) wm.updateViewLayout(lamp, lampParams);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void resetLamp() {
        lampState = LampView.STATE_GREEN;
        resetRequested = true;
        if (lamp != null) lamp.setState(LampView.STATE_GREEN);
    }

    /** Tap = back to green. Drag = move the lamp. */
    private final class LampTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lampParams.x;
                    startY = lampParams.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > touchSlop) moved = true;
                    if (moved) {
                        lampParams.x = Math.max(0, Math.min(maxX, startX + Math.round(dx)));
                        lampParams.y = Math.max(0, Math.min(maxY, startY + Math.round(dy)));
                        wm.updateViewLayout(lamp, lampParams);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        savePosition();
                    } else {
                        v.performClick();
                        resetLamp();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (moved) savePosition();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void savePosition() {
        prefs.edit()
                .putFloat(Prefs.posXKey(landscape), maxX > 0 ? lampParams.x / (float) maxX : 0f)
                .putFloat(Prefs.posYKey(landscape), maxY > 0 ? lampParams.y / (float) maxY : 0f)
                .apply();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Screen rotated: move the lamp to its place for this orientation.
        if (lamp != null) layoutLamp(true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.startsWith(Prefs.POS_PREFIX)) return; // saved by the drag itself
        triggerMask = Prefs.triggerMask(prefs);
        final JudgeDetector d = detector;
        final Handler h = captureHandler;
        final int sensitivity = Prefs.sensitivity(prefs);
        if (d != null && h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    d.setSensitivity(sensitivity);
                }
            });
        }
        if (lamp != null) layoutLamp(true);
    }

    @SuppressWarnings("deprecation") // getRealMetrics: still the simplest way that works from a Service
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm);
        return dm;
    }

    // ------------------------------------------------------------------ notification

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_lamp)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null,
                        getString(R.string.action_reset), serviceIntent(ACTION_RESET, 1)).build())
                .addAction(new Notification.Action.Builder((Icon) null,
                        getString(R.string.action_stop), serviceIntent(ACTION_STOP, 2)).build())
                .build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, LampService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

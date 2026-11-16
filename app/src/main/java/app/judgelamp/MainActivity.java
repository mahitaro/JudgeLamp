package app.judgelamp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Start / stop button and the few settings. */
public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private SharedPreferences prefs;
    private TextView status;
    private Button toggle;
    private TextView sizeLabel;
    private boolean askedForNotifications;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            boolean on = LampService.running;
            status.setText(on ? R.string.status_on : R.string.status_off);
            toggle.setText(on ? R.string.button_stop : R.string.button_start);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        status = findViewById(R.id.status);
        toggle = findViewById(R.id.toggle);
        sizeLabel = findViewById(R.id.size_label);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });

        bindCheckBox(R.id.check_great, Prefs.KEY_GREAT);
        bindCheckBox(R.id.check_good, Prefs.KEY_GOOD);
        bindCheckBox(R.id.check_miss, Prefs.KEY_MISS);

        SeekBar size = findViewById(R.id.size);
        size.setMin(Prefs.SIZE_MIN_DP);
        size.setMax(Prefs.SIZE_MAX_DP);
        size.setProgress(Prefs.sizeDp(prefs));
        showSize(Prefs.sizeDp(prefs));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                showSize(progress);
                if (fromUser) prefs.edit().putInt(Prefs.KEY_SIZE_DP, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        RadioGroup sensitivity = findViewById(R.id.sensitivity);
        sensitivity.check(Prefs.sensitivity(prefs) == JudgeDetector.SENS_LOW
                ? R.id.sensitivity_low : R.id.sensitivity_normal);
        sensitivity.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int value = checkedId == R.id.sensitivity_low
                        ? JudgeDetector.SENS_LOW : JudgeDetector.SENS_NORMAL;
                prefs.edit().putInt(Prefs.KEY_SENSITIVITY, value).apply();
            }
        });
    }

    private void bindCheckBox(int id, final String key) {
        CheckBox box = findViewById(id);
        box.setChecked(prefs.getBoolean(key, true));
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
    }

    private void showSize(int dp) {
        sizeLabel.setText(getString(R.string.size_label, dp));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LampService.stateListener = refresh;
        refresh.run();
    }

    @Override
    protected void onPause() {
        LampService.stateListener = null;
        super.onPause();
    }

    // ------------------------------------------------------------------ start / stop

    private void onToggle() {
        if (LampService.running) {
            startService(new Intent(this, LampService.class).setAction(LampService.ACTION_STOP));
            return;
        }
        // 1. permission to draw above other apps
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        // 2. notification permission (Android 13+). Only asked once; the lamp works without it.
        if (Build.VERSION.SDK_INT >= 33 && !askedForNotifications
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedForNotifications = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        requestCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) requestCapture();
    }

    /** 3. the system dialog that allows reading the screen */
    @SuppressWarnings("deprecation") // startActivityForResult: fine without AndroidX
    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Whole screen, not "a single app": the picture then always has the screen's size.
            intent = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, LampService.class)
                    .setAction(LampService.ACTION_START)
                    .putExtra(LampService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(LampService.EXTRA_RESULT_DATA, data));
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_LONG).show();
        }
    }
}

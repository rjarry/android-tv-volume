// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package cc.jarry.finevolume;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
	private static final String PREFS = "volume";
	private static final float[] MAX_ATTENUATION_PRESETS_DB = {20.0f, 30.0f, 40.0f, 50.0f};
	private static final int[] LEVEL_COUNT_PRESETS = {50, 100};


	private TextView status;
	private TextView level;
	private Button maxAttenuation;
	private Button levelCount;
	private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
			(sharedPreferences, key) -> updateStatus();

	@Override
	protected void onCreate(android.os.Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setGravity(Gravity.CENTER);
		layout.setPadding(48, 24, 48, 24);

		TextView title = new TextView(this);
		title.setText("Fine volume control");
		title.setTextSize(28);
		layout.addView(title, matchWrap());

		status = new TextView(this);
		status.setTextSize(20);
		layout.addView(status, matchWrap());

		level = new TextView(this);
		level.setTextSize(24);
		layout.addView(level, matchWrap());

		maxAttenuation = new Button(this);
		maxAttenuation.setOnClickListener(view -> cycleMaxAttenuation());
		layout.addView(maxAttenuation, matchWrap());

		levelCount = new Button(this);
		levelCount.setOnClickListener(view -> cycleLevelCount());
		layout.addView(levelCount, matchWrap());

		Button enable = new Button(this);
		enable.setText("Open accessibility settings");
		enable.setOnClickListener(view -> startActivity(
				new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
		layout.addView(enable, matchWrap());

		TextView instructions = new TextView(this);
		instructions.setText("Volume is applied as a gain inside the audio HAL, which also "
				+ "attenuates the tunneled output Netflix uses. Level 0 is mute, level 1 "
				+ "is the configured maximum attenuation, and the highest level is full "
				+ "scale. The system volume index carries as much of the attenuation as "
				+ "its coarse curve allows, so that an unexpected reboot cannot come back "
				+ "much louder.\n"
				+ "The finevold daemon captures the volume keys. The app starts it over "
				+ "adb by itself, at boot and whenever it stops responding.");
		instructions.setTextSize(18);
		layout.addView(instructions, matchWrap());

		setContentView(layout);
		updateStatus();
	}

	private LinearLayout.LayoutParams matchWrap() {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMargins(0, 6, 0, 6);
		return params;
	}

	private void cycleMaxAttenuation() {
		SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
		float current = preferences.getFloat(VolumeAccessibilityService.KEY_MAX_ATTENUATION_DB,
				VolumeAccessibilityService.DEFAULT_MAX_ATTENUATION_DB);
		float next = MAX_ATTENUATION_PRESETS_DB[0];
		for (int i = 0; i < MAX_ATTENUATION_PRESETS_DB.length; i++) {
			if (Math.abs(current - MAX_ATTENUATION_PRESETS_DB[i]) < 0.01f) {
				next = MAX_ATTENUATION_PRESETS_DB[(i + 1) % MAX_ATTENUATION_PRESETS_DB.length];
				break;
			}
		}
		int count = preferences.getInt(VolumeAccessibilityService.KEY_LEVEL_COUNT,
				VolumeAccessibilityService.DEFAULT_LEVEL_COUNT);
		preferences.edit()
				.putFloat(VolumeAccessibilityService.KEY_MAX_ATTENUATION_DB, next)
				.putInt(VolumeAccessibilityService.KEY_LEVEL, count / 2)
				.apply();
	}

	private void cycleLevelCount() {
		SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
		int current = preferences.getInt(VolumeAccessibilityService.KEY_LEVEL_COUNT,
				VolumeAccessibilityService.DEFAULT_LEVEL_COUNT);
		int next = current == LEVEL_COUNT_PRESETS[0]
				? LEVEL_COUNT_PRESETS[1] : LEVEL_COUNT_PRESETS[0];
		preferences.edit()
				.putInt(VolumeAccessibilityService.KEY_LEVEL_COUNT, next)
				.putInt(VolumeAccessibilityService.KEY_LEVEL, next / 2)
				.apply();
	}

	private void updateStatus() {
		boolean enabled = isServiceEnabled();
		status.setText(enabled ? "Accessibility service: enabled" : "Accessibility service: disabled");
		SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
		int count = preferences.getInt(VolumeAccessibilityService.KEY_LEVEL_COUNT,
				VolumeAccessibilityService.DEFAULT_LEVEL_COUNT);
		int currentLevel = preferences.getInt(VolumeAccessibilityService.KEY_LEVEL, count / 2);
		float attenuation = preferences.getFloat(
				VolumeAccessibilityService.KEY_MAX_ATTENUATION_DB,
				VolumeAccessibilityService.DEFAULT_MAX_ATTENUATION_DB);
		level.setText(String.format(java.util.Locale.US, "Level: %d / %d  (%.1f dB)",
				currentLevel, count,
				VolumeAccessibilityService.gainForLevel(currentLevel, count, attenuation)));
		maxAttenuation.setText(String.format(java.util.Locale.US,
				"Max attenuation: -%.0f dB", attenuation));
		levelCount.setText(String.format(java.util.Locale.US, "Number of levels: %d", count));
		boolean daemon = preferences.getBoolean(VolumeAccessibilityService.KEY_CONNECTED, false);
		status.append(daemon
				? "\nDaemon: connected"
				: String.format(java.util.Locale.US, "\nDaemon: not connected (%s:%d)",
						VolumeAccessibilityService.DAEMON_HOST,
						VolumeAccessibilityService.DAEMON_PORT));
		String adb = preferences.getString(VolumeAccessibilityService.KEY_ADB_STATUS, "");
		if (!daemon && !adb.isEmpty()) {
			status.append("\nDaemon start: " + adb);
		}
		String error = preferences.getString(VolumeAccessibilityService.KEY_ERROR, "");
		if (!error.isEmpty()) {
			status.append("\nEffect error: " + error);
		}
	}

	private boolean isServiceEnabled() {
		AccessibilityManager manager = getSystemService(AccessibilityManager.class);
		List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
				AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
		String expected = new ComponentName(this, VolumeAccessibilityService.class).flattenToString();
		for (AccessibilityServiceInfo service : services) {
			if (service.getResolveInfo() != null && service.getResolveInfo().serviceInfo != null) {
				ComponentName component = new ComponentName(
						service.getResolveInfo().serviceInfo.packageName,
						service.getResolveInfo().serviceInfo.name);
				if (component.flattenToString().equals(expected)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	protected void onResume() {
		super.onResume();
		getSharedPreferences(PREFS, MODE_PRIVATE)
				.registerOnSharedPreferenceChangeListener(preferenceListener);
		updateStatus();
	}

	@Override
	protected void onPause() {
		getSharedPreferences(PREFS, MODE_PRIVATE)
				.unregisterOnSharedPreferenceChangeListener(preferenceListener);
		super.onPause();
	}
}

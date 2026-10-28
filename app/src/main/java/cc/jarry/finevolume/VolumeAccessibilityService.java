// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package cc.jarry.finevolume;

import android.accessibilityservice.AccessibilityService;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class VolumeAccessibilityService extends AccessibilityService {
	static final String PREFS = "volume";
	static final String KEY_LEVEL = "level";
	static final String KEY_LEVEL_COUNT = "level_count";
	static final String KEY_MAX_ATTENUATION_DB = "max_attenuation_db";
	static final String KEY_CONNECTED = "connected";
	static final String KEY_ADB_STATUS = "adb_status";
	static final String KEY_ERROR = "error";
	static final int DEFAULT_LEVEL_COUNT = 50;
	static final float DEFAULT_MAX_ATTENUATION_DB = 50.0f;

	// The Amlogic HAL takes a per-source gain in dB through setParameters().
	// Unlike an audio effect it sits inside the HAL, so it also attenuates
	// the tunneled (HW_AV_SYNC) output that Netflix uses and that no
	// session-0 effect can reach. Order is atv, dtv, hdmiin, av, media.
	private static final String SOURCE_GAIN = "SOURCE_GAIN=0 0 0 0 %.2f";
	private static final float MIN_GAIN_DB = -100.0f;
	static final String DAEMON_HOST = "127.0.0.1";
	static final int DAEMON_PORT = 38501;

	private static final int CONNECT_TIMEOUT_MS = 2000;
	private static final long RETRY_DELAY_MS = 2000;
	private static final String ADB_HOST = "127.0.0.1";
	private static final int ADB_PORT = 5555;
	private static final int ADB_TIMEOUT_MS = 20000;
	private static final long ADB_RETRY_MS = 60000;
	private static final String DAEMON_PATH = "/data/local/tmp/finevold";
	private static final String DAEMON_LOG = "/data/local/tmp/finevold.log";
	// How far the HAL gain may drift from the system volume index before the
	// index takes over the coarse part. The index is what survives a power
	// cut, so this bounds how loud an unexpected reboot can be.
	private static final float INDEX_HYSTERESIS_DB = 6.0f;
	// The index is never taken below the point where the vendor curve stops
	// being smooth. Its bottom steps are 11.5 and 20.5 dB, and the gain has
	// to compensate for them, which is audible however the two are ordered.
	// Below that point the gain carries everything.
	private static final float MAX_INDEX_STEP_DB = 3.0f;
	// A HAL parameter takes effect sooner than a stream volume change, so
	// when the index drops the compensating gain is applied a moment later.
	// Otherwise the gain arrives first and is heard as a bump.
	private static final long INDEX_SETTLE_MS = 60;
	private static final long GAIN_WATCHDOG_MS = 5000;
	private static final float GAIN_EPSILON_DB = 0.05f;
	// The panel reproduces the TV volume dialog of SystemUI. Dimensions come
	// from res/values-television/dimens.xml, colours from colors_tv.xml and
	// values-television/colors.xml, and the animation from VolumeDialogImpl.
	private static final float PANEL_WIDTH_DP = 48.0f;
	private static final float PANEL_TRANSPARENT_PADDING_DP = 24.0f;
	private static final float BUBBLE_SIZE_DP = 36.0f;
	private static final float ROW_PADDING_DP = 6.0f;
	private static final float SLIDER_HEIGHT_DP = 190.0f;
	private static final float SLIDER_WIDTH_DP = 4.0f;
	// Measured on the device: the thumb is 16dp, and the track is inset by
	// the thumb radius plus the 8dp focus ring, which is how far a SeekBar
	// keeps the thumb drawable inside its bounds.
	private static final float THUMB_DIAMETER_DP = 16.0f;
	private static final float THUMB_FOCUS_RING_DP = 8.0f;
	private static final float THUMB_SHADOW_RADIUS_DP = 4.0f;
	private static final float THUMB_SHADOW_DY_DP = 4.0f;
	private static final float ICON_SIZE_DP = 20.0f;
	private static final float NUMBER_TEXT_SIZE_SP = 16.0f;
	private static final float PANEL_HEIGHT_DP =
			ROW_PADDING_DP + BUBBLE_SIZE_DP + SLIDER_HEIGHT_DP
					+ BUBBLE_SIZE_DP + ROW_PADDING_DP;

	private static final int COLOR_PANEL = 0xE61F232B;
	private static final int COLOR_BUBBLE = 0x08FFFFFF;
	private static final int COLOR_TRACK = 0xA03C4043;
	private static final int COLOR_ACCENT = 0xFFDADCE0;
	// Upstream names a separate seek bar fill (#F8F9FA), but this device
	// draws the filled part in the accent colour.
	private static final int COLOR_FILL = COLOR_ACCENT;
	private static final int COLOR_THUMB_SHADOW = 0x40000000;

	private static final long PANEL_TIMEOUT_MS = 3000;
	private static final long PANEL_SHOW_MS = 300;
	private static final long PANEL_HIDE_MS = 250;

	private AudioManager audioManager;
	private WindowManager windowManager;
	private SharedPreferences preferences;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private GainPanelView gainPanel;
	private Thread client;
	private Socket socket;
	private volatile boolean running;
	private volatile boolean connected;
	private int unmutedLevel;
	private long lastAdbAttemptMs;
	private float appliedGainDb;
	private float pendingGainDb;
	private final Runnable applyPendingGain = () -> setHalGain(pendingGainDb);
	private float[] streamDb;
	private int streamDbDevice = -1;
	private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
			(sharedPreferences, key) -> {
				if (KEY_LEVEL_COUNT.equals(key)
						|| KEY_MAX_ATTENUATION_DB.equals(key)) {
					setMidpoint(sharedPreferences);
				}
			};
	// The HAL may forget the gain when an output is reopened, so keep
	// checking that what it reports is still what was asked for. Only the
	// gain is restored, never the index, so this can never click.
	private final Runnable gainWatchdog = new Runnable() {
		@Override
		public void run() {
			float reported = reportedGainDb();
			if (!Float.isNaN(reported)
					&& Math.abs(reported - appliedGainDb) > GAIN_EPSILON_DB) {
				setHalGain(appliedGainDb);
			}
			handler.postDelayed(this, GAIN_WATCHDOG_MS);
		}
	};
	private final Runnable hideGainOverlay = () -> {
		GainPanelView panel = gainPanel;
		if (panel != null && panel.getVisibility() == View.VISIBLE) {
			panel.animate().cancel();
			panel.animate()
					.translationX(panel.getWidth() / 2.0f)
					.alpha(0.0f)
					.setDuration(PANEL_HIDE_MS)
					.setInterpolator(new LogAccelerateInterpolator())
					.withEndAction(() -> {
						panel.setVisibility(View.GONE);
						panel.setTranslationX(0.0f);
						panel.setAlpha(1.0f);
					})
					.start();
		}
	};

	@Override
	protected void onServiceConnected() {
		super.onServiceConnected();
		audioManager = getSystemService(AudioManager.class);
		windowManager = getSystemService(WindowManager.class);
		preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
		try {
			int levelCount = preferences.getInt(KEY_LEVEL_COUNT, DEFAULT_LEVEL_COUNT);
			int level = preferences.getInt(KEY_LEVEL, levelCount / 2);
			unmutedLevel = level > 0 ? level : levelCount / 2;
			// The HAL gain is lost on reboot while the system index is not,
			// so restore the stored level as early as possible.
			applyLevel(level, levelCount, false);
			preferences.edit().putString(KEY_ERROR, "").apply();
			preferences.registerOnSharedPreferenceChangeListener(settingsListener);
			handler.postDelayed(gainWatchdog, GAIN_WATCHDOG_MS);
			startClient();
		} catch (RuntimeException e) {
			reportError(e);
		}
	}

	static float gainForLevel(int level, int levelCount, float maxAttenuationDb) {
		if (level <= 0) {
			return MIN_GAIN_DB;
		}
		if (level >= levelCount) {
			return 0.0f;
		}
		return -maxAttenuationDb * (levelCount - level) / (levelCount - 1);
	}

	private void startClient() {
		running = true;
		client = new Thread(this::clientLoop, "finevold-client");
		client.setDaemon(true);
		client.start();
	}

	private void stopClient() {
		running = false;
		Socket open = socket;
		if (open != null) {
			try {
				open.close();
			} catch (IOException e) {
				// closing only needs to unblock the reader
			}
		}
		if (client != null) {
			client.interrupt();
			client = null;
		}
	}

	private void clientLoop() {
		while (running) {
			try (Socket conn = new Socket()) {
				socket = conn;
				conn.connect(new InetSocketAddress(DAEMON_HOST, DAEMON_PORT),
						CONNECT_TIMEOUT_MS);
				conn.setTcpNoDelay(true);
				setConnected(true);
				BufferedReader reader = new BufferedReader(new InputStreamReader(
						conn.getInputStream(), StandardCharsets.US_ASCII));
				String line;
				while ((line = reader.readLine()) != null) {
					String action = line.trim();
					handler.post(() -> handleAction(action));
				}
			} catch (IOException e) {
				// the daemon is not running yet, or went away
			} finally {
				socket = null;
				setConnected(false);
			}
			if (!running) {
				return;
			}
			startDaemon();
			try {
				Thread.sleep(RETRY_DELAY_MS);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/**
	 * Asks the local adbd to (re)start the daemon. This covers both boot, as
	 * the accessibility service starts on its own and finds nothing
	 * listening, and a daemon that died.
	 */
	private void startDaemon() {
		long now = SystemClock.uptimeMillis();
		if (lastAdbAttemptMs != 0 && now - lastAdbAttemptMs < ADB_RETRY_MS) {
			return;
		}
		lastAdbAttemptMs = now;
		String library = getApplicationInfo().nativeLibraryDir + "/libfinevold.so";
		// Match the process by exact name: -f would also match the shell
		// running this very command line, which kills the command itself.
		// Install through a temporary name so that replacing a running binary
		// cannot fail with ETXTBSY, and only when it actually differs.
		// Staying alive for a moment after spawning keeps adbd from tearing
		// the daemon down with the shell that started it.
		String command = "pkill -x finevold; "
				+ "if [ \"$(stat -c %s " + library + ")\" != "
				+ "\"$(stat -c %s " + DAEMON_PATH + " 2>/dev/null)\" ]; then "
				+ "cat " + library + " >" + DAEMON_PATH + ".new"
				+ " && chmod 755 " + DAEMON_PATH + ".new"
				+ " && mv -f " + DAEMON_PATH + ".new " + DAEMON_PATH + "; fi; "
				+ "setsid nohup " + DAEMON_PATH + " >" + DAEMON_LOG + " 2>&1 &"
				+ " sleep 2; "
				+ "pgrep -x finevold >/dev/null && echo started"
				+ " || { echo 'not running'; cat " + DAEMON_LOG + "; }";
		String status;
		try {
			String output = AdbClient.shell(this, ADB_HOST, ADB_PORT, ADB_TIMEOUT_MS,
					command).trim();
			status = output.isEmpty() ? "started" : output;
		} catch (AdbClient.AuthorizationPendingException e) {
			status = e.getMessage();
		} catch (Exception e) {
			status = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		preferences.edit().putString(KEY_ADB_STATUS, status).apply();
	}

	private void setConnected(boolean value) {
		if (connected == value) {
			return;
		}
		connected = value;
		preferences.edit().putBoolean(KEY_CONNECTED, value).apply();
	}

	private void setStreamIndex(int index) {
		try {
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index,
					AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
			if (index > 0) {
				audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
						AudioManager.ADJUST_UNMUTE,
						AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
			}
		} catch (RuntimeException e) {
			reportError(e);
		}
	}

	/**
	 * Attenuation in dB of every system volume index on the active output,
	 * relative to nothing in particular; only differences are meaningful.
	 * Returns null when the platform refuses to report the curve.
	 */
	private float[] streamDbTable() {
		int device = outputDeviceType();
		int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (streamDb != null && device == streamDbDevice && streamDb.length == max + 1) {
			return streamDb;
		}
		float[] table = new float[max + 1];
		for (int i = 0; i <= max; i++) {
			try {
				table[i] = audioManager.getStreamVolumeDb(
						AudioManager.STREAM_MUSIC, i, device);
			} catch (RuntimeException e) {
				return null;
			}
		}
		streamDb = table;
		streamDbDevice = device;
		return table;
	}

	// AudioManager.getAudioDevicesForAttributes() would name the active route
	// exactly, but it is a system API. The connected outputs are enough here:
	// the volume curve is chosen per device category, and the USB DAC and HDMI
	// share one.
	private int outputDeviceType() {
		AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
		for (AudioDeviceInfo info : outputs) {
			int type = info.getType();
			if (type == AudioDeviceInfo.TYPE_USB_HEADSET
					|| type == AudioDeviceInfo.TYPE_USB_DEVICE
					|| type == AudioDeviceInfo.TYPE_HDMI) {
				return type;
			}
		}
		return outputs.length > 0 ? outputs[0].getType() : AudioDeviceInfo.TYPE_HDMI;
	}

	/** Attenuation of a system volume index, relative to the maximum. */
	private float curveDb(int index) {
		float[] db = streamDbTable();
		int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (db == null || index < 0 || index > max) {
			return 0.0f;
		}
		return db[index] - db[max];
	}

	/**
	 * Quietest system volume index that is still louder than the target, so
	 * that the HAL gain only ever has to attenuate the remainder. Keeping as
	 * much of the attenuation as possible in the index matters because the
	 * index survives a power cut and the HAL gain does not.
	 */
	private int indexForTarget(float target) {
		int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (streamDbTable() == null) {
			return max;
		}
		int floor = smoothFloorIndex();
		for (int i = floor; i <= max; i++) {
			if (curveDb(i) >= target) {
				return i;
			}
		}
		return max;
	}

	/**
	 * Lowest index reachable without a large jump in the curve. Walking down
	 * from the maximum stops at the first step the gain could not hide.
	 */
	private int smoothFloorIndex() {
		int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (streamDbTable() == null) {
			return 1;
		}
		int i = max;
		while (i > 1 && curveDb(i) - curveDb(i - 1) <= MAX_INDEX_STEP_DB) {
			i--;
		}
		return i;
	}

	private void reportError(RuntimeException e) {
		preferences.edit()
				.putString(KEY_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage())
				.apply();
	}

	private void handleAction(String action) {
		int levelCount = preferences.getInt(KEY_LEVEL_COUNT, DEFAULT_LEVEL_COUNT);
		int level = preferences.getInt(KEY_LEVEL, levelCount / 2);
		switch (action) {
			case "up":
				setLevel(Math.min(levelCount, level + 1), levelCount);
				break;
			case "down":
				setLevel(Math.max(0, level - 1), levelCount);
				break;
			case "mute":
				if (level > 0) {
					unmutedLevel = level;
					setLevel(0, levelCount);
				} else {
					setLevel(Math.min(levelCount, Math.max(1, unmutedLevel)),
							levelCount);
				}
				break;
			default:
				break;
		}
	}

	private void setMidpoint(SharedPreferences sharedPreferences) {
		int levelCount = sharedPreferences.getInt(KEY_LEVEL_COUNT, DEFAULT_LEVEL_COUNT);
		setLevel(levelCount / 2, levelCount);
	}

	private void setLevel(int level, int levelCount) {
		applyLevel(level, levelCount, true);
	}

	/**
	 * Splits the requested loudness between the system volume index and the
	 * HAL gain. The index moves only when the gain would otherwise drift too
	 * far from it, so most keypresses are a single HAL write.
	 */
	private void applyLevel(int level, int levelCount, boolean overlay) {
		float attenuation = preferences.getFloat(
				KEY_MAX_ATTENUATION_DB, DEFAULT_MAX_ATTENUATION_DB);
		float target = gainForLevel(level, levelCount, attenuation);

		int index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		float gain = target - curveDb(index);
		if (index < 1 || gain > 0.0f || gain < -INDEX_HYSTERESIS_DB) {
			index = indexForTarget(target);
			gain = target - curveDb(index);
		}
		gain = Math.max(MIN_GAIN_DB, Math.min(0.0f, gain));

		// Whichever of the two changes attenuates goes first, so that a
		// non-atomic pair dips rather than blasts. Raising the gain is the
		// one case where ordering is not enough, because the gain would take
		// effect before the index it compensates for.
		handler.removeCallbacks(applyPendingGain);
		int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		if (index > current) {
			setHalGain(gain);
			setStreamIndex(index);
		} else if (index < current) {
			setStreamIndex(index);
			pendingGainDb = gain;
			handler.postDelayed(applyPendingGain, INDEX_SETTLE_MS);
		} else {
			setHalGain(gain);
		}

		if (level > 0) {
			unmutedLevel = level;
		}
		preferences.edit().putInt(KEY_LEVEL, level).apply();
		if (overlay) {
			showGainOverlay(level, levelCount);
		}
	}

	/** Last value the HAL reports for the media source gain, NaN if unknown. */
	private float reportedGainDb() {
		String params = audioManager.getParameters("SOURCE_GAIN");
		if (params == null) {
			return Float.NaN;
		}
		try {
			return Float.parseFloat(params.substring(params.lastIndexOf(' ') + 1));
		} catch (RuntimeException e) {
			return Float.NaN;
		}
	}

	private void setHalGain(float gainDb) {
		try {
			audioManager.setParameters(
					String.format(Locale.US, SOURCE_GAIN, gainDb));
			appliedGainDb = gainDb;
		} catch (RuntimeException e) {
			reportError(e);
		}
	}

	private void showGainOverlay(int level, int levelCount) {
		if (gainPanel == null) {
			gainPanel = new GainPanelView(this);
			gainPanel.setVisibility(View.GONE);
			// The transparent padding is part of the window, as in the
			// SystemUI layout, so the panel sits 24dp off the right edge.
			WindowManager.LayoutParams params = new WindowManager.LayoutParams(
					dp(PANEL_WIDTH_DP + 2 * PANEL_TRANSPARENT_PADDING_DP),
					dp(PANEL_HEIGHT_DP + 2 * PANEL_TRANSPARENT_PADDING_DP),
					WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
							| WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
							| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
					PixelFormat.TRANSLUCENT);
			params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
			params.setTitle("Fine volume gain panel");
			windowManager.addView(gainPanel, params);
		}

		boolean wasVisible = gainPanel.getVisibility() == View.VISIBLE;
		gainPanel.setLevel(level, levelCount);
		gainPanel.animate().cancel();
		if (wasVisible) {
			gainPanel.setAlpha(1.0f);
			gainPanel.setTranslationX(0.0f);
		} else {
			gainPanel.setAlpha(0.0f);
			gainPanel.setTranslationX(
					dp(PANEL_WIDTH_DP + 2 * PANEL_TRANSPARENT_PADDING_DP) / 2.0f);
			gainPanel.setVisibility(View.VISIBLE);
			gainPanel.animate()
					.translationX(0.0f)
					.alpha(1.0f)
					.setDuration(PANEL_SHOW_MS)
					.setInterpolator(new LogDecelerateInterpolator())
					.start();
		}
		handler.removeCallbacks(hideGainOverlay);
		handler.postDelayed(hideGainOverlay, PANEL_TIMEOUT_MS);
	}

	// SystemUIInterpolators, as used by VolumeDialogImpl for the volume
	// dialog's show and hide animations.
	private static final class LogDecelerateInterpolator implements TimeInterpolator {
		private static final float BASE = 400.0f;
		private static final float TIME_SCALE = 1.0f / 1.4f;
		private final float outputScale = 1.0f / computeLog(1.0f);

		private static float computeLog(float t) {
			return 1.0f - (float) Math.pow(BASE, -t * TIME_SCALE);
		}

		@Override
		public float getInterpolation(float t) {
			return computeLog(t) * outputScale;
		}
	}

	private static final class LogAccelerateInterpolator implements TimeInterpolator {
		private static final int BASE = 100;
		private final float logScale = 1.0f / computeLog(1.0f);

		private static float computeLog(float t) {
			return 1.0f - (float) Math.pow(BASE, -t);
		}

		@Override
		public float getInterpolation(float t) {
			return 1.0f - computeLog(1.0f - t) * logScale;
		}
	}

	private int dp(float value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private class GainPanelView extends View {
		private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path path = new Path();
		private int level;
		private int levelCount = DEFAULT_LEVEL_COUNT;

		GainPanelView(Context context) {
			super(context);
			// The thumb shadow needs software rendering.
			setLayerType(LAYER_TYPE_SOFTWARE, null);
			panelPaint.setColor(COLOR_PANEL);
			bubblePaint.setColor(COLOR_BUBBLE);
			trackPaint.setColor(COLOR_TRACK);
			fillPaint.setColor(COLOR_FILL);
			thumbPaint.setColor(COLOR_ACCENT);
			thumbPaint.setShadowLayer(dp(THUMB_SHADOW_RADIUS_DP), 0.0f,
					dp(THUMB_SHADOW_DY_DP), COLOR_THUMB_SHADOW);
			textPaint.setColor(COLOR_ACCENT);
			textPaint.setTextAlign(Paint.Align.CENTER);
			textPaint.setTextSize(sp(NUMBER_TEXT_SIZE_SP));
			// Tabular figures, so the number does not shift as it changes.
			textPaint.setFontFeatureSettings("tnum");
			iconPaint.setColor(COLOR_ACCENT);
		}

		void setLevel(int level, int levelCount) {
			this.level = level;
			this.levelCount = levelCount;
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			float pad = dp(PANEL_TRANSPARENT_PADDING_DP);
			float left = pad;
			float right = pad + dp(PANEL_WIDTH_DP);
			float top = pad;
			float bottom = pad + dp(PANEL_HEIGHT_DP);
			float cx = (left + right) / 2.0f;
			float radius = dp(PANEL_WIDTH_DP) / 2.0f;

			rect.set(left, top, right, bottom);
			canvas.drawRoundRect(rect, radius, radius, panelPaint);

			float bubble = dp(BUBBLE_SIZE_DP);
			float topBubbleCy = top + dp(ROW_PADDING_DP) + bubble / 2.0f;
			float bottomBubbleCy = bottom - dp(ROW_PADDING_DP) - bubble / 2.0f;
			canvas.drawCircle(cx, topBubbleCy, bubble / 2.0f, bubblePaint);
			canvas.drawCircle(cx, bottomBubbleCy, bubble / 2.0f, bubblePaint);

			Paint.FontMetrics metrics = textPaint.getFontMetrics();
			float baseline = topBubbleCy - (metrics.ascent + metrics.descent) / 2.0f;
			canvas.drawText(Integer.toString(level), cx, baseline, textPaint);

			// The track is inset by half of the thumb drawable, which is the
			// thumb plus its focus ring, and the thumb centre travels the
			// whole track, as a SeekBar does.
			float thumbRadius = dp(THUMB_DIAMETER_DP) / 2.0f;
			float inset = thumbRadius + dp(THUMB_FOCUS_RING_DP);
			float sliderTop = top + dp(ROW_PADDING_DP) + bubble;
			float trackTop = sliderTop + inset;
			float trackBottom = sliderTop + dp(SLIDER_HEIGHT_DP) - inset;
			float half = dp(SLIDER_WIDTH_DP) / 2.0f;
			rect.set(cx - half, trackTop, cx + half, trackBottom);
			canvas.drawRoundRect(rect, half, half, trackPaint);

			float fraction = levelCount > 0
					? Math.max(0.0f, Math.min(1.0f, (float) level / levelCount))
					: 0.0f;
			float thumbCy = trackBottom - fraction * (trackBottom - trackTop);
			if (thumbCy < trackBottom) {
				rect.set(cx - half, thumbCy, cx + half, trackBottom);
				canvas.drawRoundRect(rect, half, half, fillPaint);
			}
			canvas.drawCircle(cx, thumbCy, thumbRadius, thumbPaint);

			drawIcon(canvas, cx, bottomBubbleCy);
		}

		/** Speaker glyph on the 24 unit grid the material icons use. */
		private void drawIcon(Canvas canvas, float cx, float cy) {
			float unit = dp(ICON_SIZE_DP) / 24.0f;
			float ox = cx - 12.0f * unit;
			float oy = cy - 12.0f * unit;
			path.reset();
			path.moveTo(ox + 3 * unit, oy + 9 * unit);
			path.lineTo(ox + 7 * unit, oy + 9 * unit);
			path.lineTo(ox + 12 * unit, oy + 4 * unit);
			path.lineTo(ox + 12 * unit, oy + 20 * unit);
			path.lineTo(ox + 7 * unit, oy + 15 * unit);
			path.lineTo(ox + 3 * unit, oy + 15 * unit);
			path.close();
			iconPaint.setStyle(Paint.Style.FILL);
			canvas.drawPath(path, iconPaint);

			iconPaint.setStyle(Paint.Style.STROKE);
			iconPaint.setStrokeWidth(1.8f * unit);
			iconPaint.setStrokeCap(Paint.Cap.ROUND);
			if (level == 0) {
				canvas.drawLine(ox + 15 * unit, oy + 9 * unit,
						ox + 21 * unit, oy + 15 * unit, iconPaint);
				canvas.drawLine(ox + 21 * unit, oy + 9 * unit,
						ox + 15 * unit, oy + 15 * unit, iconPaint);
			} else {
				rect.set(ox + 10 * unit, oy + 7.5f * unit,
						ox + 19 * unit, oy + 16.5f * unit);
				canvas.drawArc(rect, -55, 110, false, iconPaint);
			}
		}

		private float sp(float value) {
			return value * getResources().getDisplayMetrics().scaledDensity;
		}
	}

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
	}

	@Override
	public void onInterrupt() {
	}

	@Override
	public void onDestroy() {
		stopClient();
		handler.removeCallbacks(hideGainOverlay);
		handler.removeCallbacks(gainWatchdog);
		handler.removeCallbacks(applyPendingGain);
		if (preferences != null) {
			preferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
			preferences.edit().putBoolean(KEY_CONNECTED, false).apply();
		}
		if (gainPanel != null) {
			gainPanel.animate().cancel();
			windowManager.removeView(gainPanel);
			gainPanel = null;
		}
		super.onDestroy();
	}
}

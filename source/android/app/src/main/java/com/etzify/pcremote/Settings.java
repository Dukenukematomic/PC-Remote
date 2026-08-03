package com.etzify.pcremote;

import android.content.SharedPreferences;

/**
 * Trackpad tuning, stored as raw slider positions (0-100) and converted to
 * multipliers on read. Keeping the slider position is what lets the settings
 * screen reopen showing exactly where the user left the handle.
 */
public class Settings {

    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_SCROLL_SPEED = "scroll_speed";

    /** 40 -> 1.9x, which is the feel the trackpad shipped with. */
    public static final int DEFAULT_SENSITIVITY = 40;
    /** 25 -> 1.0x, one scroll notch per 22dp of finger travel. */
    public static final int DEFAULT_SCROLL_SPEED = 25;

    private static final float SENS_MIN = 0.5f;
    private static final float SENS_RANGE = 3.5f;   // 0.5x .. 4.0x
    private static final float SCROLL_MIN = 0.5f;
    private static final float SCROLL_RANGE = 2.0f; // 0.5x .. 2.5x

    private Settings() {
    }

    public static float sensitivity(int progress) {
        return SENS_MIN + (progress / 100f) * SENS_RANGE;
    }

    public static float scrollSpeed(int progress) {
        return SCROLL_MIN + (progress / 100f) * SCROLL_RANGE;
    }

    public static int sensitivityProgress(SharedPreferences p) {
        return p.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
    }

    public static int scrollSpeedProgress(SharedPreferences p) {
        return p.getInt(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED);
    }

    /** Pushes the saved values onto a trackpad. */
    public static void apply(SharedPreferences p, TrackpadView pad) {
        pad.setSensitivity(sensitivity(sensitivityProgress(p)));
        pad.setScrollSpeed(scrollSpeed(scrollSpeedProgress(p)));
    }

    public static String format(float multiplier) {
        return String.format(java.util.Locale.US, "%.1f×", multiplier);
    }
}

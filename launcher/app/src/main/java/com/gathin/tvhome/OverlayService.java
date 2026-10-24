package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Date;

/**
 * Floats the free-RAM and session-time pill over whatever is playing, so the count is visible inside apps and
 * not only on the home screen. The launcher hides it while its own header pill is on screen.
 *
 * Needs "display over other apps", granted once over adb:
 *   adb shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
 */
public class OverlayService extends Service {

    private static final String EXTRA_HOME_VISIBLE = "home_visible";
    private static final long MINUTE_MS = 60_000L;
    /** Eye break: the picture turns grey this often, for this long, measured in time the TV has been on. */
    private static final long BREAK_EVERY_MS = 30 * MINUTE_MS;
    private static final long BREAK_LENGTH_MS = 2 * MINUTE_MS;

    private static final String DALTONIZER = "accessibility_display_daltonizer";
    private static final String DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String CHANNEL_ID = "tvhome_overlay";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager wm;
    private View pill;
    private TextView text;
    private boolean homeVisible;
    private boolean screenOn = true;
    private boolean breakOn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Session.settle(prefs());   // heals a session left behind by a stale clock at boot
            updateEyeBreak();
            refresh();
            // Land on the minute rather than a minute from now, so the clock changes when the clock changes.
            handler.postDelayed(this, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = !Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            if (!screenOn) {
                prefs().edit().putLong(Session.KEY_SCREEN_OFF_AT, System.currentTimeMillis()).apply();
                setGrayscale(false);   // never leave the picture grey behind a dark screen
            } else {
                Session.settle(prefs());
                updateEyeBreak();
            }
            apply();
        }
    };

    /** Tells the service whether the launcher's own header pill is currently on screen. */
    static void setHomeVisible(Context ctx, boolean visible) {
        try {
            // Foreground: onCreate calls startForeground straight away, well inside the system's grace window.
            ctx.startForegroundService(new Intent(ctx, OverlayService.class).putExtra(EXTRA_HOME_VISIBLE, visible));
        } catch (Exception ignored) {
            // Starting a service from the background is refused on some builds; the overlay simply stays as it is.
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(AppAdapter.PREFS, MODE_PRIVATE);
    }

    /**
     * Runs as a foreground service so the pill survives memory pressure: free RAM on this stick runs near
     * 40 MB, and a plain background service is the first thing the low-memory killer takes.
     */
    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle(getString(R.string.overlay_notice))
                .setOngoing(true)
                .setShowWhen(false)
                .build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        wm = getSystemService(WindowManager.class);
        // If the process died mid-break the picture would still be grey: start from a known colour.
        breakOn = true;
        setGrayscale(false);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_HOME_VISIBLE)) {
            homeVisible = intent.getBooleanExtra(EXTRA_HOME_VISIBLE, false);
        }
        apply();
        return START_STICKY;
    }

    /** Adds or removes the floating pill to match the current state. */
    private void apply() {
        boolean want = !homeVisible && screenOn && Settings.canDrawOverlays(this);
        if (want && pill == null) {
            pill = LayoutInflater.from(this).inflate(R.layout.overlay_pill, null);
            text = pill.findViewById(R.id.overlayText);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            float d = getResources().getDisplayMetrics().density;
            lp.x = (int) (28 * d);
            lp.y = (int) (18 * d);
            try {
                wm.addView(pill, lp);
            } catch (Exception e) {
                pill = null;
                text = null;
                return;
            }
            refresh();
        } else if (!want && pill != null) {
            try {
                wm.removeView(pill);
            } catch (Exception ignored) {
            }
            pill = null;
            text = null;
        }
    }

    /** The clock and how long the TV has been on. Free RAM stays on the home screen's own pill. */
    private void refresh() {
        if (text == null) return;
        String clock = DateFormat.getTimeFormat(this).format(new Date());   // follows the 12/24-hour setting
        // Say why the picture went grey, so a break never reads as the TV misbehaving.
        text.setText(breakOn
                ? clock + " · " + getString(R.string.eye_break)
                : clock + " · " + Session.format(prefs()));
    }

    /**
     * Turns the picture grey for {@link #BREAK_LENGTH_MS} at every {@link #BREAK_EVERY_MS} of TV-on time, as a
     * nudge to look away. Measured against the session, so a short time with the TV off doesn't restart the count.
     */
    private void updateEyeBreak() {
        long on = System.currentTimeMillis() - Session.start(prefs());
        setGrayscale(screenOn && on >= BREAK_EVERY_MS && on % BREAK_EVERY_MS < BREAK_LENGTH_MS);
    }

    /** Drives the accessibility daltonizer, which needs WRITE_SECURE_SETTINGS (granted to this app over adb). */
    private void setGrayscale(boolean on) {
        if (on == breakOn) return;
        try {
            if (on) Settings.Secure.putInt(getContentResolver(), DALTONIZER, 0);   // 0: monochromacy
            Settings.Secure.putInt(getContentResolver(), DALTONIZER_ENABLED, on ? 1 : 0);
            breakOn = on;
            refresh();
        } catch (Exception ignored) {
            // No permission to colour the display: the pill alone still marks the time.
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        setGrayscale(false);
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        homeVisible = true;
        apply();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** The session the pill counts, shared by the launcher's header pill and this overlay. */
    static final class Session {
        static final String KEY_SESSION_START = "session_start";
        static final String KEY_SCREEN_OFF_AT = "screen_off_at";
        /** The screen has to stay off this long for the count to start again. */
        static final long IDLE_RESET_MS = 10 * 60_000L;

        /**
         * Returns the start of the current session, restarting it when the screen stayed off past the idle
         * window. A short time with the TV off keeps the running total.
         *
         * A session may only predate this boot by the time the TV was off, which the rule caps at
         * {@link #IDLE_RESET_MS}. Anything older is a long power cut, or this stick coming up with a stale
         * clock and having it corrected afterwards: both start the count again from boot. Called every minute
         * so a correction that lands after boot heals on the next tick.
         */
        static long settle(SharedPreferences p) {
            long now = System.currentTimeMillis();
            long start = p.getLong(KEY_SESSION_START, 0L);
            long offAt = p.getLong(KEY_SCREEN_OFF_AT, 0L);
            long bootAt = now - SystemClock.elapsedRealtime();
            if (start <= 0 || start > now || start < bootAt - IDLE_RESET_MS) start = bootAt;
            if (offAt > 0 && now - offAt > IDLE_RESET_MS) start = now;
            if (start != p.getLong(KEY_SESSION_START, 0L) || offAt > 0) {
                p.edit().putLong(KEY_SESSION_START, start).remove(KEY_SCREEN_OFF_AT).apply();
            }
            return start;
        }

        static long start(SharedPreferences p) {
            return p.getLong(KEY_SESSION_START, System.currentTimeMillis());
        }

        static String format(SharedPreferences p) {
            long mins = Math.max(0, System.currentTimeMillis() - p.getLong(KEY_SESSION_START, 0L)) / 60_000L;
            long days = mins / 1440, hours = (mins % 1440) / 60, m = mins % 60;
            return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + m + "m" : m + "m";
        }
    }
}

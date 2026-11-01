package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Current model weather from Open-Meteo; no location permission, background service or dependency. */
final class WeatherStatus {
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long MAX_AGE_MS = 2 * 60 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences cache;
    private final Runnable changed;
    private final String endpoint;
    private final String location;
    private final Runnable refresh = this::refresh;
    private boolean active, closed, loading;
    private long retryAfter;

    WeatherStatus(Context context, Runnable changed) {
        this.changed = changed;
        cache = context.getSharedPreferences("current_weather", Context.MODE_PRIVATE);
        String lat = context.getString(R.string.weather_latitude);
        String lon = context.getString(R.string.weather_longitude);
        location = lat + "," + lon;
        endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&temperature_unit=celsius&timeformat=unixtime";
    }

    void start() {
        if (closed) return;
        active = true;
        changed.run();
        refresh();
    }

    void stop() {
        active = false;
        handler.removeCallbacks(refresh);
    }

    void close() {
        closed = true;
        stop();
        worker.shutdownNow();
    }

    String label() {
        long age = System.currentTimeMillis() - cache.getLong("observed", 0);
        if (!location.equals(cache.getString("location", "")) || age < 0 || age > MAX_AGE_MS) return "";
        int code = cache.getInt("code", -1);
        String icon = icon(code, cache.getBoolean("day", true));
        if (icon.isEmpty()) return "";
        return String.format(Locale.ENGLISH, "%s %d°C", icon, cache.getInt("temperature", 0));
    }

    private long age() {
        return System.currentTimeMillis() - cache.getLong("fetched", 0);
    }

    private void schedule(long delay) {
        handler.removeCallbacks(refresh);
        if (active && !closed) handler.postDelayed(refresh, Math.max(1_000L, delay));
    }

    private void refresh() {
        if (!active || closed || loading) return;
        long wait = retryAfter - SystemClock.elapsedRealtime();
        if (wait > 0) { schedule(wait); return; }
        long age = age();
        if (location.equals(cache.getString("location", "")) && age >= 0 && age < REFRESH_MS) {
            schedule(REFRESH_MS - age);
            return;
        }
        loading = true;
        worker.execute(() -> {
            HttpsURLConnection connection = null;
            boolean success = false;
            try {
                connection = (HttpsURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather HTTP status");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[2_048];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (body.size() + count > 32_768) throw new java.io.IOException("Weather response too large");
                        body.write(buffer, 0, count);
                    }
                }
                JSONObject root = new JSONObject(body.toString("UTF-8"));
                JSONObject current = root.getJSONObject("current");
                double temperature = current.getDouble("temperature_2m");
                int code = current.getInt("weather_code");
                int day = current.getInt("is_day");
                long observed = current.getLong("time");
                long now = System.currentTimeMillis();
                if (!Double.isFinite(temperature) || temperature < -100 || temperature > 70
                        || icon(code, day == 1).isEmpty() || (day != 0 && day != 1)
                        || now - observed * 1_000L > MAX_AGE_MS || observed * 1_000L - now > 300_000L) {
                    throw new java.io.IOException("Invalid or stale weather");
                }
                if (!closed) {
                    cache.edit().putString("location", location).putLong("fetched", now)
                            .putLong("observed", observed * 1_000L)
                            .putInt("temperature", (int) Math.round(temperature))
                            .putInt("code", code).putBoolean("day", day == 1).apply();
                    success = true;
                }
            } catch (Exception ignored) {
                // Keep only a recent cached reading. A missing/stale reading leaves the date alone.
            } finally {
                if (connection != null) connection.disconnect();
            }
            boolean fetched = success;
            handler.post(() -> {
                loading = false;
                if (closed) return;
                retryAfter = fetched ? 0 : SystemClock.elapsedRealtime() + RETRY_MS;
                if (active) {
                    changed.run();
                    schedule(fetched ? REFRESH_MS : RETRY_MS);
                }
            });
        });
    }

    /** WMO weather codes; text presentation keeps icons monochrome alongside JetBrains Mono. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀\uFE0E" : "☾";
        if (code == 1 || code == 2) return day ? "⛅\uFE0E" : "☁\uFE0E";
        if (code == 3) return "☁\uFE0E";
        if (code == 45 || code == 48) return "≋";
        if (code == 51 || code == 53 || code == 55 || code == 56 || code == 57
                || code == 61 || code == 63 || code == 65 || code == 66 || code == 67
                || code == 80 || code == 81 || code == 82) return "☂\uFE0E";
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return "❄\uFE0E";
        if (code == 95 || code == 96 || code == 99) return "⚡\uFE0E";
        return "";
    }
}

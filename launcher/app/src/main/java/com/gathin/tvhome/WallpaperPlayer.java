package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private static final String SHUFFLE_ORDER = "shuffle_order";
    private static final String SHUFFLE_INDEX = "shuffle_index";
    private final FrameLayout root;
    private final ImageView image;
    private final ColorDrawable idleShade = new ColorDrawable(0x00000000);
    private final int backgroundColor;
    private final List<File> files;
    private final List<String> mirroredFiles;
    private final boolean mirrorAll;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private int pendingManualSteps;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        mirroredFiles = java.util.Arrays.asList(context.getResources().getStringArray(R.array.wallpaper_mirrored_files));
        mirrorAll = context.getResources().getBoolean(R.bool.wallpaper_mirror_horizontal);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").remove("shuffle_seen").remove("shuffle_pool")
                    .remove(SHUFFLE_ORDER).remove(SHUFFLE_INDEX).apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Styling only: the tint follows this existing image through every wallpaper change.
        image.setForeground(new LayerDrawable(new android.graphics.drawable.Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x33080C19, 0x14080C19, 0x8C080C19}), idleShade}));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = pickNext(playable, previous);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, first);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                rememberDisplayed(first, previous, !displayed.equals(previous));
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = pickNext(playable, previous);
        }
    }

    /** Extra Idle-only shade, above the wallpaper/gradient and below every UI element. */
    void setIdleDimPercent(int percent) {
        int bounded = Math.max(0, Math.min(80, percent));
        idleShade.setColor((Math.round(bounded * 255f / 100f) << 24));
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
        pendingManualSteps = 0;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private Set<String> shuffleHistory(List<File> pool) {
        // Keep a separate cycle when a configured time-of-day wallpaper group changes.
        if (!collectionKey(pool).equals(preferences.getString("shuffle_pool", ""))) return Collections.emptySet();
        return preferences.getStringSet("shuffle_seen", Collections.emptySet());
    }

    private File pickNext(List<File> pool, String previous) {
        if (WallpaperRotation.ORDER_SEQUENTIAL.equals(order)) return WallpaperRotation.next(pool, previous);
        List<File> sequence = shuffleSequence(pool, previous);
        int index = indexOf(sequence, previous);
        if (index >= sequence.size() - 1) {
            sequence = startNewShuffleCycle(pool, previous);
            index = -1;
        }
        return sequence.isEmpty() ? null : sequence.get(index + 1);
    }

    private void rememberDisplayed(File file, String previous, boolean updateTime) {
        SharedPreferences.Editor editor = preferences.edit().putString("image", file.getName());
        if (updateTime) editor.putLong("changed", System.currentTimeMillis());
        if (!WallpaperRotation.ORDER_SEQUENTIAL.equals(order)) {
            List<File> pool = playable();
            List<File> sequence = shuffleSequence(pool, file.getName());
            editor.putString("shuffle_pool", collectionKey(pool)).putInt(SHUFFLE_INDEX,
                    indexOf(sequence, file.getName()));
        }
        editor.apply();
    }

    /** Keeps the shuffled order for a complete cycle so manual and timed changes traverse the same playlist. */
    private List<File> shuffleSequence(List<File> pool, String current) {
        String key = collectionKey(pool);
        List<File> saved = new ArrayList<>();
        String encoded = preferences.getString(SHUFFLE_ORDER, "");
        String savedPool = preferences.getString("shuffle_pool", "");
        if (key.equals(savedPool) && !encoded.isEmpty()) {
            Set<String> names = new java.util.HashSet<>();
            boolean valid = true;
            for (String name : encoded.split("\\n")) {
                File file = WallpaperRotation.find(pool, name);
                if (file == null || !names.add(name)) {
                    valid = false;
                    break;
                }
                saved.add(file);
            }
            if (valid && saved.size() == pool.size()) return saved;
        }

        // Older builds stored only a set of seen names. Keep those at the start of this first saved sequence,
        // put the current picture last among them, and shuffle the pictures that have not been shown yet.
        Set<String> shown = shuffleHistory(pool);
        File currentFile = WallpaperRotation.find(pool, current);
        List<File> prior = new ArrayList<>(), remaining = new ArrayList<>();
        for (File file : pool) {
            String name = file.getName();
            if (file.equals(currentFile)) continue;
            if (shown.contains(name)) prior.add(file);
            else remaining.add(file);
        }
        Collections.shuffle(prior, random);
        Collections.shuffle(remaining, random);
        saved.addAll(prior);
        int index = -1;
        if (currentFile != null) {
            saved.add(currentFile);
            index = saved.size() - 1;
        }
        saved.addAll(remaining);
        saveShuffleSequence(pool, saved, index);
        return saved;
    }

    private List<File> startNewShuffleCycle(List<File> pool, String previous) {
        List<File> sequence = new ArrayList<>(pool);
        Collections.shuffle(sequence, random);
        if (sequence.size() > 1 && sequence.get(0).getName().equals(previous)) {
            Collections.swap(sequence, 0, 1);
        }
        saveShuffleSequence(pool, sequence, -1);
        return sequence;
    }

    private void saveShuffleSequence(List<File> pool, List<File> sequence, int index) {
        StringBuilder encoded = new StringBuilder();
        for (File file : sequence) {
            if (encoded.length() > 0) encoded.append('\n');
            encoded.append(file.getName());
        }
        preferences.edit().putString("shuffle_pool", collectionKey(pool))
                .putString(SHUFFLE_ORDER, encoded.toString()).putInt(SHUFFLE_INDEX, index)
                .remove("shuffle_seen").apply();
    }

    private static int indexOf(List<File> sequence, String name) {
        for (int i = 0; i < sequence.size(); i++) {
            if (sequence.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = pickNext(playable, previous);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        displayAsync(selected, previous, changed);
    }

    /** Change to the previous/next picture while Idle Mode is active. */
    void stepByUser(int direction) {
        if (!active || closed || files.isEmpty() || direction == 0) return;
        int step = direction < 0 ? -1 : 1;
        if (loading) {
            pendingManualSteps += step;
            return;
        }
        List<File> pool = playable();
        String previous = preferences.getString("image", "");
        File selected;
        if (WallpaperRotation.ORDER_SEQUENTIAL.equals(order)) {
            selected = WallpaperRotation.step(pool, previous, Collections.emptySet(), step, order);
        } else {
            List<File> sequence = shuffleSequence(pool, previous);
            int index = indexOf(sequence, previous);
            if (step > 0 && index == sequence.size() - 1) {
                sequence = startNewShuffleCycle(pool, previous);
                index = -1;
            }
            int target = index < 0 ? (step > 0 ? 0 : sequence.size() - 1)
                    : Math.floorMod(index + step, sequence.size());
            selected = sequence.isEmpty() ? null : sequence.get(target);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        displayAsync(selected, previous, preferences.getLong("changed", 0));
    }

    private void displayAsync(File next, String previous, long changed) {
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, next);
                displayed = next.getName();
                showCaption(true);
                rememberDisplayed(next, previous, !displayed.equals(previous) || changed == 0);
                if (pendingManualSteps != 0) {
                    int step = pendingManualSteps > 0 ? 1 : -1;
                    pendingManualSteps -= step;
                    stepByUser(step);
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap, File file) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        image.setScaleX(mirrorAll || mirroredFiles.contains(file.getName()) ? -1f : 1f);
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}

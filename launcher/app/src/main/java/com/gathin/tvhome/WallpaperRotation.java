package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

final class WallpaperRotation {
    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    /** The on-TV choice (Launcher settings, Wallpaper) that turns the wallpaper off. */
    static final String CHOICE_NONE = "none";

    /**
     * What an on-TV wallpaper choice puts on Home in place of the theme's rotation: null when the theme decides
     * (no choice, or a picture that is no longer there), an empty list for {@link #CHOICE_NONE}, or the one picture
     * chosen from the theme's collection or its single {@code single} wallpaper.
     */
    static List<File> chosen(List<File> collection, File single, String choice) {
        if (choice == null) return null;
        if (CHOICE_NONE.equals(choice)) return Collections.emptyList();
        File picked = find(collection, choice);
        if (picked == null && single != null && choice.equals(single.getName())) picked = single;
        return picked == null ? null : Collections.singletonList(picked);
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /** Random permutation by drawing only from the pictures not yet shown in this cycle. */
    static File pickUnseen(List<File> images, String previous, Set<String> shown, Random random) {
        List<File> remaining = new ArrayList<>();
        for (File image : images) {
            if (!shown.contains(image.getName()) && !image.getName().equals(previous)) remaining.add(image);
        }
        // A complete cycle starts a fresh draw, still excluding the last picture at the boundary.
        return remaining.isEmpty() ? pick(images, previous, random)
                : remaining.get(random.nextInt(remaining.size()));
    }

    /** Commit only a successfully displayed picture; selecting/decoding alone never consumes a turn. */
    static Set<String> shuffleHistory(List<File> images, Set<String> shown, String previous, String selected) {
        Set<String> history = new HashSet<>();
        for (File image : images) if (shown.contains(image.getName())) history.add(image.getName());
        if (find(images, previous) != null) history.add(previous);
        if (find(images, selected) == null) return history;
        if (history.contains(selected) && !selected.equals(previous)) history.clear();
        history.add(selected);
        return history;
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /** Manual left/right browsing for sequential order; shuffle order is persisted by WallpaperPlayer. */
    static File step(List<File> images, String previous, Set<String> shown, int direction, String order) {
        if (images.isEmpty()) return null;
        int step = direction < 0 ? -1 : 1;
        int current = -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                current = i;
                break;
            }
        }
        if (images.size() == 1) return images.get(0);
        if (ORDER_SEQUENTIAL.equals(order)) {
            int start = current < 0 ? (step > 0 ? -1 : 0) : current;
            return images.get(Math.floorMod(start + step, images.size()));
        }
        if (current < 0) {
            for (int i = step > 0 ? 0 : images.size() - 1;
                 i >= 0 && i < images.size(); i += step) {
                if (!shown.contains(images.get(i).getName())) return images.get(i);
            }
            return images.get(step > 0 ? 0 : images.size() - 1);
        }
        for (int offset = 1; offset < images.size(); offset++) {
            File candidate = images.get(Math.floorMod(current + step * offset, images.size()));
            if (!shown.contains(candidate.getName())) return candidate;
        }
        // The current shuffle cycle is complete. Start the next one without repeating this picture.
        return images.get(Math.floorMod(current + step, images.size()));
    }

    /** Picks the next picture for {@code order}; an unknown order keeps the historical shuffle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        return ORDER_SEQUENTIAL.equals(order) ? next(images, previous) : pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        // The minute tick is a Handler postDelayed, so it drifts by tens of milliseconds and often lands a few
        // hundred short of the interval. Compared exactly, that pass is skipped and the picture waits a whole
        // extra interval - a one-minute collection then changes every two. Two seconds of slack absorbs the
        // drift without letting a genuinely early call advance the picture.
        long slack = Math.min(2_000L, interval / 10);
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval - slack);
    }
}

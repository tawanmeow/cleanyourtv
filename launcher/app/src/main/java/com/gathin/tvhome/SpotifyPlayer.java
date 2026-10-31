package com.gathin.tvhome;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/** Foreground-only, event-driven view of Spotify's existing local media session. */
final class SpotifyPlayer {
    private final Activity activity;
    private final View panel;
    private final TextView title, artist, album, stateLabel, elapsedLabel, durationLabel;
    private final ImageButton toggle;
    private final View progressGroup;
    private final ProgressBar progress;
    private final ImageView artwork;
    private final View[] buttons;
    private final Runnable focusStatus, focusApps;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSessionManager manager;
    private final ComponentName access;
    private final List<String> packages;
    private boolean started, listening, receiverRegistered, observerRegistered;
    private MediaController controller;
    private MediaController.Callback callback;
    private MediaMetadata metadata;
    private PlaybackState playback;
    private Bitmap displayedArt;
    private View hoveredControl;
    private final Runnable progressTick = this::tickProgress;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::select;
    private final BroadcastReceiver accessReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private final ContentObserver accessObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    SpotifyPlayer(Activity activity, Runnable focusStatus, Runnable focusApps) {
        this.activity = activity;
        this.focusStatus = focusStatus;
        this.focusApps = focusApps;
        panel = activity.findViewById(R.id.spotifyPlayer);
        title = panel.findViewById(R.id.spotifyTitle);
        artist = panel.findViewById(R.id.spotifyArtist);
        album = panel.findViewById(R.id.spotifyAlbum);
        stateLabel = panel.findViewById(R.id.spotifyState);
        artwork = panel.findViewById(R.id.spotifyArtwork);
        toggle = panel.findViewById(R.id.spotifyToggle);
        progressGroup = panel.findViewById(R.id.spotifyProgressGroup);
        progress = panel.findViewById(R.id.spotifyProgress);
        elapsedLabel = panel.findViewById(R.id.spotifyElapsed);
        durationLabel = panel.findViewById(R.id.spotifyDuration);
        buttons = new View[] {panel.findViewById(R.id.spotifyPrevious), toggle,
                panel.findViewById(R.id.spotifyNext)};
        manager = activity.getSystemService(MediaSessionManager.class);
        access = new ComponentName(activity, SpotifySessionAccess.class);
        packages = Arrays.asList(activity.getResources().getStringArray(R.array.spotify_session_packages));
        for (int i = 0; i < buttons.length; i++) {
            final int index = i;
            buttons[i].setOnClickListener(v -> transport(index));
            buttons[i].setOnFocusChangeListener((v, focused) -> updateControlHighlight());
            buttons[i].setOnHoverListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                        || event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) hoveredControl = v;
                else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && hoveredControl == v) hoveredControl = null;
                updateControlHighlight();
                return false;
            });
            buttons[i].setOnKeyListener((v, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN
                        && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) focusStatus.run();
                    else if (key == KeyEvent.KEYCODE_DPAD_DOWN) focusApps.run();
                    else {
                        int step = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                        int next = index + step;
                        while (next >= 0 && next < buttons.length && !buttons[next].isEnabled()) next += step;
                        if (next >= 0 && next < buttons.length) buttons[next].requestFocus();
                        else focusStatus.run();
                    }
                }
                return true;
            });
        }
    }

    // The legacy overload is reached only below API 33; newer Android explicitly uses NOT_EXPORTED.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter(SpotifySessionAccess.CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(accessReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(accessReceiver, filter);
            receiverRegistered = true;
            activity.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor("enabled_notification_listeners"), false, accessObserver);
            observerRegistered = true;
            refresh();
        } catch (RuntimeException unavailable) { stop(); }
    }

    void stop() {
        if (!started) return;
        started = false;
        if (receiverRegistered) {
            receiverRegistered = false;
            try { activity.unregisterReceiver(accessReceiver); } catch (RuntimeException ignored) { }
        }
        if (observerRegistered) {
            observerRegistered = false;
            try { activity.getContentResolver().unregisterContentObserver(accessObserver); }
            catch (RuntimeException ignored) { }
        }
        removeSessionListener();
        detach();
        hide(false);
    }

    boolean focusControls() {
        if (!started || panel.getVisibility() != View.VISIBLE) return false;
        if (toggle.isEnabled() && toggle.requestFocus()) return true;
        for (View button : buttons) if (button.isEnabled() && button.requestFocus()) return true;
        return false;
    }

    private boolean hasAccess() {
        String enabled = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
        if (enabled != null) for (String item : enabled.split(":")) {
            if (access.equals(ComponentName.unflattenFromString(item))) return true;
        }
        return false;
    }

    private void refresh() {
        if (!started) return;
        try {
            if (manager == null || !hasAccess()) { unavailable(); return; }
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(sessions, access, handler);
                listening = true;
            }
            select(manager.getActiveSessions(access));
        } catch (RuntimeException unavailable) { unavailable(); }
    }

    private void select(List<MediaController> active) {
        if (!started) return;
        try {
            if (!hasAccess()) { unavailable(); return; }
            MediaController chosen = null;
            if (active != null) for (MediaController candidate : active) {
                if (!packages.contains(candidate.getPackageName())) continue;
                if (chosen == null) chosen = candidate;
                if (isPlaying(candidate.getPlaybackState())) { chosen = candidate; break; }
            }
            if (chosen == null) { detach(); hide(true); return; }
            if (controller == null || !controller.getSessionToken().equals(chosen.getSessionToken())) {
                detach();
                controller = chosen;
                final MediaController watched = chosen;
                callback = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata value) {
                        if (started && controller == watched) { metadata = value; render(); }
                    }
                    @Override public void onPlaybackStateChanged(PlaybackState value) {
                        if (started && controller == watched) { playback = value; render(); }
                    }
                    @Override public void onSessionDestroyed() {
                        if (started && controller == watched) { detach(); hide(true); refresh(); }
                    }
                };
                controller.registerCallback(callback, handler);
            }
            metadata = controller.getMetadata();
            playback = controller.getPlaybackState();
            render();
        } catch (RuntimeException unavailable) { unavailable(); }
    }

    private static boolean isPlaying(PlaybackState value) {
        if (value == null) return false;
        int state = value.getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING;
    }

    private CharSequence text(String key) { return metadata == null ? null : metadata.getText(key); }

    private void updateControlHighlight() {
        View active = hoveredControl != null && hoveredControl.isEnabled() ? hoveredControl : null;
        if (active == null) for (View button : buttons) if (button.hasFocus() && button.isEnabled()) {
            active = button;
            break;
        }
        for (View button : buttons) {
            boolean selected = button == active;
            if (button.isSelected() == selected) continue;
            button.setSelected(selected);
            button.animate().cancel();
            button.animate().scaleX(selected ? 1.05f : 1f).scaleY(selected ? 1.05f : 1f).setDuration(180).start();
        }
    }

    private void tickProgress() {
        try { updateProgress(); }
        catch (RuntimeException unavailable) { progressGroup.setVisibility(View.GONE); }
    }

    private void updateProgress() {
        handler.removeCallbacks(progressTick);
        if (!started || panel.getVisibility() != View.VISIBLE || metadata == null || playback == null) {
            progressGroup.setVisibility(View.GONE);
            return;
        }
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean advancing = playback.getState() == PlaybackState.STATE_PLAYING && playback.getPlaybackSpeed() != 0;
        long position = MusicProgress.position(playback.getPosition(), playback.getLastPositionUpdateTime(),
                playback.getPlaybackSpeed(), advancing, SystemClock.elapsedRealtime(), duration);
        if (position < 0) {
            progressGroup.setVisibility(View.GONE);
            return;
        }
        progressGroup.setVisibility(View.VISIBLE);
        elapsedLabel.setText(MusicProgress.time(position));
        durationLabel.setText(MusicProgress.time(duration));
        progress.setProgress((int) Math.round(position * 1000.0 / duration));
        // Advance cached timestamps locally; never poll Spotify or discover sessions from this timer.
        if (advancing && position < duration) handler.postDelayed(progressTick, 1000);
    }

    private void render() {
        try {
            if (!hasAccess()) { unavailable(); return; }
            if (controller == null || playback == null || playback.getState() == PlaybackState.STATE_NONE
                    || playback.getState() == PlaybackState.STATE_ERROR) { hide(true); return; }
            CharSequence song = text(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(song)) song = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            CharSequence shownTitle = TextUtils.isEmpty(song) ? activity.getString(R.string.spotify_unknown_title) : song;
            if (!TextUtils.equals(title.getText(), shownTitle)) title.setText(shownTitle);
            CharSequence performer = text(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(performer)) performer = text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            artist.setText(performer);
            album.setText(text(MediaMetadata.METADATA_KEY_ALBUM));
            Bitmap art = null;
            if (metadata != null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
            // Borrow the supplied bitmap; avoid oversized textures and never recycle Spotify's bitmap.
            if (art != null && (art.isRecycled() || art.getWidth() > 2048 || art.getHeight() > 2048)) art = null;
            if (art != displayedArt) { displayedArt = art; artwork.setImageBitmap(art); }
            artwork.setVisibility(art == null ? View.INVISIBLE : View.VISIBLE);
            boolean playing = isPlaying(playback);
            toggle.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            toggle.setContentDescription(activity.getString(playing ? R.string.spotify_pause : R.string.spotify_play));
            int state = playback.getState();
            stateLabel.setText(state == PlaybackState.STATE_PLAYING ? R.string.spotify_playing
                    : state == PlaybackState.STATE_PAUSED ? R.string.spotify_paused
                    : state == PlaybackState.STATE_STOPPED ? R.string.spotify_stopped : R.string.spotify_loading);
            long actions = playback.getActions();
            View focused = panel.findFocus();
            long[] required = {PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY) | PlaybackState.ACTION_PLAY_PAUSE,
                    PlaybackState.ACTION_SKIP_TO_NEXT};
            for (int i = 0; i < buttons.length; i++) {
                boolean enabled = (actions & required[i]) != 0;
                buttons[i].setEnabled(enabled);
                buttons[i].setFocusable(enabled);
                buttons[i].setAlpha(enabled ? 1f : 0.35f);
            }
            panel.setVisibility(View.VISIBLE);
            if (focused != null && !focused.isEnabled()) {
                boolean restored = false;
                for (View button : buttons) if (button.isEnabled()) { restored = button.requestFocus(); break; }
                if (!restored) focusStatus.run();
            }
            updateControlHighlight();
            updateProgress();
        } catch (RuntimeException unavailable) { unavailable(); }
    }

    private void transport(int index) {
        if (!started || controller == null || !buttons[index].isEnabled()) return;
        try {
            if (!hasAccess()) { unavailable(); return; }
            // Refresh capabilities before dispatch: an earlier callback may still be queued.
            playback = controller.getPlaybackState();
            render();
            if (controller == null || panel.getVisibility() != View.VISIBLE || !buttons[index].isEnabled()) return;
            MediaController.TransportControls controls = controller.getTransportControls();
            if (index == 0) controls.skipToPrevious();
            else if (index == 2) controls.skipToNext();
            else if (isPlaying(playback)) controls.pause();
            else controls.play();
        } catch (RuntimeException unavailable) { unavailable(); }
    }

    private void removeSessionListener() {
        if (listening) {
            listening = false;
            try { manager.removeOnActiveSessionsChangedListener(sessions); }
            catch (RuntimeException ignored) { /* Access may already have been revoked. */ }
        }
    }

    private void detach() {
        handler.removeCallbacks(progressTick);
        if (controller != null && callback != null) {
            try { controller.unregisterCallback(callback); } catch (RuntimeException ignored) { }
        }
        controller = null;
        callback = null;
        metadata = null;
        playback = null;
        displayedArt = null;
        artwork.setImageDrawable(null);
    }

    private void unavailable() { removeSessionListener(); detach(); hide(true); }

    private void hide(boolean restoreFocus) {
        handler.removeCallbacks(progressTick);
        hoveredControl = null;
        boolean focused = panel.hasFocus();
        panel.setVisibility(View.GONE);
        for (View button : buttons) {
            button.animate().cancel();
            button.setSelected(false);
            button.setScaleX(1f);
            button.setScaleY(1f);
        }
        if (restoreFocus && focused && started) focusStatus.run();
    }
}

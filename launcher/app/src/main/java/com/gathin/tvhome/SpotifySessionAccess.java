package com.gathin.tvhome;

import android.content.Intent;
import android.service.notification.NotificationListenerService;

/** Media-session access only; deliberately independent of the Home recovery listener. */
public final class SpotifySessionAccess extends NotificationListenerService {
    static final String CHANGED = "com.gathin.tvhome.SPOTIFY_ACCESS_CHANGED";

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        signal();
    }

    @Override public void onListenerDisconnected() {
        signal();
        super.onListenerDisconnected();
    }

    private void signal() {
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }
}

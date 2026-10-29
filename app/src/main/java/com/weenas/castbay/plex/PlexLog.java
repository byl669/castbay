package com.weenas.castbay.plex;

/**
 * Where Plex protocol lines go ([com.weenas.castbay.util.Log]). A plain interface rather than
 * {@code java.util.function.Consumer}: that is API 24 and this app runs on Android 6.0 (API 23).
 */
public interface PlexLog {
    void write(String line);
}

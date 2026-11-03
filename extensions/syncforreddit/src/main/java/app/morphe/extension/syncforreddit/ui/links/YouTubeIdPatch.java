package app.morphe.extension.syncforreddit.ui.links;

import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Reads the video id out of the YouTube addresses that came after Sync did.
 *
 * <p>Sync finds the id after {@code v=}, {@code v/} or {@code youtu.be/}, and hands back the
 * whole address for anything else on youtube.com. That address then becomes the id in a
 * thumbnail's, {@code i.ytimg.com/vi/https://www.youtube.com/live/…/hqdefault.jpg}, which
 * draws nothing. Live streams, Shorts and embeds are all addressed by a path instead.
 *
 * @noinspection unused
 */
public final class YouTubeIdPatch {
    /** A video's id: eleven characters of letters, digits, dashes and underscores. */
    private static final Pattern BY_PATH = Pattern.compile(
            "^/(?:live|shorts|embed|e)/([A-Za-z0-9_-]{11})(?:[/?#]|$)");

    private YouTubeIdPatch() {}

    /**
     * Called before Sync reads the id.
     *
     * @return The id where the address puts it in its path, or null for Sync to read it.
     */
    public static String idFromPath(String link) {
        try {
            if (link == null) {
                return null;
            }
            Uri uri = Uri.parse(link.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null
                    || !(host.equalsIgnoreCase("youtube.com")
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".youtube.com"))) {
                return null;
            }
            Matcher found = BY_PATH.matcher(path);
            return found.find() ? found.group(1) : null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read a YouTube id from " + link + ": " + ex);
            return null;
        }
    }
}

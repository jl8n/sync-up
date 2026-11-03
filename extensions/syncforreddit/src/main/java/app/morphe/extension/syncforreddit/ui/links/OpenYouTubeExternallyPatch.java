package app.morphe.extension.syncforreddit.ui.links;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import app.morphe.extension.shared.Logger;

/**
 * Sends a YouTube link to the YouTube app, or to whatever else opens YouTube links, in place of
 * Sync's own player.
 *
 * <p>That player is built on the YouTube Android Player API. The YouTube app stopped serving it,
 * so the player fails to start and the library it comes from offers to install YouTube from the
 * Play Store, whether or not YouTube is installed.
 *
 * @noinspection unused
 */
public final class OpenYouTubeExternallyPatch {
    /** What Sync passes the link to its player under. */
    private static final String LINK = "url";

    private OpenYouTubeExternallyPatch() {}

    /**
     * Called as the player is created.
     *
     * @return Whether the link was handed off and the player closed, so it should not start.
     */
    public static boolean handOff(Activity player) {
        try {
            Intent started = player.getIntent();
            String link = started == null ? null : started.getStringExtra(LINK);
            if (TextUtils.isEmpty(link)) {
                return false;
            }
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link.trim()))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            player.startActivity(view);
            player.finish();
            // Nothing of the player was ever drawn, so it should not be seen to come and go.
            player.overridePendingTransition(0, 0);
            Logger.printInfo(() -> "Handed " + link + " to another app");
            return true;
        } catch (ActivityNotFoundException ex) {
            Logger.printInfo(() -> "Nothing else opens YouTube links: " + ex);
            return false;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not hand off the YouTube link: " + ex);
            return false;
        }
    }
}

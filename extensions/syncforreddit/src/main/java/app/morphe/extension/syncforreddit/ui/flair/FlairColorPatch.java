package app.morphe.extension.syncforreddit.ui.flair;

import android.graphics.Color;

import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Hands Sync a flair colour it can read.
 *
 * <p>Sync reads a flair's background colour with {@link Color#parseColor}, which takes
 * {@code #rrggbb}, {@code #aarrggbb} and a few names. Reddit also answers {@code transparent},
 * three digit colours and colours without their {@code #}, all of which throw. Sync catches that
 * and then sets no colour at all, so a recycled row went on showing the last post's flair
 * colour.
 *
 * @noinspection unused
 */
public final class FlairColorPatch {
    /** What Sync answers with its own colours for a flair with none of its own. */
    private static final String NONE = "";

    /** Sync's own name for a user's flair, which it colours itself. */
    private static final String USER_FLAIR = "user_flair";

    private FlairColorPatch() {}

    /**
     * @return A colour {@link Color#parseColor} reads, or empty where there is none to use.
     */
    public static String readable(String colour) {
        if (colour == null) {
            return null;
        }
        String held = colour.trim();
        if (held.isEmpty() || held.equalsIgnoreCase(USER_FLAIR)) {
            return held;
        }
        if (!held.startsWith("#") && held.matches("[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}")) {
            held = "#" + held;
        }
        if (held.matches("#[0-9A-Fa-f]{3}")) {
            held = "#" + held.charAt(1) + held.charAt(1) + held.charAt(2) + held.charAt(2)
                    + held.charAt(3) + held.charAt(3);
        }
        try {
            Color.parseColor(held);
            return held;
        } catch (IllegalArgumentException ex) {
            String said = colour;
            Logger.printInfo(() -> "Flair colour " + said + " cannot be read, using the theme's");
            return NONE;
        }
    }
}

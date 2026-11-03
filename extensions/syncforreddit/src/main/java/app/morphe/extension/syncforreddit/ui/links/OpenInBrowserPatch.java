package app.morphe.extension.syncforreddit.ui.links;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Opens a link meant for another app in a browser when Sync would otherwise take it back.
 *
 * <p>Sync opens a link "externally" with a plain {@code ACTION_VIEW}. When Sync is the app the
 * system opens Reddit links in, that view comes straight back to Sync, so "Open in browser" on a
 * post opens the post again. Only then is the link redirected: a link another app would open,
 * such as a YouTube link the YouTube app takes, is left to Sync to send as it always has.
 *
 * @noinspection unused
 */
public final class OpenInBrowserPatch {
    /** Any web address: what a browser answers to and an app claiming particular sites does not. */
    private static final Uri ANY_WEB_PAGE = Uri.parse("https://example.com/");

    private OpenInBrowserPatch() {}

    /**
     * @return Whether the link was opened, so that Sync does not open it again.
     */
    public static boolean open(Context context, String link) {
        try {
            if (context == null || link == null) {
                return false;
            }
            Uri uri = Uri.parse(link.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                return false;
            }

            PackageManager packages = context.getPackageManager();
            String ours = context.getPackageName();
            Intent view = new Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE);

            if (!wouldComeBack(packages, view, ours)) {
                return false;
            }

            List<String> browsers = browsers(packages, ours);
            if (browsers.isEmpty()) {
                Logger.printInfo(() -> "No browser to open " + link + " in");
                return false;
            }

            String preferred = defaultBrowser(packages, ours);
            Intent launch;
            if (preferred != null) {
                launch = new Intent(view).setPackage(preferred);
            } else if (browsers.size() == 1) {
                launch = new Intent(view).setPackage(browsers.get(0));
            } else {
                launch = chooserWithout(context, packages, view, ours);
            }
            if (!(context instanceof Activity)) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(launch);
            Logger.printInfo(() -> "Opened " + link + " in "
                    + (preferred != null ? preferred : "a browser"));
            return true;
        } catch (Exception ex) {
            // Sync's own way is the app as it was, so hand the link back to it.
            Logger.printInfo(() -> "Could not open the link in a browser: " + ex);
            return false;
        }
    }

    /**
     * Whether the system would hand the view to Sync, either directly or by offering Sync among
     * the apps to choose from.
     */
    private static boolean wouldComeBack(PackageManager packages, Intent view, String ours) {
        ResolveInfo chosen = packages.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (chosen == null || chosen.activityInfo == null) {
            return false;
        }
        if (ours.equals(chosen.activityInfo.packageName)) {
            return true;
        }
        if (!isTheChooser(chosen)) {
            // Another app takes it outright, which is what "externally" is for.
            return false;
        }
        for (ResolveInfo candidate :
                packages.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (candidate.activityInfo != null
                    && ours.equals(candidate.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    /** The browser the person picked as their default, if they have one that is not Sync. */
    private static String defaultBrowser(PackageManager packages, String ours) {
        Intent anyPage = new Intent(Intent.ACTION_VIEW, ANY_WEB_PAGE)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo chosen = packages.resolveActivity(anyPage, PackageManager.MATCH_DEFAULT_ONLY);
        if (chosen == null || chosen.activityInfo == null || isTheChooser(chosen)) {
            return null;
        }
        String browser = chosen.activityInfo.packageName;
        return ours.equals(browser) ? null : browser;
    }

    /** Every browser installed, which Sync's manifest already asks to be able to see. */
    private static List<String> browsers(PackageManager packages, String ours) {
        Intent anyPage = new Intent(Intent.ACTION_VIEW, ANY_WEB_PAGE)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        List<String> found = new ArrayList<>();
        for (ResolveInfo candidate : packages.queryIntentActivities(anyPage, 0)) {
            if (candidate.activityInfo == null) {
                continue;
            }
            String name = candidate.activityInfo.packageName;
            if (!ours.equals(name) && !found.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /** The system chooser for the view, with Sync left out of it. */
    private static Intent chooserWithout(Context context, PackageManager packages, Intent view,
                                         String ours) {
        Intent chooser = Intent.createChooser(view, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            List<ComponentName> excluded = new ArrayList<>();
            for (ResolveInfo candidate : packages.queryIntentActivities(view, 0)) {
                if (candidate.activityInfo != null
                        && ours.equals(candidate.activityInfo.packageName)) {
                    excluded.add(new ComponentName(ours, candidate.activityInfo.name));
                }
            }
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    excluded.toArray(new Parcelable[0]));
        }
        return chooser;
    }

    /** The system's own "open with" screen, which belongs to the "android" package. */
    private static boolean isTheChooser(ResolveInfo chosen) {
        return "android".equals(chosen.activityInfo.packageName)
                || chosen.activityInfo.name.endsWith("ResolverActivity");
    }
}

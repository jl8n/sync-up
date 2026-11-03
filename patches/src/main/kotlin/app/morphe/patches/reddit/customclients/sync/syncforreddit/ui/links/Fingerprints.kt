package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.links

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Opens a link outside Sync, `LinkHandler.X(Context, String)`. Every "Open in browser" row calls
 * it, as does every link Sync is set to hand to another app. It sends a plain `ACTION_VIEW` with
 * no package, so a Reddit or Imgur link goes straight back to Sync whenever Sync is the app the
 * system opens those links in.
 */
internal val openExternallyFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    returnType = "V",
    strings = listOf("Opening link externally: "),
    custom = { _, classDef -> classDef.sourceFile == "LinkHandler.java" },
)

internal const val YOUTUBE_ACTIVITY_CLASS =
    "Lcom/laurencedawson/reddit_sync/ui/activities/CustomYouTubeVideoActivity;"

/**
 * Sync's in-app YouTube player. It is built on the YouTube Android Player API, which the YouTube
 * app no longer serves, so initialising it fails as `SERVICE_MISSING` and the library offers to
 * install YouTube from the Play Store.
 *
 * The method that starts it, `LinkHandler.a0`, takes an array and so cannot be injected into,
 * which is why the activity itself is hooked.
 */
internal val youTubeActivityCreatedFingerprint = Fingerprint(
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
    custom = { method, classDef ->
        classDef.type == YOUTUBE_ACTIVITY_CLASS && method.name == "onCreate"
    },
)

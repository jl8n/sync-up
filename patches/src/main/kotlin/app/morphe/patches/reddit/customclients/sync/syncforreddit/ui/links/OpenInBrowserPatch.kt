package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/ui/links/OpenInBrowserPatch;"

@Suppress("unused")
val openInBrowserPatch = bytecodePatch(
    name = "Open in browser opens a browser",
    description = "Opens a link sent outside Sync in a browser when Sync would otherwise " +
            "open it again itself, as it does for Reddit links when it is set to open them.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // At the very start, where p0 and p1 are still the context and the link. The method has
        // six registers and two parameters, so v0 is free.
        openExternallyFingerprint.method.addInstructionsWithLabels(
            0,
            """
            invoke-static       { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->open(Landroid/content/Context;Ljava/lang/String;)Z
            move-result         v0
            if-eqz              v0, :sync_opens_it
            return-void
            """,
            ExternalLabel("sync_opens_it", openExternallyFingerprint.method.getInstruction(0)),
        )
    }
}

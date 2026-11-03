package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.flair

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/ui/flair/FlairColorPatch;"

@Suppress("unused")
val flairColorPatch = bytecodePatch(
    name = "Fix flair colors",
    description = "Reads the flair colors Sync cannot, such as transparent and three digit ones, " +
            "so a flair is not left with the color of the one drawn before it.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // At the start, where p1 is still the colour; it is only read after this.
        flairColourFingerprint.method.addInstructions(
            0,
            """
            invoke-static       { p1 }, $EXTENSION_CLASS_DESCRIPTOR->readable(Ljava/lang/String;)Ljava/lang/String;
            move-result-object  p1
            """,
        )
    }
}

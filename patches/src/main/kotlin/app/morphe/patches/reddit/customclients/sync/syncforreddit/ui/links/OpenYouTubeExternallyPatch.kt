package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/ui/links/OpenYouTubeExternallyPatch;"

@Suppress("unused")
val openYouTubeExternallyPatch = bytecodePatch(
    name = "Open YouTube links in YouTube",
    description = "Opens YouTube links in the YouTube app rather than in Sync's own player, " +
            "which no longer works and offers to install YouTube instead.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        youTubeActivityCreatedFingerprint.method.apply {
            // After super.onCreate, which an activity must call before it may finish. The
            // method has three registers and two parameters, so v0 is free.
            val afterSuper = 1
            addInstructionsWithLabels(
                afterSuper,
                """
                invoke-static       { p0 }, $EXTENSION_CLASS_DESCRIPTOR->handOff(Landroid/app/Activity;)Z
                move-result         v0
                if-eqz              v0, :play_in_sync
                return-void
                """,
                ExternalLabel("play_in_sync", getInstruction(afterSuper)),
            )
        }
    }
}

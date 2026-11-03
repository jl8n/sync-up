package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/ui/links/YouTubeIdPatch;"

@Suppress("unused")
val youTubeIdPatch = bytecodePatch(
    name = "Fix YouTube thumbnails",
    description = "Shows the thumbnail of a YouTube live stream, Short or embed, which Sync " +
            "otherwise cannot find the video of.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // At the start, where p0 is still the address. The method has nine registers and one
        // parameter, so v0 is free.
        youTubeIdFingerprint.method.addInstructionsWithLabels(
            0,
            """
            invoke-static       { p0 }, $EXTENSION_CLASS_DESCRIPTOR->idFromPath(Ljava/lang/String;)Ljava/lang/String;
            move-result-object  v0
            if-eqz              v0, :sync_reads_it
            return-object       v0
            """,
            ExternalLabel("sync_reads_it", youTubeIdFingerprint.method.getInstruction(0)),
        )
    }
}

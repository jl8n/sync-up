package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/ui/links/YouTubePlayerPatch;"

@Suppress("unused")
val youTubePlayerPatch = bytecodePatch(
    name = "Play YouTube links in Sync",
    description = "Plays YouTube links in YouTube's embedded player in place of Sync's own, " +
            "which no longer works, and opens the YouTube app where a video cannot be embedded.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        youTubeActivityCreatedFingerprint.method.apply {
            // After super.onCreate, which an activity must call before it may show anything or
            // finish. The method has three registers and two parameters, so v0 is free.
            val superCall = getInstruction(0)
            if (superCall.opcode != Opcode.INVOKE_SUPER ||
                superCall.getReference<MethodReference>()?.name != "onCreate"
            ) {
                throw PatchException("The YouTube activity does not begin with super.onCreate")
            }
            val afterSuper = 1
            addInstructionsWithLabels(
                afterSuper,
                """
                invoke-static       { p0 }, $EXTENSION_CLASS_DESCRIPTOR->play(Landroid/app/Activity;)Z
                move-result         v0
                if-eqz              v0, :sync_plays_it
                return-void
                """,
                ExternalLabel("sync_plays_it", getInstruction(afterSuper)),
            )
        }
    }
}

package app.morphe.patches.reddit.customclients.sync.syncforreddit.backup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/backup/BackupAccountsPatch;"

private const val ACTIVITY_OF_A_FRAGMENT =
    "Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;"

@Suppress("unused")
val backupAccountsPatch = bytecodePatch(
    name = "Back up accounts",
    description = "Offers to include your signed in accounts and each account's own settings " +
            "in a backup, encrypted with a passphrase, and restores them from it.",
    default = true
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        // region Leave our section to us, so Sync's own restore does not write it as a setting.

        isLeftOutFingerprint.method.addInstructionsWithLabels(
            0,
            """
            invoke-static       { p0 }, $EXTENSION_CLASS_DESCRIPTOR->isOurs(Ljava/lang/String;)Z
            move-result         v0
            if-eqz              v0, :sync_decides
            return              v0
            """,
            ExternalLabel("sync_decides", isLeftOutFingerprint.method.getInstruction(0)),
        )

        // endregion

        // region Ask first, then add the accounts to the backup as it is written.

        writeBackupFingerprint.method.apply {
            val writeMethodName = name
            // Where the backup is turned into text: the JSON is complete by then, and nothing
            // branches to this instruction, which sits in a straight run after the loop.
            val toTextIndex = indexOfFirstInstructionOrThrow {
                val reference = getReference<MethodReference>()
                reference?.definingClass == "Lorg/json/JSONObject;" &&
                        reference.name == "toString" && reference.parameterTypes.size == 1
            }
            val jsonRegister = getInstruction<FiveRegisterInstruction>(toTextIndex).registerC

            addInstructions(
                toTextIndex,
                "invoke-static { v$jsonRegister }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->addAccounts(Lorg/json/JSONObject;)V",
            )

            // At the start, before anything is read. Asking shows a dialog, and its answer runs
            // this method again by name, which is why the name is handed over: it is
            // obfuscated, and so whatever this build calls it.
            addInstructionsWithLabels(
                0,
                """
                invoke-virtual      { p0 }, $ACTIVITY_OF_A_FRAGMENT
                move-result-object  v0
                const-string        v1, "$writeMethodName"
                invoke-static       { v0, p0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->askFirst(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/String;)Z
                move-result         v0
                if-eqz              v0, :back_up
                return-void
                """,
                ExternalLabel("back_up", getInstruction(0)),
            )
        }

        // endregion

        // region Restore the accounts once Sync has restored the settings.

        restoreBackupFingerprint.method.apply {
            val appliedIndex = indexOfFirstInstructionOrThrow {
                val reference = getReference<MethodReference>()
                reference?.definingClass == "Landroid/content/SharedPreferences\$Editor;" &&
                        reference.name == "apply"
            }

            // The JSON the file was read into, which stays in its register to the end.
            val parsedIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT &&
                        getReference<MethodReference>()?.let {
                            it.definingClass == "Lorg/json/JSONObject;" && it.name == "<init>"
                        } == true
            }
            val jsonRegister = getInstruction<FiveRegisterInstruction>(parsedIndex).registerC

            // The screen the restore was started from, which the thread holds.
            val fragmentField = restoreBackupFingerprint.classDef.fields.firstOrNull {
                it.type == writeBackupFingerprint.classDef.type
            } ?: throw PatchException("The restore does not hold the backup screen")

            // After the apply rather than before it: the loop leaves straight for the apply,
            // so code placed before it would be jumped over. The register about to be loaded
            // with a class is free here.
            val free = getInstruction<OneRegisterInstruction>(appliedIndex + 1).registerA
            if (free == jsonRegister) {
                throw PatchException("No register free after the restore is applied")
            }
            addInstructions(
                appliedIndex + 1,
                """
                iget-object         v$free, p0, $fragmentField
                invoke-virtual      { v$free }, $ACTIVITY_OF_A_FRAGMENT
                move-result-object  v$free
                invoke-static       { v$free, v$jsonRegister }, $EXTENSION_CLASS_DESCRIPTOR->restoreAccounts(Landroid/content/Context;Lorg/json/JSONObject;)V
                """,
            )
        }

        // endregion
    }
}

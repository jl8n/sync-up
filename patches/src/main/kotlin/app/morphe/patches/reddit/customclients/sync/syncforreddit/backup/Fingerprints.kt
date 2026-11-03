package app.morphe.patches.reddit.customclients.sync.syncforreddit.backup

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

private const val BACKUP_SOURCE = "PreferencesBackupFragment.java"

/**
 * Writes the backup: every setting in the settings in use, as JSON, to a file. It runs on the
 * main thread, from the row's click or once a folder has been picked.
 */
internal val writeBackupFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(),
    returnType = "V",
    strings = listOf("sync_backup_", "Error backing up settings"),
    custom = { _, classDef -> classDef.sourceFile == BACKUP_SOURCE },
)

/**
 * Says whether a setting is left out of a backup and a restore. Sync leaves out the signed in
 * account's name and token here, along with anything kept for the device alone.
 */
internal val isLeftOutFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
    returnType = "Z",
    strings = listOf("username", "refresh_token"),
    custom = { _, classDef -> classDef.sourceFile == BACKUP_SOURCE },
)


/**
 * Restores a backup on a thread of its own: reads the file, writes each setting back, applies
 * them, then says so.
 */
internal val restoreBackupFingerprint = Fingerprint(
    parameters = listOf(),
    returnType = "V",
    strings = listOf("Key: "),
    custom = { method, classDef ->
        classDef.sourceFile == BACKUP_SOURCE && method.name == "run"
    },
)

package app.morphe.patches.reddit.customclients.sync.syncforreddit.diagnostics

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** Sync's logger, whose message methods R8 emptied: found by the one that still does a thing. */
internal val loggerExceptionFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/Exception;", "Z"),
    returnType = "V",
    custom = { _, classDef -> classDef.sourceFile == "Logger.java" },
)

/** What Sync would send its crash reporting: a level, a tag and a message. */
internal val crashReportFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("I", "Ljava/lang/String;", "Ljava/lang/String;"),
    returnType = "V",
    custom = { _, classDef -> classDef.sourceFile == "CrashlyticsHelper.java" },
)

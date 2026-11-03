package app.morphe.patches.reddit.customclients.sync.syncforreddit.diagnostics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.reddit.customclients.sync.SyncForRedditCompatible
import app.morphe.patches.reddit.customclients.sync.syncforreddit.extension.sharedExtensionPatch
import app.morphe.patches.reddit.customclients.sync.syncforreddit.http.interceptHttpRequests

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/syncforreddit/diagnostics/DiagnosticLogging;"

@Suppress("unused")
val diagnosticLoggingPatch = bytecodePatch(
    name = "Diagnostic logging",
    description = "Writes Sync's own log, and every request it makes, to logcat under " +
            "SyncDiag. For finding what is broken; leave it off otherwise.",
    default = false
) {
    dependsOn(sharedExtensionPatch, interceptHttpRequests)

    compatibleWith(*SyncForRedditCompatible)

    execute {
        Fingerprint(
            definingClass = EXTENSION_CLASS_DESCRIPTOR,
            name = "isPatchIncluded",
        ).method.returnEarly(true)

        // Sync's log methods are there but empty, so the call is all they hold.
        val messages = mutableClassDefBy(loggerExceptionFingerprint.classDef.type).methods.filter {
            it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Ljava/lang/String;", "Ljava/lang/String;") && it.returnType == "V"
        }
        if (messages.isEmpty()) throw PatchException("Sync's logger has no message methods")
        messages.forEach { method ->
            method.addInstruction(
                0,
                "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                        "message(Ljava/lang/String;Ljava/lang/String;)V",
            )
        }

        loggerExceptionFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->exception(Ljava/lang/Exception;)V",
        )

        crashReportFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1, p2 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "report(ILjava/lang/String;Ljava/lang/String;)V",
        )
    }
}

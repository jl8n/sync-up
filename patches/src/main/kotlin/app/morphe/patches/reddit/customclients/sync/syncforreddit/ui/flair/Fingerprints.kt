package app.morphe.patches.reddit.customclients.sync.syncforreddit.ui.flair

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val POST_FLAIR_CLASS =
    "Lcom/laurencedawson/reddit_sync/ui/views/text/spannable/children/image_components/flair/PostFlairTextView;"

/** Colours a post's flair from the colour Reddit gives it. */
internal val flairColourFingerprint = Fingerprint(
    parameters = listOf("Ljava/lang/String;"),
    returnType = "V",
    strings = listOf("user_flair"),
    custom = { method, classDef ->
        classDef.type == POST_FLAIR_CLASS && method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.let { called ->
                called.definingClass == "Landroid/graphics/Color;" && called.name == "parseColor"
            } == true
        } == true
    },
)

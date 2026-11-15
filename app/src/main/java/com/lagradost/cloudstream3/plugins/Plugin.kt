package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Stable
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import kotlin.Throws

/**
 * Host shadow of CloudStream's `Plugin` base class (same package, same name,
 * same public API as the upstream
 * `app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt`).
 *
 * Why it exists: plugins compiled against a NEWER cloudstream3 core read the
 * Compose `$stable` field off this class in their own `<clinit>`
 * (StreamPlay v709: `NoSuchFieldError: No field $stable of type I in class
 * Lcom/lagradost/cloudstream3/plugins/Plugin`). The prebuilt
 * `app/libs/cloudstream3.jar` was compiled by an older toolchain whose
 * `Plugin` carries no such field, so every newer plugin died at
 * `Cs3PluginManager` instantiate time. The jar's copy is excluded from the
 * `cloudstreamJarClean` task in `app/build.gradle.kts`; this source —
 * compiled here with the current Kotlin/Compose plugin and explicitly
 * `@Stable` — emits the field, so newer plugins link. The public API is
 * identical to upstream, so both old and new plugins behave the same.
 *
 * If a plugin still fails with a `$stable` linkage after this, the plugin
 * needs an even newer core than this shadow — that is a core-bump decision,
 * not a loader bug (see `Cs3PluginManager.isStabilityLinkage`).
 */
@Stable
abstract class Plugin : BasePlugin() {
    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /**
     * Used to register VideoClickAction instances
     * @param element VideoClickAction you want to register
     */
    fun registerVideoClickAction(element: VideoClickAction) {
        Log.i(PLUGIN_TAG, "Adding ${element.name} VideoClickAction")
        element.sourcePlugin = this.filename
        VideoClickActionHolder.allVideoClickActions.add(element)
    }

    /**
     * This will contain your resources if you specified requiresResources in gradle
     */
    var resources: Resources? = null

    /**
     * This will add a button in the settings allowing you to add custom settings
     */
    var openSettings: ((context: Context) -> Unit)? = null
}

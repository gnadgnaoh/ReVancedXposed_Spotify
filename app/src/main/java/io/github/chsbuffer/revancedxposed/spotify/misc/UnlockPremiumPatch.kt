package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {
    // Override the attributes map in the getter method.
    ::productStateProtoFingerprint.hookMethod {
        val field = ::attributesMapField.field
        before { param ->
            Logger.printDebug { field.get(param.thisObject)!!.toString() }
            UnlockPremiumPatch.overrideAttributes(field.get(param.thisObject) as Map<String, *>)
        }
    }

    // Add the query parameter trackRows to show popular tracks in the artist page.
    ::buildQueryParametersFingerprint.hookMethod {
        after { param ->
            val result = param.result
            val FIELD = "checkDeviceCapability"
            if (result.toString().contains("${FIELD}=")) {
                param.result = XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, arrayOf(param.args[0], true)
                )
            }
        }
    }

    // Enable choosing a specific song/artist via Google Assistant.
    ::contextFromJsonFingerprint.hookMethod {
        fun removeStationString(field: Field, obj: Any) {
            field.set(obj, UnlockPremiumPatch.removeStationString(field.get(obj) as String))
        }

        after { param ->
            val thiz = param.result
            val clazz = param.result.javaClass
            removeStationString(clazz.findField("uri"), thiz)
            removeStationString(clazz.findField("url"), thiz)
        }
    }

    // Disable forced shuffle when asking for an album/playlist via Google Assistant.
    XposedHelpers.findAndHookMethod(
        "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
        classLoader,
        "build",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject.callMethod("shufflingContext", false)
            }
        })

    // Hook the method which adds context menu items and return before adding if the item is a Premium ad.
    val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
    XposedBridge.hookAllConstructors(
        contextMenuViewModelClazz, object : XC_MethodHook() {
            val isPremiumUpsell = ::isPremiumUpsellField.field

            override fun beforeHookedMethod(param: MethodHookParam) {
                val parameterTypes = (param.method as Constructor<*>).parameterTypes
                Logger.printDebug { "ContextMenuViewModel(${parameterTypes.joinToString(",") { it.name }})" }
                for (i in 0 until param.args.size) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue
                    Logger.printDebug { "List value type: ${original.firstOrNull()?.javaClass}" }
                    val filtered = original.filter {
                        it!!.callMethod("getViewModel").let { isPremiumUpsell.get(it) } != true
                    }
                    param.args[i] = filtered
                    Logger.printDebug { "Filtered ${original.size - filtered.size} context menu items." }
                }
            }
        })

    // Remove ads sections from home.
    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result
            // Set sections mutable
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeHomeSections(param.result as MutableList<*>)
        }
    }
    // Remove ads sections from browser.
    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result
            // Set sections mutable
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeBrowseSections(param.result as MutableList<*>)
        }
    }

    // Remove pendragon (pop up ads) requests and return the errors instead.
    val replaceFetchRequestSingleWithError = object : XC_MethodHook() {
        val justMethod =
            DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()

        val onErrorField =
            DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

        override fun afterHookedMethod(param: MethodHookParam) {
            if (!param.result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            val justError = justMethod.invoke(null, onErrorField.get(param.result))
            param.result = justError
        }
    }

    ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)
    ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)

    // Fix for Spotify 9.1.32: Hook Restrictions$Builder.build() to clear server-sent
    // playback restrictions that lock the seek bar and gray out the shuffle button.
    //
    // Root cause (confirmed from classes7.dex analysis):
    //   - Spotify server sends a Restrictions protobuf with free-account player state.
    //   - Fields disallowSeekingReasons_ and disallowTogglingShuffleReasons_ are populated
    //     with restriction reasons, causing the UI to disable seeking and shuffle toggling.
    //   - These restrictions are applied at UI render time, independently of the account
    //     attribute overrides done in overrideAttributes(). Overriding attributes alone
    //     is NOT sufficient to fix the seek/shuffle issue in 9.1.32.
    //
    // Fix: intercept every Restrictions instance right after it is built and clear
    //      the disallow fields so the player UI sees a fully unrestricted state.
    //
    // Class confirmed in classes7.dex:
    //   Lcom/spotify/player/model/AutoValue_Restrictions$Builder;
    //   Lcom/spotify/player/model/AutoValue_Restrictions;
    runCatching {
        ::restrictionsBuilderFingerprint.hookMethod {
            after { param ->
                val result = param.result ?: return@after
                UnlockPremiumPatch.clearPlayerRestrictions(result)
            }
        }
        Logger.printInfo { "UnlockPremium: Restrictions hook installed successfully" }
    }.onFailure { ex ->
        // Log but do not crash — other hooks (attribute overrides, pendragon, etc.) still work.
        Logger.printException(
            { "UnlockPremium: Failed to install Restrictions hook. Seek/shuffle may be locked." },
            ex
        )
    }
}

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

    // -------------------------------------------------------------------------
    // 1. Override account attributes (ProductStateProto)
    // -------------------------------------------------------------------------
    ::productStateProtoFingerprint.hookMethod {
        val field = ::attributesMapField.field
        before { param ->
            Logger.printDebug { field.get(param.thisObject)!!.toString() }
            UnlockPremiumPatch.overrideAttributes(field.get(param.thisObject) as Map<String, *>)
        }
    }

    // -------------------------------------------------------------------------
    // 2. Add trackRows query parameter (artist page popular tracks)
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // 3. Google Assistant — play specific song/artist
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // 4. Disable forced shuffle (Google Assistant album/playlist)
    // -------------------------------------------------------------------------
    XposedHelpers.findAndHookMethod(
        "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
        classLoader,
        "build",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject.callMethod("shufflingContext", false)
            }
        })

    // -------------------------------------------------------------------------
    // 5. Filter Premium upsell items from context menu
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // 6. Remove ad sections from home feed
    // -------------------------------------------------------------------------
    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeHomeSections(param.result as MutableList<*>)
        }
    }

    // -------------------------------------------------------------------------
    // 7. Remove ad sections from browse
    // -------------------------------------------------------------------------
    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeBrowseSections(param.result as MutableList<*>)
        }
    }

    // -------------------------------------------------------------------------
    // 8. Block pendragon (pop-up ads) fetch requests
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // 9. Fix: seek bar locked + shuffle grayed out (Spotify 9.1.32)
    // -------------------------------------------------------------------------
    runCatching {
        ::restrictionsBuilderFingerprint.hookMethod {
            after { param ->
                val result = param.result ?: return@after
                UnlockPremiumPatch.clearPlayerRestrictions(result)
            }
        }
        Logger.printInfo { "UnlockPremium: Restrictions hook installed (seek + shuffle fix)" }
    }.onFailure { ex ->
        Logger.printException(
            { "UnlockPremium: Restrictions hook failed — seek/shuffle may remain locked" }, ex
        )
    }

    // -------------------------------------------------------------------------
    // 10. Fix: logout after ~2 min caused by Google Maps SDK OAuth token refresh
    //
    // Root cause (confirmed via logcat, two sessions):
    //   Google Maps opens SpotifyAuthenticationActivity → AuthorizationActivity.
    //
    // Previous fix mistake: setResult(RESULT_OK) with null Intent data
    //   → NPE in LoginActivity.onActivityResult: intent.getParcelableExtra(key) on null
    //   → Google Maps FATAL EXCEPTION → Spotify logout
    //   Confirmed at log lines:
    //     23:04:45 PID 1854  — Failure delivering result{result=-1, data=null}
    //     23:16:18 PID 3239  — same crash, new Maps process
    //     23:16:45 PID 11165 — same crash again
    //
    // Correct fix: setResult(RESULT_CANCELED)
    //   The Spotify Android SDK LoginActivity checks result code before reading data.
    //   RESULT_CANCELED = user dismissed → SDK fires error callback, no NPE, no crash.
    //   Google Maps continues running. Spotify session stays alive.
    //
    // Only deny external callers (callingPackage != null && != "com.spotify.music").
    // Internal Spotify flows (callingPackage == null or == "com.spotify.music") are
    // skipped so the user can still log in manually.
    // -------------------------------------------------------------------------
    runCatching {
        val authActivityClass = runCatching {
            XposedHelpers.findClass(
                "com.spotify.appauthorization.sso.AuthorizationActivity",
                classLoader
            )
        }.getOrElse {
            Logger.printDebug { "findClass failed for AuthorizationActivity, using DexKit fallback" }
            ::ssoAuthorizationActivityClass.clazz
        }

        XposedBridge.hookAllMethods(
            authActivityClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as android.app.Activity
                    val callingPackage = activity.callingPackage

                    // Skip internal Spotify auth flows (manual login).
                    if (callingPackage == null || callingPackage == "com.spotify.music") {
                        Logger.printDebug {
                            "AuthorizationActivity: skipping — internal Spotify flow " +
                                    "(callingPackage=$callingPackage)"
                        }
                        return
                    }

                    // External SDK request (Google Maps, etc.) — deny silently.
                    // RESULT_CANCELED is safe: SDK handles it as "user dismissed"
                    // without crashing. RESULT_OK with null data caused NPE crash.
                    Logger.printInfo {
                        "AuthorizationActivity: silently denying SSO from $callingPackage"
                    }
                    UnlockPremiumPatch.silentlyDenyAuthorization(activity)
                }
            }
        )
        Logger.printInfo { "UnlockPremium: SSO deny hook installed (logout fix)" }
    }.onFailure { ex ->
        Logger.printException(
            { "UnlockPremium: SSO hook failed — Google Maps may still trigger logout" }, ex
        )
    }
}

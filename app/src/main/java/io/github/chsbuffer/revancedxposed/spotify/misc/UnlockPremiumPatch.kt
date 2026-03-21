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
    //
    // Hooks AutoValue_Restrictions$Builder.build() so every Restrictions instance
    // is cleared of server-set disallow fields before reaching the player UI.
    //
    // Class confirmed in classes7.dex:
    //   Lcom/spotify/player/model/AutoValue_Restrictions$Builder;
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
    // Root cause confirmed via logcat (full.log):
    //   20:43:11 — Spotify starts playing (PID 15221)
    //   20:45:05 — Google Maps (PID 10139) opens SpotifyAuthenticationActivity
    //   20:45:06 — Spotify opens AuthorizationActivity (OAuth consent dialog)
    //   20:47:55 — PlaybackState ERROR(7): "Vui lòng đăng nhập"
    //
    // Fix: hook AuthorizationActivity.onCreate() and immediately call
    // setResult(RESULT_OK) + finish() so the OAuth flow completes silently
    // without interrupting playback or showing any UI.
    //
    // We use XposedHelpers.findClass (stable class name, not obfuscated) rather
    // than the DexKit fingerprint. The ssoAuthorizationActivityClass fingerprint
    // in Fingerprints.kt serves as a typed fallback if the class name ever changes.
    // -------------------------------------------------------------------------
    runCatching {
        val authActivityClass = runCatching {
            // Primary: class name is stable across Spotify versions.
            XposedHelpers.findClass(
                "com.spotify.appauthorization.sso.AuthorizationActivity",
                classLoader
            )
        }.getOrElse {
            // Fallback: use DexKit fingerprint.
            Logger.printDebug { "XposedHelpers.findClass failed for AuthorizationActivity, using DexKit fallback" }
            ::ssoAuthorizationActivityClass.clazz
        }

        XposedBridge.hookAllMethods(
            authActivityClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as android.app.Activity

                    // Only auto-approve if launched by a third-party app (not Spotify itself).
                    // callingPackage is null when Spotify starts the activity internally for
                    // its own login flow — we must NOT intercept that case.
                    val callingPackage = activity.callingPackage
                    if (callingPackage == null || callingPackage == "com.spotify.music") {
                        Logger.printDebug {
                            "AuthorizationActivity: skipping auto-approve " +
                                    "(callingPackage=$callingPackage — internal Spotify flow)"
                        }
                        return
                    }

                    Logger.printInfo {
                        "AuthorizationActivity: auto-approving SSO request from $callingPackage"
                    }
                    UnlockPremiumPatch.autoApproveAuthorization(activity)
                }
            }
        )

        Logger.printInfo { "UnlockPremium: SSO auto-approve hook installed (logout fix)" }
    }.onFailure { ex ->
        Logger.printException(
            { "UnlockPremium: SSO hook failed — third-party apps may still trigger logout" }, ex
        )
    }
}

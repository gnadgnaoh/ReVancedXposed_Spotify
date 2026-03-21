package io.github.chsbuffer.revancedxposed.spotify.misc

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.SkipTest
import io.github.chsbuffer.revancedxposed.findClassDirect
import io.github.chsbuffer.revancedxposed.findFieldDirect
import io.github.chsbuffer.revancedxposed.findMethodDirect
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.strings
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.enums.UsingType

// -----------------------------------------------------------------------------
// Existing fingerprints (unchanged)
// -----------------------------------------------------------------------------

val productStateProtoFingerprint = fingerprint {
    returns("Ljava/util/Map;")
    classMatcher { descriptor = "Lcom/spotify/remoteconfig/internal/ProductStateProto;" }
}

val attributesMapField =
    findFieldDirect { productStateProtoFingerprint().usingFields.single().field }

val buildQueryParametersFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("trackRows", "device_type:tablet")
        }
    }.single()
}

val contextFromJsonFingerprint = fingerprint {
    opcodes(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC
    )
    methodMatcher {
        name("fromJson")
        declaredClass(
            "voiceassistants.playermodels.ContextJsonAdapter", StringMatchType.EndsWith
        )
    }
}

val contextMenuViewModelClass = findClassDirect {
    return@findClassDirect runCatching {
        fingerprint {
            strings("ContextMenuViewModel(header=")
        }
    }.getOrElse {
        fingerprint {
            accessFlags(AccessFlags.CONSTRUCTOR)
            strings("ContextMenuViewModel cannot contain items with duplicate itemResId. id=")
            parameters("L", "Ljava/util/List;", "Z")
        }
    }.declaredClass!!
}

val viewModelClazz = findClassDirect {
    findMethod {
        findFirst = true
        matcher { name("getViewModel") }
    }.single().returnType!!
}

val isPremiumUpsellField = findFieldDirect {
    viewModelClazz().fields.filter { it.typeName == "boolean" }[1]
}

@SkipTest
fun structureGetSectionsFingerprint(className: String) = fingerprint {
    classMatcher { className(className, StringMatchType.EndsWith) }
    methodMatcher {
        addUsingField {
            usingType = UsingType.Read
            name = "sections_"
        }
    }
}

val homeStructureGetSectionsFingerprint =
    structureGetSectionsFingerprint("homeapi.proto.HomeStructure")
val browseStructureGetSectionsFingerprint =
    structureGetSectionsFingerprint("browsita.v1.resolved.BrowseStructure")

val pendragonJsonFetchMessageRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name("apply")
            addInvoke {
                name("<init>")
                declaredClass("FetchMessageRequest", StringMatchType.EndsWith)
            }
        }
    }.single()
}

val pendragonJsonFetchMessageListRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name("apply")
            addInvoke {
                name("<init>")
                declaredClass("FetchMessageListRequest", StringMatchType.EndsWith)
            }
        }
    }.single()
}

// -----------------------------------------------------------------------------
// New fingerprints for Spotify 9.1.32 fixes
// -----------------------------------------------------------------------------

/**
 * Fix: seek bar locked + shuffle button grayed out.
 *
 * Hooks AutoValue_Restrictions$Builder.build() so every constructed Restrictions
 * instance is cleared before it reaches the player UI layer.
 *
 * Class confirmed in classes7.dex:
 *   Lcom/spotify/player/model/AutoValue_Restrictions$Builder;
 *   Lcom/spotify/player/model/AutoValue_Restrictions;
 *
 * Fields targeted (all confirmed in classes7.dex string pool):
 *   disallowSeekingReasons_          → seek bar locked
 *   disallowTogglingShuffleReasons_  → shuffle button grayed out
 *   disallowSkippingNextReasons_     → skip next locked
 *   disallowSkippingPrevReasons_     → skip prev locked
 */
val restrictionsBuilderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name("build")
            declaredClass("AutoValue_Restrictions\$Builder", StringMatchType.EndsWith)
        }
    }.single()
}

/**
 * Fix: logout after ~2 minutes of playback caused by Google Maps SDK OAuth token refresh.
 *
 * Root cause confirmed in full.log:
 *   20:45:05 — com.google.android.apps.maps opens SpotifyAuthenticationActivity
 *   20:45:06 — Spotify opens AuthorizationActivity (SSO OAuth consent dialog)
 *   20:47:55 — PlaybackState ERROR(7): "Vui lòng đăng nhập"
 *
 * AuthorizationActivity class confirmed in:
 *   classes3.dex, classes4.dex, classes5.dex, classes7.dex, classes8.dex
 * Full class name: com.spotify.appauthorization.sso.AuthorizationActivity
 *
 * We hook onCreate() and auto-approve by calling setResult(RESULT_OK) + finish()
 * before the activity renders, so no UI is shown and the SDK gets a valid grant signal.
 *
 * Note: we use hookAllMethods instead of a DexKit fingerprint because the class name
 * is stable (not obfuscated) across Spotify versions and XposedHelpers.findClass is
 * more reliable for Activity subclasses than DexKit method matching.
 * The fingerprint val below is kept as a typed placeholder for consistency with the
 * rest of the patch; the actual hook is registered directly in UnlockPremiumPatch.kt
 * using XposedHelpers.findClass + XposedBridge.hookAllMethods.
 */
val ssoAuthorizationActivityClass = findClassDirect {
    findClass {
        matcher {
            // SimilarRegex not needed — class name is stable and not obfuscated.
            // Use EndsWith so the descriptor prefix "L...;" is handled automatically.
            className(
                "appauthorization.sso.AuthorizationActivity",
                StringMatchType.EndsWith
            )
        }
    }.firstOrNull()
        ?: findClass {
            // Fallback: find by unique string present only in this class.
            // "com.spotify.sso.action.START_GOOGLE_AUTH_FLOW_V1" is declared in
            // classes3.dex and referenced exclusively from AuthorizationActivity.
            matcher {
                addUsingString("START_GOOGLE_AUTH_FLOW_V1")
            }
        }.first()
}

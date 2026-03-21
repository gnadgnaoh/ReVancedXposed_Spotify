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

/**
 * Fix: seek bar locked + shuffle button grayed out (Spotify 9.1.32).
 * Hooks AutoValue_Restrictions$Builder.build() to clear disallow fields.
 * Class confirmed in classes7.dex: Lcom/spotify/player/model/AutoValue_Restrictions$Builder;
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
 * Fix: logout after ~2 min caused by Google Maps SDK OAuth token refresh (Spotify 9.1.32).
 *
 * Root cause confirmed via logcat (two separate log sessions, PIDs 1854/3239/10139):
 *   Google Maps opens SpotifyAuthenticationActivity → Spotify opens AuthorizationActivity.
 *   Previous fix (RESULT_OK + null data) caused NPE crash in Google Maps LoginActivity
 *   (intent.getParcelableExtra on null data) → Google Maps dies → Spotify logout.
 *   Correct fix: RESULT_CANCELED — Spotify Android SDK handles this gracefully.
 *
 * Class confirmed in classes3/4/5/7/8.dex:
 *   Lcom/spotify/appauthorization/sso/AuthorizationActivity;
 *
 * We use XposedHelpers.findClass (stable name) in UnlockPremiumPatch.kt.
 * This fingerprint is a DexKit fallback only.
 */
val ssoAuthorizationActivityClass = findClassDirect {
    findClass {
        matcher {
            className(
                "appauthorization.sso.AuthorizationActivity",
                StringMatchType.EndsWith
            )
        }
    }.firstOrNull()
        ?: findClass {
            // Fallback: unique string confirmed in classes3.dex only in this class.
            matcher {
                addUsingString("START_GOOGLE_AUTH_FLOW_V1")
            }
        }.first()
}

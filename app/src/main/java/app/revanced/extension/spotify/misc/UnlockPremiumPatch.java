/*
 * Custom changes:
 * - Wipe stubbed types: REMOVED_HOME_SECTIONS, overrideAttributes, removeHomeSections
 * - Fix Spotify 9.1.32.2083:
 *     [LOGOUT] Added on-demand-restricted=false, ad-based-on-demand=false to PREMIUM_OVERRIDES
 *     [SEEK]   Added clearPlayerRestrictions() — clears disallowSeekingReasons_ from server proto
 *     [SHUFFLE] clearPlayerRestrictions() also clears disallowTogglingShuffleReasons_
 *     [LOGOUT] silentlyDenyAuthorization() — fixes Google Maps SDK triggering re-auth
 *              Root cause confirmed via logcat: after ~2 min Google Maps (PID 10139/1854/3239)
 *              opens SpotifyAuthenticationActivity → Spotify opens AuthorizationActivity (SSO).
 *              Previous fix used RESULT_OK with null data → NPE crash in Google Maps
 *              LoginActivity.onActivityResult (data=null) → Google Maps process dies → logout.
 *              Correct fix: RESULT_CANCELED — SDK handles this gracefully without crashing.
 */
package app.revanced.extension.spotify.misc;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.revanced.extension.shared.Logger;
import de.robv.android.xposed.XposedHelpers;

@SuppressWarnings("unused")
public final class UnlockPremiumPatch {

    private static class OverrideAttribute {
        final String key;
        final Object overrideValue;
        final boolean isExpected;

        OverrideAttribute(String key, Object overrideValue) {
            this(key, overrideValue, true);
        }

        OverrideAttribute(String key, Object overrideValue, boolean isExpected) {
            this.key = Objects.requireNonNull(key);
            this.overrideValue = Objects.requireNonNull(overrideValue);
            this.isExpected = isExpected;
        }
    }

    private static final List<OverrideAttribute> PREMIUM_OVERRIDES = List.of(
            new OverrideAttribute("ads", FALSE),
            new OverrideAttribute("player-license", "premium"),
            new OverrideAttribute("player-license-v2", "premium"),
            new OverrideAttribute("shuffle", FALSE),
            new OverrideAttribute("on-demand", TRUE),
            new OverrideAttribute("streaming", TRUE),
            new OverrideAttribute("pick-and-shuffle", FALSE),
            new OverrideAttribute("streaming-rules", ""),
            new OverrideAttribute("nft-disabled", "1"),
            new OverrideAttribute("type", "premium"),
            new OverrideAttribute("can_use_superbird", TRUE, false),
            new OverrideAttribute("tablet-free", FALSE, false),
            // Fix 9.1.32: confirmed in classes5.dex
            new OverrideAttribute("on-demand-restricted", FALSE, false),
            // Fix 9.1.32: confirmed in classes8.dex
            new OverrideAttribute("ad-based-on-demand", FALSE, false)
    );

    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.home.evopage.homeapi.proto.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.home.evopage.homeapi.proto.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    // -------------------------------------------------------------------------
    // Injection points
    // -------------------------------------------------------------------------

    public static void overrideAttributes(Map<String, ?> attributes) {
        try {
            for (OverrideAttribute override : PREMIUM_OVERRIDES) {
                var attribute = attributes.get(override.key);
                if (attribute == null) {
                    if (override.isExpected)
                        Logger.printException(() -> "Attribute " + override.key + " expected but not found");
                    continue;
                }
                Object overrideValue = override.overrideValue;
                Object originalValue = XposedHelpers.getObjectField(attribute, "value_");
                if (overrideValue.equals(originalValue)) continue;
                Logger.printInfo(() -> "Overriding account attribute " + override.key +
                        " from " + originalValue + " to " + overrideValue);
                XposedHelpers.setObjectField(attribute, "value_", overrideValue);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "overrideAttributes failure", ex);
        }
    }

    public static String removeStationString(String spotifyUriOrUrl) {
        try {
            Logger.printInfo(() -> "Removing station string from " + spotifyUriOrUrl);
            return spotifyUriOrUrl.replace("spotify:station:", "spotify:");
        } catch (Exception ex) {
            Logger.printException(() -> "removeStationString failure", ex);
            return spotifyUriOrUrl;
        }
    }

    /**
     * Injection point. Clear server-sent player restrictions.
     *
     * Spotify 9.1.32 sends a Restrictions protobuf with free-account player state.
     * Fields confirmed in classes7.dex:
     *   disallowSeekingReasons_          → seek bar locked
     *   disallowTogglingShuffleReasons_  → shuffle grayed out
     *   disallowSkippingNextReasons_     → skip next locked
     *   disallowSkippingPrevReasons_     → skip prev locked
     *   disallowPausingReasons_          → pause locked (free radio)
     */
    public static void clearPlayerRestrictions(Object restrictions) {
        try {
            clearField(restrictions, "disallowSeekingReasons_");
            clearField(restrictions, "disallowTogglingShuffleReasons_");
            clearField(restrictions, "disallowSkippingNextReasons_");
            clearField(restrictions, "disallowSkippingPrevReasons_");
            clearField(restrictions, "disallowPausingReasons_");
            try {
                XposedHelpers.setBooleanField(restrictions, "allowSeeking_", true);
            } catch (NoSuchFieldError ignored) {}
            Logger.printInfo(() -> "clearPlayerRestrictions: seek + shuffle + skip unlocked");
        } catch (Exception ex) {
            Logger.printException(() -> "clearPlayerRestrictions failure", ex);
        }
    }

    /**
     * Injection point. Silently deny SSO authorization requests from third-party apps.
     *
     * Root cause (confirmed via full.log, two separate log sessions):
     *   Google Maps (PIDs 1854, 3239, 10139) uses the Spotify Android SDK.
     *   After ~2 min the SDK OAuth token expires. Maps opens:
     *     SpotifyAuthenticationActivity → Spotify opens AuthorizationActivity.
     *
     * Previous fix (RESULT_OK + null data) caused:
     *   RuntimeException: Failure delivering result ResultInfo{result=-1, data=null}
     *   NullPointerException at LoginActivity.onActivityResult (intent.getParcelableExtra on null)
     *   → Google Maps process crash → Spotify forced back to login screen.
     *
     * Correct fix: return RESULT_CANCELED.
     *   The Spotify Android SDK's LoginActivity.onActivityResult checks result code first.
     *   RESULT_CANCELED means "user dismissed" — SDK handles this gracefully:
     *   it fires an error callback to the app without crashing, and Google Maps
     *   continues running without killing Spotify's session.
     *
     * Why not supply a real token?
     *   AppProtocol$TokenResponse(Integer expiresIn, String accessToken, String tokenType)
     *   requires a valid server-issued access token. We cannot generate one client-side.
     *   RESULT_CANCELED is the correct signal for "auth not completed" per the SDK contract.
     *
     * @param authorizationActivity the intercepted AuthorizationActivity instance.
     */
    public static void silentlyDenyAuthorization(android.app.Activity authorizationActivity) {
        try {
            String callingPackage = authorizationActivity.getCallingPackage();
            Logger.printInfo(() -> "silentlyDenyAuthorization: denying SSO request" +
                    (callingPackage != null ? " from " + callingPackage : " (unknown caller)"));

            // RESULT_CANCELED: SDK treats this as user dismissal — no NPE, no crash.
            authorizationActivity.setResult(android.app.Activity.RESULT_CANCELED);
            authorizationActivity.finish();

            Logger.printInfo(() -> "silentlyDenyAuthorization: finished AuthorizationActivity silently");
        } catch (Exception ex) {
            Logger.printException(() -> "silentlyDenyAuthorization failure", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void clearField(Object obj, String fieldName) {
        try {
            XposedHelpers.setObjectField(obj, fieldName, null);
            Logger.printDebug(() -> "Cleared field: " + fieldName);
        } catch (NoSuchFieldError e) {
            Logger.printDebug(() -> "Field not found (skipped): " + fieldName);
        }
    }

    private interface FeatureTypeIdProvider<T> {
        int getFeatureTypeId(T section);
    }

    private static <T> void removeSections(
            List<T> sections,
            FeatureTypeIdProvider<T> featureTypeExtractor,
            List<Integer> idsToRemove
    ) {
        try {
            Iterator<T> iterator = sections.iterator();
            while (iterator.hasNext()) {
                T section = iterator.next();
                int featureTypeId = featureTypeExtractor.getFeatureTypeId(section);
                if (idsToRemove.contains(featureTypeId)) {
                    Logger.printInfo(() -> "Removing section with feature type id " + featureTypeId);
                    iterator.remove();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "removeSections failure", ex);
        }
    }

    public static void removeHomeSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from home");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "featureTypeCase_"),
                REMOVED_HOME_SECTIONS
        );
    }

    public static void removeBrowseSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from browse");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "sectionTypeCase_"),
                REMOVED_BROWSE_SECTIONS
        );
    }
}

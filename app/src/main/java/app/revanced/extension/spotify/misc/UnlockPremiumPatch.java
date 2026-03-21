/*
 * Custom changes:
 * - Wipe stubbed types: REMOVED_HOME_SECTIONS, overrideAttributes, removeHomeSections
 * - Fix Spotify 9.1.32.2083:
 *     [LOGOUT] Added on-demand-restricted=false, ad-based-on-demand=false to PREMIUM_OVERRIDES
 *     [SEEK]   Added clearPlayerRestrictions() — clears disallowSeekingReasons_ from server proto
 *     [SHUFFLE] clearPlayerRestrictions() also clears disallowTogglingShuffleReasons_
 *     [LOGOUT] Added autoApproveAuthorization() — fixes Google Maps SDK triggering re-auth
 *              after ~2 min of playback. Root cause confirmed via logcat:
 *              com.google.android.apps.maps/SpotifyAuthenticationActivity (PID 10139)
 *              → com.spotify.appauthorization.sso.AuthorizationActivity (PID 15221)
 *              at 20:45:05, exactly 2 min after playback started at 20:43:11.
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
        /** Account attribute key. */
        final String key;
        /** Override value. */
        final Object overrideValue;
        /**
         * If true, logs an error when the attribute is missing.
         * Set false for keys that only exist in some Spotify versions.
         */
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
            // Disables player and app ads.
            new OverrideAttribute("ads", FALSE),
            // Works along on-demand, allows playing any song without restriction.
            new OverrideAttribute("player-license", "premium"),
            new OverrideAttribute("player-license-v2", "premium"),
            // Disables shuffle being initially enabled when first playing a playlist.
            new OverrideAttribute("shuffle", FALSE),
            // Allows playing any song on-demand, without a shuffled order.
            new OverrideAttribute("on-demand", TRUE),
            // Make sure playing songs is not disabled remotely and playlists show up.
            new OverrideAttribute("streaming", TRUE),
            // Allows adding songs to queue and removes the smart shuffle mode restriction,
            // allowing to pick any of the other modes.
            new OverrideAttribute("pick-and-shuffle", FALSE),
            // Disables shuffle-mode streaming-rule, which forces songs to be played shuffled
            // and breaks the player when other patches are applied.
            new OverrideAttribute("streaming-rules", ""),
            // Enables premium UI in settings and removes the premium button in the nav-bar.
            new OverrideAttribute("nft-disabled", "1"),
            // Enable Spotify Connect and disable other premium related UI, like buying premium.
            // Also removes the download button.
            new OverrideAttribute("type", "premium"),
            // Enable Spotify Car Thing hardware device (discontinued).
            new OverrideAttribute("can_use_superbird", TRUE, false),
            // Removes the premium button in the nav-bar for tablet users.
            new OverrideAttribute("tablet-free", FALSE, false),
            // Fix 9.1.32: prevents server from flagging free account streaming without ads.
            // String confirmed present in classes5.dex.
            new OverrideAttribute("on-demand-restricted", FALSE, false),
            // Fix 9.1.32: disables ad-based on-demand mode check that triggers session
            // invalidation / FORCED_LOGOUT. Confirmed in classes8.dex.
            new OverrideAttribute("ad-based-on-demand", FALSE, false)
    );

    /**
     * A list of home section feature type ids to remove (ad sections).
     */
    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.home.evopage.homeapi.proto.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.home.evopage.homeapi.proto.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    /**
     * A list of browse section feature type ids to remove (ad sections).
     */
    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    // -------------------------------------------------------------------------
    // Injection points
    // -------------------------------------------------------------------------

    /**
     * Injection point. Override account attributes from ProductStateProto.
     */
    public static void overrideAttributes(Map<String, ?> attributes) {
        try {
            for (OverrideAttribute override : PREMIUM_OVERRIDES) {
                var attribute = attributes.get(override.key);

                if (attribute == null) {
                    if (override.isExpected) {
                        Logger.printException(() -> "Attribute " + override.key + " expected but not found");
                    }
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

    /**
     * Injection point. Remove station data from Google Assistant URI.
     */
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
     * Injection point. Clear server-sent playback restrictions from Restrictions protobuf.
     *
     * In Spotify 9.1.32 the server sends a Restrictions protobuf object alongside every
     * player state update for free accounts. The player UI reads restriction fields
     * directly from this object, so overriding account attributes alone is not sufficient.
     *
     * Fields confirmed in classes7.dex:
     *   disallowSeekingReasons_          → seek bar locked       (fix: seek)
     *   disallowTogglingShuffleReasons_  → shuffle grayed out    (fix: shuffle)
     *   disallowSkippingNextReasons_     → skip next disabled
     *   disallowSkippingPrevReasons_     → skip prev disabled
     *   disallowPausingReasons_          → pause disabled (free radio)
     *   allowSeeking_                    → explicit allow flag
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
            } catch (NoSuchFieldError ignored) { /* not present in all versions */ }
            Logger.printInfo(() -> "clearPlayerRestrictions: seek + shuffle + skip unlocked");
        } catch (Exception ex) {
            Logger.printException(() -> "clearPlayerRestrictions failure", ex);
        }
    }

    /**
     * Injection point. Auto-approve SSO authorization requests from third-party apps.
     *
     * Root cause confirmed in full.log:
     *   Google Maps (PID 10139) uses the Spotify Android SDK to control playback.
     *   After ~2 minutes the SDK OAuth token expires. Maps opens:
     *     SpotifyAuthenticationActivity (com.google.android.apps.maps, 20:45:05)
     *   which causes Spotify to present:
     *     AuthorizationActivity (com.spotify.appauthorization.sso, 20:45:06)
     *   This interrupts playback. At 20:47:55 PlaybackState → ERROR(7):
     *     "Vui lòng đăng nhập để sử dụng Spotify."
     *
     * Fix: intercept AuthorizationActivity.onCreate() and immediately return RESULT_OK
     * so the SDK treats the grant as approved without showing any UI to the user.
     * The SSO layer handles actual token provisioning internally after the activity
     * returns — we only need to signal consent.
     *
     * @param authorizationActivity the intercepted AuthorizationActivity instance.
     */
    public static void autoApproveAuthorization(android.app.Activity authorizationActivity) {
        try {
            String callingPackage = authorizationActivity.getCallingPackage();
            Logger.printInfo(() -> "autoApproveAuthorization: auto-approving SSO request" +
                    (callingPackage != null ? " from " + callingPackage : ""));
            // RESULT_OK signals approval to the requesting SDK.
            authorizationActivity.setResult(android.app.Activity.RESULT_OK);
            authorizationActivity.finish();
            Logger.printInfo(() -> "autoApproveAuthorization: AuthorizationActivity finished silently");
        } catch (Exception ex) {
            Logger.printException(() -> "autoApproveAuthorization failure", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Null-safe field clearer. Ignores NoSuchFieldError for cross-version safety. */
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

    /**
     * Injection point. Remove ads sections from home.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeHomeSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from home");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "featureTypeCase_"),
                REMOVED_HOME_SECTIONS
        );
    }

    /**
     * Injection point. Remove ads sections from browse.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeBrowseSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from browse");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "sectionTypeCase_"),
                REMOVED_BROWSE_SECTIONS
        );
    }
}

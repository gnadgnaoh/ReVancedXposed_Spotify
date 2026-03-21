/*
 * Custom changes:
 * Wipe stubbed types: REMOVED_HOME_SECTIONS, overrideAttributes, removeHomeSections
 * Fix for Spotify 9.1.32.2083:
 *   - Added on-demand-restricted=false, ad-based-on-demand=false to prevent FORCED_LOGOUT
 *   - Added clearPlayerRestrictions() to fix disallowSeekingReasons_ (seek bar locked)
 *   - Added clearPlayerRestrictions() to fix disallowTogglingShuffleReasons_ (shuffle grayed out)
 * */
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
        /**
         * Account attribute key.
         */
        final String key;

        /**
         * Override value.
         */
        final Object overrideValue;

        /**
         * If this attribute is expected to be present in all situations.
         * If false, then no error is raised if the attribute is missing.
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
            // allowing to pick any of the other modes. Flag is not present in legacy app target.
            new OverrideAttribute("pick-and-shuffle", FALSE),
            // Disables shuffle-mode streaming-rule, which forces songs to be played shuffled
            // and breaks the player when other patches are applied.
            new OverrideAttribute("streaming-rules", ""),
            // Enables premium UI in settings and removes the premium button in the nav-bar.
            new OverrideAttribute("nft-disabled", "1"),
            // Enable Spotify Connect and disable other premium related UI, like buying premium.
            // It also removes the download button.
            new OverrideAttribute("type", "premium"),
            // Enable Spotify Car Thing hardware device.
            // Device is discontinued and no longer works with the latest releases,
            // but it might still work with older app targets.
            new OverrideAttribute("can_use_superbird", TRUE, false),
            // Removes the premium button in the nav-bar for tablet users.
            new OverrideAttribute("tablet-free", FALSE, false),
            // Fix for 9.1.32: Prevents FORCED_LOGOUT triggered by server detecting
            // free-tier account streaming without ads. Found in classes5.dex.
            new OverrideAttribute("on-demand-restricted", FALSE, false),
            // Fix for 9.1.32: Disables ad-based on-demand mode check that causes
            // session invalidation. Found in classes8.dex.
            new OverrideAttribute("ad-based-on-demand", FALSE, false)
    );

    /**
     * A list of home sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers home sections.
     */
    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.home.evopage.homeapi.proto.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.home.evopage.homeapi.proto.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    /**
     * A list of browse sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers browse sections.
     */
    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    /**
     * Injection point. Override account attributes.
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
                Object originalValue;
                originalValue = XposedHelpers.getObjectField(attribute, "value_");

                if (overrideValue.equals(originalValue)) {
                    continue;
                }

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
     * Injection point. Clear player restrictions set by server for free accounts.
     * <p>
     * Spotify 9.1.32 sends a Restrictions protobuf alongside each player state update.
     * This object contains fields like disallowSeekingReasons_ and
     * disallowTogglingShuffleReasons_ which lock the seek bar and gray out the
     * shuffle button at the UI layer — independently of the account attribute overrides.
     * <p>
     * We clear these fields after the Restrictions object is built so the player UI
     * treats the session as fully unrestricted.
     * <p>
     * Field names confirmed present in classes7.dex:
     *   disallowSeekingReasons_              → seek bar locked
     *   disallowTogglingShuffleReasons_      → shuffle button grayed out
     *   disallowSkippingNextReasons_         → skip next disabled
     *   disallowSkippingPrevReasons_         → skip prev disabled
     *   allowSeeking_                        → explicit allow flag (set to true)
     */
    public static void clearPlayerRestrictions(Object restrictions) {
        try {
            // Fix: seek bar locked — clear the list of reasons that disable seeking.
            clearField(restrictions, "disallowSeekingReasons_");

            // Fix: shuffle button grayed out — clear the list of reasons disabling shuffle toggle.
            clearField(restrictions, "disallowTogglingShuffleReasons_");

            // Extra: clear skip restrictions so previous/next track works freely.
            clearField(restrictions, "disallowSkippingNextReasons_");
            clearField(restrictions, "disallowSkippingPrevReasons_");

            // Extra: clear pause restriction (sometimes set for free radio playback).
            clearField(restrictions, "disallowPausingReasons_");

            // Extra: explicitly enable seeking if the allowSeeking_ boolean field exists.
            try {
                XposedHelpers.setBooleanField(restrictions, "allowSeeking_", true);
            } catch (NoSuchFieldError ignored) {
                // Field may not exist in all versions — safe to ignore.
            }

            Logger.printInfo(() -> "clearPlayerRestrictions: seek + shuffle + skip unlocked");
        } catch (Exception ex) {
            Logger.printException(() -> "clearPlayerRestrictions failure", ex);
        }
    }

    /**
     * Null-safe helper: sets a List/Object field to null to effectively clear it.
     * Silently ignores NoSuchFieldError so the hook survives minor protobuf refactors.
     */
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

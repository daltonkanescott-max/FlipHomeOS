# FlipHome OS research notes

Research date: 2026-09-28
Target device: Samsung Galaxy Z Flip5

This document records the engineering lessons used before writing v0.1. It is intentionally focused on failure modes rather than feature marketing.

## 1. Native Flex Window registration is the correct Flip5 entry point

Samsung officially documents custom app widgets for the Flip5 Flex Window. The important pieces are:

- a normal `AppWidgetProvider`,
- `widgetCategory="keyguard"`,
- the documented Flex Window minimum dimensions,
- and Samsung metadata whose XML contains `display="sub_screen"`.

Source:
https://developer.samsung.com/galaxy-z/flex_window.html

The small `revoverflow/flipbase` project independently demonstrates the same structure in a working Flip5-oriented project. Its manifest has both normal Android widget metadata and `com.samsung.android.appwidget.provider` metadata pointing at a Samsung cover XML file.

Source:
https://github.com/revoverflow/flipbase

**Decision:** FlipHome OS uses this official/native path rather than spoofing a Samsung package or depending on Good Lock/MultiStar internals.

## 2. Do not repeat the old SamSprung whitelist/package exploit

The older SamSprung widget documented several firmware-dependent limitations: Samsung package/signature whitelisting, Samsung Health package conflicts, lock-screen restrictions, keyboard limitations, and breakage on newer firmware/Android versions.

Source:
https://github.com/SamSprung/SamSprung-Widget

That was clever for older Flips, but the Flip5 gives us an official widget mechanism.

**Decision:** no Samsung package-name spoofing and no requirement to uninstall Samsung Health.

## 3. A RemoteViews widget should be an entry point, not the full editable OS

Android home/lock widgets are based on `RemoteViews`. They do not behave like a normal arbitrary View hierarchy and cannot simply host another app's full interactive widget tree.

The FlipWidgets experiment explicitly moved to a hybrid design: a cover widget/preview launches a normal Activity containing an `AppWidgetHost` for real interactive widgets.

Source:
https://github.com/Gh0strab/FlipWidgets

**Decision:** v0.1 uses the Samsung cover widget as the native launch surface and a real Activity as the editable home screen. If third-party widgets are added later, they should be hosted in the Activity with `AppWidgetHost` rather than nested into RemoteViews.

## 4. Keyguard + overlay timing is a real Flip5 failure mode

A 2026 launcher developer received multiple real-Flip5 reports that a floating overlay appeared once, then disappeared after the cover screen slept/woke. The root cause they reported was:

- overlay creation could fail while keyguard was active,
- their retry logic stopped as soon as keyguard cleared,
- if creation failed on that one post-unlock attempt, it never retried,
- Samsung Remote Test Lab did not reproduce the problem because the test device did not have the same lock-screen path.

Reddit discussion:
https://www.reddit.com/r/galaxyzflip/comments/1vvlitv/i_got_tired_of_unfolding_my_flip_just_to_check/

**Decision:** v0.1 does not require an overlay at all. If an overlay is added later, creation must be state-driven and success-driven: wait until keyguard permits it, attempt creation, verify the view was actually attached, and continue retrying with bounded backoff until success or the cover session ends.

## 5. Never keep user layout only in service memory

The same 2026 launcher had a floating control jump back to its default location whenever the background service was recreated because its location only lived in memory.

Reddit discussion:
https://www.reddit.com/r/galaxyzflip/comments/1vvlitv/i_got_tired_of_unfolding_my_flip_just_to_check/

**Decision:** app order, column count, wallpaper URI, and dim level are written to persistent preferences. Future overlay position/page/folder state should follow the same rule.

## 6. Gesture layers can steal touches from widgets and keyboards

Reports from current cover launchers describe:

- vertical launcher gestures preventing a scrollable calendar widget from receiving its own vertical scroll,
- side-panel touch areas interfering with keyboard keys near the edge,
- mitigations such as shrinking the touch region or hiding overlays while a keyboard is active.

Reddit discussion:
https://www.reddit.com/r/galaxyzflip/comments/1vvlitv/i_got_tired_of_unfolding_my_flip_just_to_check/

**Decision:** no global gesture interception in v0.1. When pages/edge panels are added, child content should get first chance at touch handling, and global gestures should use deliberately small edge zones instead of the whole screen.

## 7. Cover-screen keyboards remain inconsistent

SamSprung's legacy documentation called out Samsung's secondary IME restrictions. Other apps have reported keyboards not opening on Z Flip cover screens, and 2026 users still report version-dependent behavior across One UI releases.

Sources:
https://github.com/SamSprung/SamSprung-Widget
https://github.com/BlueBubblesApp/bluebubbles-app/issues/2529

**Decision:** v0.1 never requires keyboard input on the cover screen to remain usable. App selection/search is available when the phone is open. We can test current Samsung Keyboard behavior on the user's exact Flip5/One UI build later.

## 8. Avoid assuming a fixed display ID

Some cover-related projects use a hard-coded secondary display ID such as `1`, but foldable behavior is not universal. Other foldable experiments have observed the same display ID in contexts where geometry/aspect ratio was the more reliable discriminator.

Source:
https://github.com/evrc/fold-ambient

**Decision:** launching from the native cover widget should preserve the current display context. v0.1 does not force `launchDisplayId = 1`. Screen geometry/cutout insets are used for layout rather than treating display ID as the core device-state signal.

## 9. Do not hard-code the Flip5 camera/cutout shape into layout math

The Flip5 Flex Window has unusual geometry. Hard-coded pixel assumptions make later firmware/device support brittle.

**Decision:** the home activity uses Android display-cutout insets and responsive grid columns. The documented 352 x 339 dp numbers are used where Samsung requires them for widget metadata, not as permanent Activity layout coordinates.

## 10. Wallpapers should be app-local first

Trying to change Samsung's actual system cover wallpaper from a normal third-party app is not a clean, documented general API. Current launcher-style apps instead render their own wallpaper inside their cover UI.

A 2026 cover utility advertises custom cover wallpapers/themes as part of its own launcher experience, and users specifically requested dimming because bright wallpaper reduced readability.

Reddit discussions:
https://www.reddit.com/r/galaxyzflip/comments/1vsaezj/i_bought_a_galaxy_z_flip_5_with_a_broken_inner/
https://www.reddit.com/r/galaxyzflip/comments/1vvlitv/i_got_tired_of_unfolding_my_flip_just_to_check/

**Decision:** FlipHome OS stores a persistable URI from Android's document picker and renders it behind the launcher grid. No storage permission is required. A 0-70% dim layer is included from v0.1.

## 11. Keep permissions narrow

Some launcher projects request broad package visibility, accessibility, overlay, or usage access because they need Recents/global navigation/features outside their own Activity.

For our first milestone, listing launchable applications can be done with a `<queries>` declaration for `ACTION_MAIN` + `CATEGORY_LAUNCHER` rather than asking for broad `QUERY_ALL_PACKAGES` visibility.

**Decision:** v0.1 has no dangerous runtime permissions and no special-access permissions. Add each privileged capability only when a feature actually requires it.

## 12. What we should test first on the real Flip5

1. Does FlipHome OS appear under Settings > Cover screen > Widgets?
2. Does tapping the widget launch `MainActivity` on the Flex Window?
3. Does the activity survive screen off/on after fingerprint/PIN use?
4. Do launched apps stay on the cover display?
5. Which apps refuse to render or jump to the inner display?
6. Does long-press Edit Mode work reliably at cover-screen scale?
7. Does wallpaper persistence survive reboot/app process death?
8. Does the camera/cutout overlap any controls on this exact One UI build?

These answers should drive v0.2. Adding overlays, Recents, widgets, notification listeners, and orientation forcing before validating the native path would make debugging much harder.

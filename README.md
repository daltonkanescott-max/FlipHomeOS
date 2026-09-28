# FlipHome OS v0.1

A from-scratch Samsung Galaxy Z Flip cover-screen home experiment. The first target is the Galaxy Z Flip5.

## What v0.1 does

- Registers as a native Samsung Flex Window widget.
- Tapping the Flex Window widget opens a real Android activity on the cover-screen path.
- Shows a clock/date and a launcher-style grid of installed apps.
- Long-press the background or an app to enter Edit Mode.
- Choose which apps appear on the home screen.
- Drag apps to reorder them while Edit Mode is active.
- Choose 3, 4, or 5 grid columns.
- Choose a custom wallpaper using Android's document picker.
- Adjust wallpaper dimming from 0-70% so labels stay readable.
- Persists layout, order, wallpaper URI, grid size, and dim level across process/service restarts.
- Uses display-cutout insets rather than hard-coding the Flip5 camera/cutout geometry.

## Deliberate v0.1 design choices

This version intentionally does **not** use AccessibilityService, draw-over-other-apps, Usage Access, Shizuku, root, or ADB.

The baseline needs to prove that the native Samsung widget -> cover activity -> app launching path is stable on a real Flip5 first. Overlay navigation and Recents can be added later without making the home screen depend on them.

### Why this architecture

Research before implementation showed several recurring problems in existing Flip launchers:

1. **Old Samsung whitelist/package-name tricks were firmware fragile.**
   Older SamSprung builds depended on Samsung widget whitelisting/package behavior and broke when Samsung changed firmware checks. Flip5 supports the official `display="sub_screen"` Flex Window metadata, so this project uses that instead.

2. **RemoteViews is not a full launcher surface.**
   Android widgets use `RemoteViews`; they are useful as the native cover-screen entry point, but not as a general-purpose interactive home environment. The actual editable home is a normal Activity.

3. **Overlay lifecycle races on real Flip5 hardware.**
   A 2026 cover launcher documented a Flip5 failure where an overlay could not be created while keyguard was active. A single retry after unlock could fail and then never retry again. Remote Test Lab did not reproduce it because the lab device had no real lock screen. v0.1 therefore has no required overlay service.

4. **In-memory UI state gets lost.**
   Another reported bug reset a floating control to its default position whenever its background service was recreated because position was only stored in memory. All user layout state in FlipHome OS is persisted immediately.

5. **Global gesture interception can break widgets and keyboards.**
   Cover launchers have reported edge/slide gesture regions stealing touches from keyboards and vertically scrollable widgets. v0.1 does not install a global gesture layer.

6. **Cover keyboards are inconsistent across One UI versions.**
   Samsung's secondary-display IME behavior has changed across generations/firmware. v0.1 does not depend on typing on the cover screen. Configuration can always be done with the phone open.

## Samsung Flex Window integration

The project follows Samsung's documented Flip5 widget requirements:

`res/xml/cover_widget_info.xml`

- `android:minWidth="352dp"`
- `android:minHeight="339dp"`
- `android:resizeMode="horizontal|vertical"`
- `android:widgetCategory="keyguard"`

`res/xml/samsung_cover_widget_info.xml`

```xml
<samsung-appwidget-provider display="sub_screen" />
```

The receiver references both metadata files in `AndroidManifest.xml`.

## Building

### Android Studio

1. Install current Android Studio.
2. Open this folder as a project.
3. Make sure Android SDK 35 is installed.
4. Use JDK 17.
5. Sync Gradle.
6. Build > Build APK(s).
7. Install `app-debug.apk` on the Flip5.

If your machine does not have Gradle available separately, Android Studio can create a Gradle wrapper from the project. The included GitHub Actions workflow does not rely on a committed wrapper JAR.

### GitHub Actions

Push the project to a GitHub repository and run **Build Android APK** from Actions. The workflow installs Gradle 8.9 and builds `app-debug.apk`.

## Installing / testing on a Galaxy Z Flip5

1. Install the APK.
2. Open **FlipHome OS** once with the phone unfolded.
3. Customize your apps/wallpaper if desired.
4. Open Samsung **Settings > Cover screen > Widgets**.
5. Enable/add **FlipHome OS**.
6. Fold the phone and swipe to the widget.
7. Tap the widget to open the editable cover home.
8. Long-press the background to enter Edit Mode.

If the widget does not appear after first install, toggle the widget off/on or reboot once. Samsung/MultiStar users have reported stale cover-widget registration state after updates; the application itself does not use MultiStar.

## Important v0.1 limitation

The Samsung Flex Window is still controlled by System UI. FlipHome OS is not replacing Samsung's system launcher. It is a native cover widget that opens a launcher-style Activity. This is intentional because it is the least fragile approach available to a normal APK.

Some third-party apps may still:

- refuse to render correctly at the cover-screen aspect ratio,
- rotate incorrectly,
- display a permission dialog only on the inner screen,
- require the device to be unlocked first,
- or be restricted by Samsung System UI.

Those are app/System UI constraints rather than something an ordinary launcher APK can universally override.

## Next development milestones after Flip5 testing

- Native app icons directly inside the Flex Window widget so common apps can launch without the extra tap.
- Multiple home pages.
- Folders.
- Icon-size and label visibility controls.
- Optional widgets via `AppWidgetHost`.
- Optional Recents/app switcher.
- Optional overlay navigation implemented with keyguard-aware retries and persisted position.
- Notification dots/listener.
- Per-app orientation rules.
- Wallpaper crop/position controls and optional blur.

## Research references

- Samsung Flex Window documentation: https://developer.samsung.com/galaxy-z/flex_window.html
- Samsung screen-size / Good Lock development notes: https://developer.samsung.com/sdp/blog/en/2024/01/09/best-practices-of-app-development-for-various-screen-sizespowered-by-good-lock
- Flip5 native widget base: https://github.com/revoverflow/flipbase
- SamSprung legacy widget: https://github.com/SamSprung/SamSprung-Widget
- SamSprung TooUI: https://github.com/SamSprung/SamSprung-TooUI
- FlipWidgets hybrid AppWidgetHost experiment: https://github.com/Gh0strab/FlipWidgets

The code in this repository was written from scratch; the projects above were used to understand platform behavior, failure modes, and architecture choices.

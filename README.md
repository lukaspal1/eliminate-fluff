# eliminate-fluff

An Android app that stops you from scrolling **Instagram Reels** and **YouTube Shorts**.

It runs as an Accessibility Service:

- **One at a time is fine.** Open a Reel or Short from your feed, a DM or Explore and watch it.
- **Swiping to the next one sends you back** to wherever you came from.
- **The Reels and Shorts tabs are blocked.** You can still see the button, but tapping it does nothing.
- **Instagram's Explore grid is covered up.** The search bar still works, so you can look
  people up and visit their profiles.

The rest of Instagram and YouTube (DMs, posts, normal videos, search) keeps working.

It can also put **time limits** on apps you pick (Instagram, YouTube and Reddit by default).
Turn each one on or off and set its minutes in the app:

- **Daily limit:** how long you can use all the limited apps together per day, e.g.
  2 hours across Instagram, YouTube and Reddit. Resets at midnight.
- **Time in a row:** how long you can use an app before it's locked for a cooldown, e.g.
  20 minutes and then locked for an hour. Leaving the app for less than the cooldown
  doesn't reset the count.

You get a warning a minute before a limit, and once it runs out the app sends you to the
home screen. The app sends nothing off the phone.

## Install it on your phone

1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
   Let it finish syncing; it downloads the Android SDK and Gradle on its own.
2. On your phone, turn on **Developer options** (Settings → About phone → tap
   *Build number* 7 times), then turn on **USB debugging**.
3. Plug the phone in, choose it in the device dropdown in Android Studio and press **Run ▶**.
4. Open **Eliminate Fluff** on the phone and tap **Turn on in Accessibility settings** →
   *Installed apps* → *Eliminate Fluff blocker* → On.
   - If the switch is greyed out ("Restricted setting"): Settings → Apps →
     Eliminate Fluff → ⋮ → **Allow restricted settings**, then try again.
     Android 13+ does this for any app that isn't installed from the Play Store.

## When an app update breaks it

Detection looks for internal view ids of the Reels and Shorts player, its swiping list
and the bottom-bar tab (`TARGETS` in
[FluffBlockerService.kt](app/src/main/java/com/eliminatefluff/FluffBlockerService.kt)).
Instagram and YouTube rename these now and then. To find the new ones:

1. Run the debug build from Android Studio and open a Reel or Short.
2. In **Logcat**, filter by the tag `FluffBlocker`. Each screen change logs every view id on
   screen, and each swipe logs `Scrolled: <id>` (that id belongs in `pagerIds`).
3. Pick an id that shows up in the Reels/Shorts player but not on the home feed, and add it
   to the list (without the `com.instagram.android:id/` prefix).

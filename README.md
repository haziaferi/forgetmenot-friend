# forgetmenot

An offline-first Android app for keeping in touch with the people you care about. Add someone,
say roughly how often you want to reach out, and tap once each time you do. The app sorts
everyone into who has been a while, who is coming up and who you spoke to recently, and can
nudge you with a reminder or a home-screen widget.

- **No network.** The app declares no `INTERNET` permission, so it cannot open a connection.
  There is no account, server, analytics or crash reporting.
- **Your data, in a folder you pick.** Every friend, tag and group is a small JSON file in a
  folder you choose through Android's folder picker. Point it at a folder a sync app already
  watches (Syncthing, for example) to share the same data across devices.
- Native Kotlin and Jetpack Compose, Glance widgets, Android 11 (API 30) and up.

## Building

Needs JDK 21 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `android/local.properties`).

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```

The debug APK lands in `android/app/build/outputs/apk/debug/`. A release build needs your own
signing key in `android/keystore.properties`; section 8 of [SPEC.md](SPEC.md) explains how to
make one.

## Documentation

[SPEC.md](SPEC.md) is the specification: the data model and sync-folder layout, conflict
handling, reminders, widgets, theming and accessibility, and how the app is built. Its citations
of the code are checked by the unit tests.

## License

The app is licensed under the GNU General Public License v3.0, see [LICENSE](LICENSE).
The bundled fonts, Fredoka and Nunito Sans, are under the SIL Open Font License 1.1; their
license texts are in `android/app/src/main/assets/licenses/`.

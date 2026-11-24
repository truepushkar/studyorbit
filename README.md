# StudyOrbit

StudyOrbit is an offline Android app for focus sessions and distraction blocking. It uses Kotlin and Jetpack Compose and does not require an account.

- Run a countdown, stopwatch, or Pomodoro cycle with focus sounds and timer notifications.
- Set app limits, block short-video feeds, or keep a list of websites and YouTube channels for studying.
- Plan one-time or repeating study sessions, and review study history and screen time.

Android Accessibility is required for blocking. Usage Access is required for app limits and screen-time reports. Website and short-video detection depends on what the browser or app exposes to Android.

## Build

Use JDK 17 and Android SDK Platform 35. The app supports Android 8.0 and later.

| Build | macOS / Linux | Windows |
| --- | --- | --- |
| Debug | `./gradlew assembleDebug` | `.\gradlew.bat assembleDebug` |
| Optimized for local install | `./gradlew assembleOptimized` | `.\gradlew.bat assembleOptimized` |

The optimized build uses R8 and the local Android debug signing key.

The bundled adult-domain list comes from [The Block List Project](https://github.com/blocklistproject/Lists), which releases it under [the Unlicense](https://github.com/blocklistproject/Lists/blob/main/LICENSE).

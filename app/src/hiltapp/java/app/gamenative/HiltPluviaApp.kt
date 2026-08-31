package app.gamenative

import dagger.hilt.android.HiltAndroidApp

/**
 * The application class the standalone gamenative build actually runs
 * (declared in the manifest). Split from [PluviaApp] because Hilt
 * hard-errors on @HiltAndroidApp in a non-application module, and
 * droidtop consumes the main source tree as a library -- this file
 * lives in its own source root (src/hiltapp/) that only this project's
 * application build includes, so both worlds get exactly the class
 * they can legally have.
 */
@HiltAndroidApp
class HiltPluviaApp : PluviaApp()

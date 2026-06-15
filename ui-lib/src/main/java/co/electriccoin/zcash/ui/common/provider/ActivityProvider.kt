package co.electriccoin.zcash.ui.common.provider

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the current foreground [Activity] so app-singleton components (e.g. BiometricRepository) can
 * launch an Activity in the SAME task instead of via the application [android.content.Context].
 *
 * Launching from the application context forces `FLAG_ACTIVITY_NEW_TASK`; combined with the biometric
 * host activity's translucent theme, that opens the prompt in a fresh task with nothing behind it and
 * composites onto BLACK — the "dark screen on Send/confirm" the user reported. Launching from the
 * current Activity keeps the prompt over the app's content (no new task, no black void).
 *
 * Register once from the Application: `registerActivityLifecycleCallbacks(ActivityProvider)`.
 * The reference is weak, so it never leaks an Activity.
 */
object ActivityProvider : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var current: WeakReference<Activity>? = null

    /** The current resumed Activity, or null when the app is backgrounded. */
    fun getActivity(): Activity? = current?.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // Only clear if it's still the one we hold — a fast resume of the next Activity may have
        // already replaced it.
        if (current?.get() === activity) current = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

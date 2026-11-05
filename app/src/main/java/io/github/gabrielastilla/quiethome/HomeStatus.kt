package io.github.gabrielastilla.quiethome

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * What the Home button will actually open, measured rather than assumed.
 *
 * On Google TV the "default Home app" setting (Android's Home role) is not the whole story: Google TV's own
 * launcher declares a higher-priority Home entry, and while it is enabled the system's Home lookup keeps
 * resolving to it even when another app holds the Home role. Verified on the Google TV image (Android 14):
 * role held by Quiet Home, yet `resolve-activity HOME` → launcherx and the Home key opened Google TV.
 */
object HomeStatus {

    /** Google TV's launcher package (stock Google TV; TCL ships the same). */
    const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"

    enum class State {
        /** The Home button opens Quiet Home. */
        ACTIVE,
        /** Quiet Home holds the Home role, but Google TV's launcher still takes the Home button. */
        ROLE_HELD_OVERRIDDEN,
        /** Another app is the Home app. */
        NOT_DEFAULT,
    }

    data class Report(
        val state: State,
        /** Package the Home intent resolves to right now ("android" = the system chooser). */
        val resolvedPackage: String?,
        val roleHeld: Boolean,
        val googleTvLauncherEnabled: Boolean,
        val canRequestRole: Boolean,
    )

    fun check(ctx: Context): Report {
        val pm = ctx.packageManager
        val resolved = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val role = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
        val roleHeld = role?.let { it.isRoleAvailable(RoleManager.ROLE_HOME) && it.isRoleHeld(RoleManager.ROLE_HOME) } ?: false
        val canRequest = role?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roleHeld
        val gtvEnabled = try {
            pm.getApplicationInfo(GOOGLE_TV_LAUNCHER, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val state = when {
            resolved == ctx.packageName -> State.ACTIVE
            roleHeld -> State.ROLE_HELD_OVERRIDDEN
            else -> State.NOT_DEFAULT
        }
        return Report(state, resolved, roleHeld, gtvEnabled, canRequest)
    }

    fun isActive(ctx: Context) = check(ctx).state == State.ACTIVE

    /** System dialog asking to make Quiet Home the default Home app (Android 10+), if the firmware offers it. */
    fun requestRoleIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_HOME) || rm.isRoleHeld(RoleManager.ROLE_HOME)) return null
        val i = rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        return if (i.resolveActivity(ctx.packageManager) != null) i else null
    }

    fun summary(r: Report): String = when (r.state) {
        State.ACTIVE -> "Opens Quiet Home"
        State.ROLE_HELD_OVERRIDDEN -> "Default, but Google TV overrides"
        State.NOT_DEFAULT -> "Opens " + when (r.resolvedPackage) {
            GOOGLE_TV_LAUNCHER -> "Google TV"
            "android", null -> "a chooser"
            else -> r.resolvedPackage
        }
    }
}

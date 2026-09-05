package dev.cockpit.mobile

import android.app.Application
import dev.cockpit.platform.android.CockpitProcessComponent

class CockpitApplication : Application() {
    val processComponent by lazy { CockpitProcessComponent(this) }
}

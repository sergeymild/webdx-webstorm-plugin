package com.intch.cssmodules

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

private fun marker(name: String, msg: String) {
    runCatching {
        File("/tmp/css-scoped-$name.txt").writeText(msg)
    }
    logger<CssScopedStartup>().warn("[CSS-SCOPED] $msg")
}

/** Project-open hook (registered via <postStartupActivity>). */
class CssScopedStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        marker("startup", "PLUGIN ACTIVE v1.0.5 (postStartupActivity) project='${project.name}'")
    }
}

/** App-lifecycle hook (registered via <applicationListeners>, independent of <extensions>). */
class CssScopedAppListener : AppLifecycleListener {
    override fun appStarted() {
        marker("app", "PLUGIN ACTIVE v1.0.5 (AppLifecycleListener.appStarted)")
    }
}

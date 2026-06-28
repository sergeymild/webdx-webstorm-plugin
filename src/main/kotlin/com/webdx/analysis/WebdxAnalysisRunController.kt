package com.webdx.analysis

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Owns a single project-wide analysis run at a time, exposing a Stop handle and a running flag.
 *
 * The run itself goes through the platform's [GlobalInspectionContextImpl.doInspections], which is
 * the same entry point the IDE's own "Inspect Code" uses: it scans the whole scope and opens the
 * standard Inspection Results window (or a "no problems found" notification). We do NOT reimplement
 * that pipeline — an earlier attempt that called `performInspectionsWithProgress` directly returned
 * instantly without scanning anything.
 *
 * Because `doInspections` is fire-and-forget (it launches its own cancellable background task), we
 * recover a handle to that task's [ProgressIndicator] from the context to drive the Stop button and
 * to detect completion (so the panel can re-enable its buttons). [onStateChange] fires on the EDT
 * whenever [isRunning] flips.
 */
class WebdxAnalysisRunController(private val project: Project) {

    private val log = logger<WebdxAnalysisRunController>()

    /** Polls (on the EDT) for the run's indicator and its completion. */
    private val watcher by lazy { Alarm(Alarm.ThreadToUse.SWING_THREAD, project) }

    @Volatile
    private var running = false

    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var runningContext: GlobalInspectionContextImpl? = null

    @Volatile
    private var liveIndicator: ProgressIndicator? = null

    /** Whether a run is currently active. */
    val isRunning: Boolean
        get() = running

    /** Label of the analysis currently running, or null when idle. */
    val runningTitle: String?
        get() = currentTitle

    /** Invoked on the EDT after [isRunning] changes. The panel uses it to refresh button state. */
    var onStateChange: (() -> Unit)? = null

    /**
     * Start a project-wide run enabling the inspections matched by [selector] (default: all WebDX
     * inspections). No-op if a run is already active or the project is still indexing.
     *
     * Must be called on the EDT (button handlers are). [title] appears in the progress UI.
     */
    fun run(title: String, selector: (String) -> Boolean = { true }) {
        if (running) return
        if (DumbService.getInstance(project).isDumb) {
            notify("Wait for project indexing to finish and try again.", NotificationType.WARNING)
            return
        }

        val profile = WebdxInspectionRunner.buildProfile(project, selector)
        val enabledCount = profile.allTools.count { it.isEnabled }
        log.info("[WEBDX-ANALYSIS] run requested: '$title', enabled inspection(s)=$enabledCount")
        if (enabledCount == 0) {
            notify("No matching inspections found for \"$title\".", NotificationType.WARNING)
            return
        }

        val managerEx = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = managerEx.createNewGlobalContext()
        context.setExternalProfile(profile)
        val scope = AnalysisScope(project)

        running = true
        currentTitle = title
        runningContext = context
        liveIndicator = null
        fireStateChange()

        // Proven path: scans the whole scope and opens the Inspection Results window itself.
        context.doInspections(scope)
        startWatcher(context, title)
    }

    /** Cancel the active run, if any. The Inspection Results window keeps whatever was found so far. */
    fun stop() {
        val indicator = liveIndicator ?: runningContext?.let { readIndicator(it) }
        if (indicator == null) {
            log.info("[WEBDX-ANALYSIS] stop requested but no live indicator yet")
            return
        }
        log.info("[WEBDX-ANALYSIS] stopping '${currentTitle}'")
        indicator.cancel()
    }

    /**
     * Polls for the background task's indicator (which `doInspections` sets a moment after it
     * returns) so Stop can cancel it, and resets the panel once the run has started and then stopped.
     */
    private fun startWatcher(context: GlobalInspectionContextImpl, title: String) {
        watcher.cancelAllRequests()
        var sawRunning = false
        var ticks = 0
        lateinit var tick: () -> Unit
        tick = {
            ticks++
            val indicator = readIndicator(context)
            if (indicator != null) liveIndicator = indicator
            if (indicator != null && indicator.isRunning) sawRunning = true

            // Done once the run we saw start has stopped, or after a generous safety timeout
            // (~10 min at 100ms) so the UI never stays stuck disabled.
            val finished = (sawRunning && (indicator == null || !indicator.isRunning)) || ticks > 6000
            if (finished) {
                finishRun(title)
            } else {
                watcher.addRequest(tick, 100)
            }
        }
        watcher.addRequest(tick, 100)
    }

    private fun finishRun(title: String) {
        log.info("[WEBDX-ANALYSIS] '$title' finished")
        running = false
        currentTitle = null
        runningContext = null
        liveIndicator = null
        fireStateChange()
    }

    /** Reads the context's current [ProgressIndicator] (protected field, no public accessor). */
    private fun readIndicator(context: GlobalInspectionContextImpl): ProgressIndicator? = try {
        val field = Class.forName("com.intellij.codeInspection.ex.GlobalInspectionContextBase")
            .getDeclaredField("myProgressIndicator")
        field.isAccessible = true
        field.get(context) as? ProgressIndicator
    } catch (e: Throwable) {
        log.warn("[WEBDX-ANALYSIS] cannot read the run's progress indicator", e)
        null
    }

    private fun fireStateChange() {
        ApplicationManager.getApplication().invokeLater { onStateChange?.invoke() }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("WebDX Analysis")
            .createNotification(content, type)
            .notify(project)
    }
}

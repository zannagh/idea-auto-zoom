package de.zannagh.idea.autozoom

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

@Service(Service.Level.PROJECT)
class AutoZoomService(private val project: Project) : Disposable {

    private var currentDisplayId: String? = null
    private var isApplyingZoom = false
    private var componentListener: ComponentAdapter? = null
    private var focusListener: WindowFocusListener? = null

    fun startTracking() {
        val frame = WindowManager.getInstance().getFrame(project) ?: return

        componentListener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                checkDisplayChanged(e.component as? Frame ?: return)
            }
        }
        frame.addComponentListener(componentListener)

        focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) {
                checkDisplayChanged(e.window as? Frame ?: return)
            }

            override fun windowLostFocus(e: WindowEvent) {}
        }
        frame.addWindowFocusListener(focusListener)

        // Listen for user-initiated zoom changes to auto-save
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(UISettingsListener.TOPIC, UISettingsListener { onZoomChanged() })

        // Detect initial display and apply saved zoom
        currentDisplayId = getDisplayId(frame)
        applyZoomForCurrentDisplay()
    }

    private fun checkDisplayChanged(frame: Frame) {
        val newDisplayId = getDisplayId(frame)
        if (newDisplayId != currentDisplayId) {
            LOG.info("Display changed: $currentDisplayId -> $newDisplayId")
            currentDisplayId = newDisplayId
            ensureDisplayNameExists(newDisplayId)
            applyZoomForCurrentDisplay()
        }
    }

    private fun ensureDisplayNameExists(displayId: String) {
        val settings = AutoZoomSettings.getInstance()
        if (!settings.state.displayNameMap.containsKey(displayId)) {
            settings.setDisplayName(displayId, AutoZoomSettings.generateDefaultName(displayId))
        }
    }

    private fun applyZoomForCurrentDisplay() {
        val settings = AutoZoomSettings.getInstance()
        if (!settings.isEnabled) return

        val displayId = currentDisplayId ?: return
        val zoom = settings.getZoomForDisplay(displayId) ?: return

        applyIdeScale(zoom)
    }

    private fun onZoomChanged() {
        if (isApplyingZoom) return

        val displayId = currentDisplayId ?: return
        val currentZoom = UISettings.getInstance().currentIdeScale
        LOG.info("Zoom changed by user to $currentZoom, saving for display $displayId")
        AutoZoomSettings.getInstance().setZoomForDisplay(displayId, currentZoom)
    }

    private fun applyIdeScale(zoom: Float) {
        val uiSettings = UISettings.getInstance()
        val currentPercent = (uiSettings.currentIdeScale * 100).toInt()
        val targetPercent = (zoom * 100).toInt()
        if (currentPercent == targetPercent) return

        LOG.info("Applying zoom ${targetPercent}% (was ${currentPercent}%)")
        isApplyingZoom = true
        try {
            uiSettings.currentIdeScale = zoom
            uiSettings.fireUISettingsChanged()
        } finally {
            isApplyingZoom = false
        }
    }

    /**
     * Called from settings UI to apply a zoom value for the current display immediately.
     */
    fun applyZoomFromSettings() {
        applyZoomForCurrentDisplay()
    }

    override fun dispose() {
        val frame = WindowManager.getInstance().getFrame(project)
        componentListener?.let { frame?.removeComponentListener(it) }
        focusListener?.let { frame?.removeWindowFocusListener(it) }
        componentListener = null
        focusListener = null
    }

    companion object {
        private val LOG = logger<AutoZoomService>()

        fun getInstance(project: Project): AutoZoomService =
            project.getService(AutoZoomService::class.java)

        fun getDisplayId(frame: Frame): String {
            val gc = frame.graphicsConfiguration ?: return "unknown"
            val bounds = gc.bounds
            val scale = gc.defaultTransform.scaleX
            return "${bounds.width}x${bounds.height}+${bounds.x}+${bounds.y}@${scale}x"
        }
    }
}

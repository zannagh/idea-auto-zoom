package de.zannagh.idea.autozoom

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AutoZoomSettings", storages = [Storage("autoZoom.xml")])
class AutoZoomSettings : PersistentStateComponent<AutoZoomSettings.State> {

    class State {
        /** Maps display ID (e.g. "2560x1440+0+0@2.0x") to IDE zoom scale */
        var displayZoomMap: MutableMap<String, String> = mutableMapOf()
        /** Maps display ID to user-friendly name */
        var displayNameMap: MutableMap<String, String> = mutableMapOf()
        var enabled: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getZoomForDisplay(displayId: String): Float? =
        myState.displayZoomMap[displayId]?.toFloatOrNull()

    fun setZoomForDisplay(displayId: String, zoom: Float) {
        myState.displayZoomMap[displayId] = zoom.toString()
    }

    var isEnabled: Boolean
        get() = myState.enabled
        set(value) {
            myState.enabled = value
        }

    fun getDisplayName(displayId: String): String =
        myState.displayNameMap[displayId] ?: generateDefaultName(displayId)

    fun setDisplayName(displayId: String, name: String) {
        myState.displayNameMap[displayId] = name
    }

    companion object {
        fun getInstance(): AutoZoomSettings =
            ApplicationManager.getApplication().getService(AutoZoomSettings::class.java)

        fun generateDefaultName(displayId: String): String {
            val match = Regex("(\\d+)x(\\d+)\\+([\\-\\d]+)\\+([\\-\\d]+)@([\\d.]+)x")
                .matchEntire(displayId)
            if (match != null) {
                val (w, h, _, _, scale) = match.destructured
                val scaleLabel = if (scale != "1.0") " @${scale}x" else ""
                return "${w}×${h}${scaleLabel}"
            }
            return displayId
        }
    }
}

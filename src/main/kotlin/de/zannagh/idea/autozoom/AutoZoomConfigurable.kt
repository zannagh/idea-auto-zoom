package de.zannagh.idea.autozoom

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.GraphicsEnvironment
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AutoZoomConfigurable : Configurable {

    private data class DisplayEntry(
        val id: String,
        var name: String,
        var zoomPercent: Int,
        val isConnected: Boolean,
    )

    private var mainPanel: JPanel? = null
    private val enabledCheckBox = JBCheckBox("Enable automatic zoom adjustment")
    private val displayComboBox = ComboBox<String>()
    private val nameField = JBTextField()
    private val zoomSpinner = JSpinner(SpinnerNumberModel(100, 25, 400, 5))
    private val removeButton = JButton("Remove")
    private val noDisplaysLabel = JBLabel("No displays recorded yet. Zoom will be saved automatically when you change it.")

    private val entries = mutableListOf<DisplayEntry>()
    private var currentIndex = -1
    private var suppressEvents = false

    override fun getDisplayName(): String = "Auto Zoom"

    override fun createComponent(): JComponent {
        loadEntries()

        displayComboBox.addActionListener {
            if (!suppressEvents) onDisplaySelected()
        }
        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onNameEdited()
            override fun removeUpdate(e: DocumentEvent) = onNameEdited()
            override fun changedUpdate(e: DocumentEvent) = onNameEdited()
        })
        zoomSpinner.addChangeListener {
            if (!suppressEvents && currentIndex in entries.indices) {
                entries[currentIndex].zoomPercent = zoomSpinner.value as Int
                refreshComboBoxItem()
            }
        }
        removeButton.addActionListener { onRemove() }

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(enabledCheckBox)
            .addSeparator()
            .addLabeledComponent(JBLabel("Display:"), displayComboBox)
            .addLabeledComponent(JBLabel("Name:"), nameField)
            .addLabeledComponent(JBLabel("Zoom (%):"), zoomSpinner)
            .addComponent(removeButton)
            .addComponent(noDisplaysLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return mainPanel!!
    }

    // ---- data loading ----

    private fun loadEntries() {
        entries.clear()
        val settings = AutoZoomSettings.getInstance()

        val connectedIds = connectedDisplayIds()
        val allIds = (settings.state.displayZoomMap.keys + connectedIds).distinct()

        for (id in allIds) {
            val zoom = settings.getZoomForDisplay(id) ?: 1.0f
            val name = settings.getDisplayName(id)
            entries.add(DisplayEntry(id, name, (zoom * 100).toInt(), id in connectedIds))
        }
        // Connected displays first, then disconnected
        entries.sortWith(compareByDescending<DisplayEntry> { it.isConnected }.thenBy { it.name })
    }

    private fun connectedDisplayIds(): Set<String> {
        if (GraphicsEnvironment.isHeadless()) return emptySet()
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            ge.screenDevices.mapTo(mutableSetOf()) { device ->
                val bounds = device.defaultConfiguration.bounds
                val scale = device.defaultConfiguration.defaultTransform.scaleX
                "${bounds.width}x${bounds.height}+${bounds.x}+${bounds.y}@${scale}x"
            }
        } catch (_: java.awt.HeadlessException) {
            emptySet()
        }
    }

    // ---- combo box ----

    private fun rebuildComboBox() {
        suppressEvents = true
        val model = DefaultComboBoxModel<String>()
        for (entry in entries) {
            model.addElement(formatItem(entry))
        }
        displayComboBox.model = model
        suppressEvents = false
    }

    private fun refreshComboBoxItem() {
        if (currentIndex !in entries.indices) return
        suppressEvents = true
        (displayComboBox.model as DefaultComboBoxModel<String>)
            .let {
                it.removeElementAt(currentIndex)
                it.insertElementAt(formatItem(entries[currentIndex]), currentIndex)
                displayComboBox.selectedIndex = currentIndex
            }
        suppressEvents = false
    }

    private fun formatItem(entry: DisplayEntry): String {
        val suffix = if (entry.isConnected) "" else " (disconnected)"
        return "${entry.name}: ${entry.zoomPercent}%$suffix"
    }

    // ---- event handlers ----

    private fun onDisplaySelected() {
        val idx = displayComboBox.selectedIndex
        if (idx < 0 || idx >= entries.size) return
        currentIndex = idx
        suppressEvents = true
        nameField.text = entries[idx].name
        zoomSpinner.value = entries[idx].zoomPercent
        suppressEvents = false
        updateFieldsEnabled()
    }

    private fun onNameEdited() {
        if (suppressEvents || currentIndex !in entries.indices) return
        entries[currentIndex].name = nameField.text
        refreshComboBoxItem()
    }

    private fun onRemove() {
        if (currentIndex !in entries.indices) return
        entries.removeAt(currentIndex)
        rebuildComboBox()
        if (entries.isNotEmpty()) {
            displayComboBox.selectedIndex = 0
        } else {
            currentIndex = -1
            suppressEvents = true
            nameField.text = ""
            zoomSpinner.value = 100
            suppressEvents = false
        }
        updateFieldsEnabled()
    }

    private fun updateFieldsEnabled() {
        val hasSelection = entries.isNotEmpty() && currentIndex in entries.indices
        nameField.isEnabled = hasSelection
        zoomSpinner.isEnabled = hasSelection
        removeButton.isEnabled = hasSelection
        displayComboBox.isVisible = hasSelection
        noDisplaysLabel.isVisible = !hasSelection
    }

    // ---- Configurable contract ----

    override fun isModified(): Boolean {
        val settings = AutoZoomSettings.getInstance()
        if (enabledCheckBox.isSelected != settings.isEnabled) return true

        val savedIds = settings.state.displayZoomMap.keys
        val entryIds = entries.map { it.id }.toSet()
        if (savedIds != entryIds) return true

        for (entry in entries) {
            val savedPercent = ((settings.getZoomForDisplay(entry.id) ?: 1.0f) * 100).toInt()
            if (savedPercent != entry.zoomPercent) return true
            if (settings.getDisplayName(entry.id) != entry.name) return true
        }
        return false
    }

    override fun apply() {
        val settings = AutoZoomSettings.getInstance()
        settings.isEnabled = enabledCheckBox.isSelected
        settings.state.displayZoomMap.clear()
        settings.state.displayNameMap.clear()
        for (entry in entries) {
            settings.setZoomForDisplay(entry.id, entry.zoomPercent / 100f)
            settings.setDisplayName(entry.id, entry.name)
        }

        // Apply zoom immediately for all open projects on their current display
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) {
                AutoZoomService.getInstance(project).applyZoomFromSettings()
            }
        }
    }

    override fun reset() {
        val settings = AutoZoomSettings.getInstance()
        enabledCheckBox.isSelected = settings.isEnabled
        loadEntries()
        rebuildComboBox()
        if (entries.isNotEmpty()) {
            displayComboBox.selectedIndex = 0
        }
        updateFieldsEnabled()
    }
}

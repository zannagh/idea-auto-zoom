package de.zannagh.idea.autozoom

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class AutoZoomStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                AutoZoomService.getInstance(project).startTracking()
            }
        }, project.disposed)
    }
}

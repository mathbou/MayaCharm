package settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MayaStubsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationSettings.INSTANCE.checkMissingStubs(project)
    }
}

package resources

import MayaBundle as Loc
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import settings.MayaSdkConfigurable

private const val displayGroup = "MayaReCharm"
private const val titleText = "MayaReCharm"

object MayaNotifications {
    val CONNECTION_REFUSED = Notification(
        displayGroup, titleText,
        Loc.message("mayarecharm.notifications.ConnectionRefused"), NotificationType.ERROR
    )

    val FILE_FAIL = Notification(
        displayGroup, titleText,
        Loc.message("mayarecharm.notifications.FailedToCreateTempFile"), NotificationType.ERROR
    )

    val NO_SDK_SELECTED = Notification(
        displayGroup, titleText,
        Loc.message("mayarecharm.notifications.NoSdkSelected"), NotificationType.ERROR
    )

    val INVALID_SDK_SELECTED = Notification(
        displayGroup, titleText,
        Loc.message("mayarecharm.notifications.InvalidSdkSelected"), NotificationType.ERROR
    )

    fun stubsOperationFailed(reason: String): Notification {
        return Notification(
            displayGroup,
            titleText,
            Loc.message("mayarecharm.notifications.StubsOperationFailed", reason),
            NotificationType.ERROR
        )
    }

    fun stubsMissing(project: Project): Notification {
        return Notification(
            displayGroup,
            titleText,
            Loc.message("mayarecharm.notifications.StubsMissing"),
            NotificationType.WARNING
        ).apply {
            addAction(
                NotificationAction.createSimpleExpiring(
                    Loc.message("mayarecharm.notifications.OpenSettings")
                ) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MayaSdkConfigurable.ID)
                }
            )
        }
    }

    fun stubsReady(stubs: String): Notification {
        return Notification(
            displayGroup,
            titleText,
            Loc.message("mayarecharm.notifications.StubsReady", stubs),
            NotificationType.INFORMATION
        )
    }

}

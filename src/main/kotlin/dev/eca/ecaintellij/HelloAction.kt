package dev.eca.ecaintellij


import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.diagnostic.Logger

class HelloAction : AnAction() {

    private val logger = Logger.getInstance(HelloAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        logger.info("Hello from ECA!")

        Messages.showInfoMessage(
            "Hello from ECA!",
            "ECA Plugin"
        )
    }
}
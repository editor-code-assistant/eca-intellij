package dev.eca.eca_intellij.extension

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(storages = [Storage("Eca.xml")], name = "EcaSettingsState")
class SettingsState : PersistentStateComponent<SettingsState?> {
    var serverPath: String? = null
    var serverArgs: String? = null
    var usageStringFormat: String? = null

    override fun getState(): SettingsState? {
        return this
    }

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun get(): SettingsState = ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}

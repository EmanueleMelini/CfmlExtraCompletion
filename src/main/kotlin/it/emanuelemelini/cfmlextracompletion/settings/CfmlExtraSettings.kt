package it.emanuelemelini.cfmlextracompletion.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker

/**
 * IDE-wide, persisted settings for the plugin's opt-in features. Both default to OFF so behavior only
 * changes when the user asks for it.
 *
 * Implements [ModificationTracker] so caches keyed on it (see CfmlProjectSymbols) invalidate the moment a
 * toggle changes — no restart needed.
 */
@Service(Service.Level.APP)
@State(name = "CfmlExtraCompletionSettings", storages = [Storage("CfmlExtraCompletion.xml")])
class CfmlExtraSettings : PersistentStateComponent<CfmlExtraSettings.State>, ModificationTracker {

    class State {
        /** Resolve ColdFusion mappings (this.mappings in Application.cfc) when following cfinclude paths. */
        var resolveCfcMappings: Boolean = false

        /** Merge Application.cfm/.cfc from every ancestor directory, not just the nearest one. */
        var mergeAncestorApplicationFiles: Boolean = false
    }

    private var state = State()
    private val tracker = SimpleModificationTracker()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
        tracker.incModificationCount()
    }

    override fun getModificationCount(): Long = tracker.modificationCount

    var resolveCfcMappings: Boolean
        get() = state.resolveCfcMappings
        set(value) {
            if (value != state.resolveCfcMappings) {
                state.resolveCfcMappings = value
                tracker.incModificationCount()
            }
        }

    var mergeAncestorApplicationFiles: Boolean
        get() = state.mergeAncestorApplicationFiles
        set(value) {
            if (value != state.mergeAncestorApplicationFiles) {
                state.mergeAncestorApplicationFiles = value
                tracker.incModificationCount()
            }
        }

    companion object {
        fun getInstance(): CfmlExtraSettings =
            ApplicationManager.getApplication().getService(CfmlExtraSettings::class.java)
    }
}

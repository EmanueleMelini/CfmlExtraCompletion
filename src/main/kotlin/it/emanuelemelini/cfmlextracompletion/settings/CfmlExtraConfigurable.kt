package it.emanuelemelini.cfmlextracompletion.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Settings page shown under Settings ▸ Other Settings ▸ Cfml Extra Completion (no groupId => "Other
 * Settings"). Exposes the two opt-in features as checkboxes, both off by default.
 */
class CfmlExtraConfigurable : Configurable {

    private val settings get() = CfmlExtraSettings.getInstance()
    private var panel: DialogPanel? = null

    override fun getDisplayName(): String = "Cfml Extra Completion"

    override fun createComponent(): JComponent {
        val built = panel {
            group("Experimental resolution (opt-in)") {
                row {
                    checkBox("Resolve ColdFusion mappings (this.mappings) when following cfinclude")
                        .bindSelected(settings::resolveCfcMappings)
                }.rowComment(
                    "Reads this.mappings from the nearest Application.cfc so includes like " +
                        "<cfinclude template=\"/alias/file.cfm\"> resolve to the mapped directory.",
                )
                row {
                    checkBox("Merge Application.cfm / Application.cfc from all ancestor directories")
                        .bindSelected(settings::mergeAncestorApplicationFiles)
                }.rowComment(
                    "Also offers symbols from Application files higher up the tree, not just the nearest " +
                        "one (nearest wins on name conflicts).",
                )
            }
        }
        panel = built
        return built
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}

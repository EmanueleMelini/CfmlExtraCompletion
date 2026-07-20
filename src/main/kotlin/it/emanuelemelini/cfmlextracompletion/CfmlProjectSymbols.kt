package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolScope
import it.emanuelemelini.cfmlextracompletion.settings.CfmlExtraSettings

/**
 * Central place that gathers the project-wide symbols visible to a CFML file: page symbols from the
 * nearest Application.cfm, application-/this-scope symbols from the nearest Application.cfc, and symbols
 * from the file's cfinclude graph. Shared by the completion contributor and the inspection suppressor so
 * both agree on exactly what "exists".
 *
 * Results are cached per file and invalidated on any PSI change, so edits to Application.cfm or an
 * included file are reflected without a restart.
 */
object CfmlProjectSymbols {

    /** Symbols grouped by the prefix under which they are valid. Each is a label-bearing [Symbol]. */
    class Aggregate(
        val unscoped: List<Symbol>,
        val application: List<Symbol>,
        val thisScope: List<Symbol>,
        /** ColdFusion mappings resolved from Application.cfc (empty unless the option is enabled). */
        val mappings: Map<String, VirtualFile> = emptyMap(),
    ) {
        /** Every symbol name across all buckets — used for cheap "does this identifier exist" checks. */
        val allNames: Set<String> =
            (unscoped.asSequence() + application.asSequence() + thisScope.asSequence())
                .map { it.name }.toSet()

        companion object {
            val EMPTY = Aggregate(emptyList(), emptyList(), emptyList())
        }
    }

    private val AGGREGATE_KEY = Key.create<CachedValue<Aggregate>>("cfml.extra.aggregate")
    private val PER_FILE_KEY = Key.create<CachedValue<List<Symbol>>>("cfml.extra.perFile")
    private val CFML_EXTENSIONS = setOf("cfm", "cfml", "cfc")

    fun isCfmlFile(file: PsiFile): Boolean {
        if (file.language.id.equals("CFML", ignoreCase = true)) return true
        val ext = file.virtualFile?.extension ?: file.name.substringAfterLast('.', "")
        return ext.lowercase() in CFML_EXTENSIONS
    }

    fun of(file: PsiFile): Aggregate =
        CachedValuesManager.getManager(file.project).getCachedValue(
            file,
            AGGREGATE_KEY,
            {
                // Depend on the settings service too, so toggling a feature invalidates this cache at once.
                CachedValueProvider.Result.create(
                    compute(file),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    CfmlExtraSettings.getInstance(),
                )
            },
            false,
        )

    private fun compute(file: PsiFile): Aggregate {
        val project = file.project
        val vfile = file.virtualFile ?: return Aggregate.EMPTY
        val psiManager = PsiManager.getInstance(project)
        val settings = CfmlExtraSettings.getInstance()

        val unscoped = LinkedHashMap<String, Symbol>()
        val application = LinkedHashMap<String, Symbol>()
        val thisScope = LinkedHashMap<String, Symbol>()

        fun addUnscoped(symbols: List<Symbol>, label: String) {
            for (s in symbols) if (s.scope == SymbolScope.UNSCOPED) {
                unscoped.putIfAbsent(s.name, s.copy(sourceLabel = label))
            }
        }

        val appFiles = ApplicationFileLocator.locate(project, vfile, settings.mergeAncestorApplicationFiles)

        // Application.cfm page symbols (nearest first; nearest wins on name conflicts).
        for (cfm in appFiles.cfms) {
            psiManager.findFile(cfm)?.let { addUnscoped(perFile(it), "Application.cfm") }
        }

        // Application.cfc application-/this-scope symbols, and mappings for include resolution.
        val mappings = LinkedHashMap<String, VirtualFile>()
        val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(vfile)
        for (cfcVf in appFiles.cfcs) {
            val cfc = psiManager.findFile(cfcVf) ?: continue
            for (s in perFile(cfc)) when (s.scope) {
                SymbolScope.APPLICATION -> application.putIfAbsent(s.name, s.copy(sourceLabel = "Application.cfc"))
                SymbolScope.THIS -> thisScope.putIfAbsent(s.name, s.copy(sourceLabel = "Application.cfc (this)"))
                else -> {}
            }
            if (settings.resolveCfcMappings) {
                CfMappingResolver.parse(cfc.text, cfcVf.parent, contentRoot)
                    .forEach { (alias, dir) -> mappings.putIfAbsent(alias, dir) }
            }
        }

        val included = IncludeGraphResolver.collectIncludedFiles(project, vfile, mappings) {
            psiManager.findFile(it)?.text
        }
        for (inc in included) {
            val psi = psiManager.findFile(inc) ?: continue
            addUnscoped(perFile(psi), "cfinclude: ${inc.name}")
        }

        return Aggregate(unscoped.values.toList(), application.values.toList(), thisScope.values.toList(), mappings)
    }

    /** Per-file regex extraction, cached and invalidated when that file changes. */
    private fun perFile(psiFile: PsiFile): List<Symbol> =
        CachedValuesManager.getManager(psiFile.project).getCachedValue(
            psiFile,
            PER_FILE_KEY,
            { CachedValueProvider.Result.create(CfmlSymbolExtractor.extract(psiFile.text, psiFile.name), psiFile) },
            false,
        )
}

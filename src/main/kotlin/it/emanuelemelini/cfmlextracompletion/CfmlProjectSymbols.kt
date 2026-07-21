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
    /** Where a symbol is declared, for go-to-declaration navigation. */
    class Declaration(val file: VirtualFile, val offset: Int)

    class Aggregate(
        val unscoped: List<Symbol>,
        val application: List<Symbol>,
        val thisScope: List<Symbol>,
        val session: List<Symbol> = emptyList(),
        val request: List<Symbol> = emptyList(),
        /** ColdFusion mappings resolved from Application.cfc (empty unless the option is enabled). */
        val mappings: Map<String, VirtualFile> = emptyMap(),
        /** Declaration site by lowercased symbol name (nearest declaration wins on conflicts). */
        val declarations: Map<String, Declaration> = emptyMap(),
    ) {
        /** Every symbol name across all buckets — used for cheap "does this identifier exist" checks. */
        val allNames: Set<String> =
            sequenceOf(unscoped, application, thisScope, session, request)
                .flatMap { it.asSequence() }.map { it.name }.toSet()

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
        val session = LinkedHashMap<String, Symbol>()
        val request = LinkedHashMap<String, Symbol>()
        val declarations = LinkedHashMap<String, Declaration>()

        // Routes each extracted symbol into the bucket for the prefix it completes under. `keepThis` is
        // only relevant for components; page/include files use application.*/session.*/request.*/unscoped.
        // Also records the declaration site (nearest wins) for go-to-declaration.
        fun ingest(symbols: List<Symbol>, source: VirtualFile, label: String, keepThis: Boolean) {
            for (s in symbols) {
                val kept = when (s.scope) {
                    SymbolScope.UNSCOPED -> unscoped.putIfAbsent(s.name, s.copy(sourceLabel = label)) == null
                    SymbolScope.APPLICATION -> application.putIfAbsent(s.name, s.copy(sourceLabel = label)) == null
                    SymbolScope.SESSION -> session.putIfAbsent(s.name, s.copy(sourceLabel = label)) == null
                    SymbolScope.REQUEST -> request.putIfAbsent(s.name, s.copy(sourceLabel = label)) == null
                    SymbolScope.THIS -> keepThis && thisScope.putIfAbsent(s.name, s.copy(sourceLabel = label)) == null
                }
                if (kept && s.declarationOffset >= 0) {
                    declarations.putIfAbsent(s.name.lowercase(), Declaration(source, s.declarationOffset))
                }
            }
        }

        val appFiles = ApplicationFileLocator.locate(project, vfile, settings.mergeAncestorApplicationFiles)

        // Application.cfm — page (unscoped) symbols AND application.* variables set the classic way,
        // e.g. <cfset application.test = 1>. Nearest first; nearest wins on name conflicts. The current
        // file is skipped everywhere: the built-in completion already handles same-file symbols, and adding
        // them again would produce duplicate entries (e.g. when editing Application.cfm/.cfc itself).
        for (cfm in appFiles.cfms) {
            if (cfm == vfile) continue
            psiManager.findFile(cfm)?.let { ingest(perFile(it), cfm, "Application.cfm", keepThis = false) }
        }

        // Application.cfc — application.* / this.* scope symbols, and mappings for include resolution.
        val mappings = LinkedHashMap<String, VirtualFile>()
        val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(vfile)
        for (cfcVf in appFiles.cfcs) {
            val cfc = psiManager.findFile(cfcVf) ?: continue
            if (cfcVf != vfile) ingest(perFile(cfc), cfcVf, "Application.cfc", keepThis = true)
            if (settings.resolveCfcMappings) {
                CfMappingResolver.parse(cfc.text, cfcVf.parent, contentRoot)
                    .forEach { (alias, dir) -> mappings.putIfAbsent(alias, dir) }
            }
        }

        // Included files — their unscoped symbols and any application.* they set.
        val included = IncludeGraphResolver.collectIncludedFiles(project, vfile, mappings) {
            psiManager.findFile(it)?.text
        }
        for (inc in included) {
            if (inc == vfile) continue
            val psi = psiManager.findFile(inc) ?: continue
            ingest(perFile(psi), inc, "cfinclude: ${inc.name}", keepThis = false)
        }

        return Aggregate(
            unscoped.values.toList(), application.values.toList(), thisScope.values.toList(),
            session.values.toList(), request.values.toList(), mappings, declarations,
        )
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

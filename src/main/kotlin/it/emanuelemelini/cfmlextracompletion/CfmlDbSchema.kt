package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Looks up a table's columns from the project's attached data sources (Database Tools).
 *
 * Accessed entirely by REFLECTION so this plugin has no compile/test dependency on the Database Tools
 * plugin (a hard dependency drags the Java plugin into the test sandbox and breaks platform tests).
 *
 * Class loading is the tricky part: the API lives in a CONTENT MODULE jar
 * (`plugins/DatabaseTools/lib/modules/intellij.database.jar`), which under the v2 module system is not
 * necessarily visible to our plugin's own classloader — so we try our loader first, then the classloaders
 * of the loaded Database plugin. Every failure path falls back to an empty list (feature simply no-ops).
 *
 * Diagnostics: set the log category `it.emanuelemelini.cfmlextracompletion` to DEBUG
 * (Help > Diagnostic Tools > Debug Log Settings) and check Help > Show Log in Finder / idea.log.
 */
internal object CfmlDbSchema {

    private val LOG = logger<CfmlDbSchema>()

    private const val FACADE = "com.intellij.database.psi.DbPsiFacade"
    private const val DAS_UTIL = "com.intellij.database.util.DasUtil"

    /** Logged-once guard so a persistent failure doesn't flood idea.log on every keystroke. */
    @Volatile
    private var failureLogged = false

    /** Classloader that can see the Database Tools API, or null if the plugin isn't available. */
    private val dbLoader: ClassLoader? by lazy {
        for ((label, loader) in candidateLoaders()) {
            try {
                Class.forName(FACADE, false, loader)
                LOG.info("Database Tools API found via $label")
                return@lazy loader
            } catch (t: Throwable) {
                LOG.debug("Database Tools API not visible via $label: ${t.javaClass.simpleName}")
            }
        }
        LOG.info("Database Tools API not available - SELECT * expansion disabled")
        null
    }

    private fun candidateLoaders(): List<Pair<String, ClassLoader>> {
        val out = ArrayList<Pair<String, ClassLoader>>()
        javaClass.classLoader?.let { out += "own plugin classloader" to it }
        // Classloaders of the loaded Database plugin (main descriptor + its content modules).
        try {
            val pmClass = Class.forName("com.intellij.ide.plugins.PluginManager")
            val instance = pmClass.getMethod("getInstance").invoke(null)
            val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")
            val pid = pluginIdClass.getMethod("getId", String::class.java).invoke(null, "com.intellij.database")
            val descriptor = pmClass.getMethod("findEnabledPlugin", pluginIdClass).invoke(instance, pid)
            val loader = descriptor?.javaClass?.getMethod("getPluginClassLoader")?.invoke(descriptor) as? ClassLoader
            loader?.let { out += "DatabaseTools plugin classloader" to it }
        } catch (t: Throwable) {
            LOG.debug("Could not obtain the Database plugin classloader: ${t.javaClass.simpleName}")
        }
        return out
    }

    /** Whether the Database Tools API is reachable. */
    val available: Boolean get() = dbLoader != null

    /** Cached lookups: the schema rarely changes mid-session and this runs on every keystroke. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private const val CACHE_TTL_MS = 60_000L

    /** Columns of the first table named [tableName] (case-insensitive) across all data sources, cached. */
    fun tableColumns(project: Project, tableName: String): List<String> {
        val key = "${project.locationHash}/${tableName.lowercase()}"
        cache[key]?.let { (at, cols) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) return cols
        }
        val cols = lookupColumns(project, tableName)
        cache[key] = System.currentTimeMillis() to cols
        return cols
    }

    private fun lookupColumns(project: Project, tableName: String): List<String> {
        val loader = dbLoader ?: return emptyList()
        return try {
            val facadeClass = Class.forName(FACADE, true, loader)
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val dataSources = facadeClass.getMethod("getDataSources").invoke(facade) as? List<*> ?: emptyList<Any>()
            LOG.debug("Looking up table '$tableName' across ${dataSources.size} data source(s)")

            val dasUtil = Class.forName(DAS_UTIL, true, loader)
            // Resolve by NAME + ARITY rather than exact parameter types: DasUtil.getColumns takes DasObject
            // (not DasTable) and getTables has several overloads, so hardcoding types is fragile.
            val getColumns = dasUtil.staticMethod("getColumns") ?: run {
                logFailureOnce("DasUtil.getColumns(..) not found")
                return emptyList()
            }
            val getTablesCandidates = dasUtil.staticMethods("getTables")
            if (getTablesCandidates.isEmpty()) {
                logFailureOnce("DasUtil.getTables(..) not found")
                return emptyList()
            }

            val table = dataSources.asSequence().filterNotNull().mapNotNull { ds ->
                val tables = tablesOf(ds, getTablesCandidates, loader)
                if (LOG.isDebugEnabled) {
                    LOG.debug(
                        "Data source '${nameOf(ds) ?: ds.javaClass.simpleName}' (${ds.javaClass.name}): " +
                            "${tables.size} table(s); sample=${tables.take(25).mapNotNull(::nameOf)}",
                    )
                }
                tables.firstOrNull { nameOf(it).equals(tableName, ignoreCase = true) }
            }.firstOrNull()

            if (table == null) {
                LOG.debug("Table '$tableName' not found in any attached data source")
                return emptyList()
            }
            val cols = (getColumns.invoke(null, table) as? Iterable<*>)?.mapNotNull(::nameOf) ?: emptyList()
            LOG.debug("Table '$tableName' -> ${cols.size} column(s): $cols")
            cols
        } catch (t: Throwable) {
            logFailureOnce("Database schema lookup failed for table '$tableName': ${t.javaClass.simpleName} ${t.message}")
            emptyList()
        }
    }

    /**
     * Tables of one data source, trying progressively deeper strategies:
     *  1. `DasUtil.getTables(ds)` — empty for the PSI-level DbDataSource in practice.
     *  2. `DasUtil.getTables(ds.getDelegateDataSource()/getDelegate())` — the RawDataSource behind it.
     *  3. Walk `ds.getModel().traverser()` and keep DasTable instances.
     *
     * All reflective calls resolve methods on the INTERFACES the object implements (and setAccessible),
     * because the impl classes (e.g. DbDataSourceImpl) are non-public — invoking a method looked up on them
     * throws IllegalAccessException.
     */
    private fun tablesOf(ds: Any, getTablesCandidates: List<java.lang.reflect.Method>, loader: ClassLoader): List<Any?> {
        fun viaDasUtil(target: Any?, label: String): List<Any?>? {
            if (target == null) return null
            val m = getTablesCandidates.firstOrNull { it.parameterTypes[0].isInstance(target) } ?: return null
            val list = runCatching { (m.invoke(null, target) as? Iterable<*>)?.toList() }.getOrNull()
            if (!list.isNullOrEmpty()) LOG.debug("Tables via $label: ${list.size}")
            return list
        }

        viaDasUtil(ds, "DasUtil.getTables(dataSource)")?.takeIf { it.isNotEmpty() }?.let { return it }

        for (accessor in listOf("getDelegateDataSource", "getDelegate")) {
            val delegate = callNoArg(ds, accessor)
            viaDasUtil(delegate, "DasUtil.getTables($accessor)")?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        return runCatching {
            val model = callNoArg(ds, "getModel") ?: return emptyList()
            val traverser = callNoArg(model, "traverser") ?: return emptyList()
            // JBTreeTraverser is not itself Iterable; traverse() gives the JBIterable of visited nodes.
            val nodes = (callNoArg(traverser, "traverse") ?: traverser) as? Iterable<*> ?: return emptyList()
            val dasTable = Class.forName("com.intellij.database.model.DasTable", true, loader)
            val list = nodes.filter { dasTable.isInstance(it) }
            LOG.debug("Tables via model traverser: ${list.size}")
            list
        }.getOrElse {
            LOG.debug("Model traverser failed: ${it.javaClass.simpleName} ${it.message}")
            emptyList()
        }
    }

    /** Clears the cached lookups (used by the diagnostic action so it always probes fresh). */
    fun clearCache() = cache.clear()

    /**
     * Human-readable probe of the DB integration, for the "Diagnose Query Schema" action. Reports every
     * step so a failure can be pinpointed without digging through idea.log.
     */
    fun diagnose(project: Project, tableName: String?): String = buildString {
        appendLine("Database Tools API reachable: $available")
        val loader = dbLoader
        if (loader == null) {
            appendLine("=> Database Tools plugin not found/enabled; SELECT * expansion is disabled.")
            return@buildString
        }
        try {
            val facadeClass = Class.forName(FACADE, true, loader)
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val dataSources = facadeClass.getMethod("getDataSources").invoke(facade) as? List<*> ?: emptyList<Any>()
            appendLine("Data sources: ${dataSources.size}")

            val dasUtil = Class.forName(DAS_UTIL, true, loader)
            val getTablesCandidates = dasUtil.staticMethods("getTables")
            appendLine("DasUtil.getTables overloads: ${getTablesCandidates.map { it.parameterTypes[0].simpleName }}")
            appendLine("DasUtil.getColumns present: ${dasUtil.staticMethod("getColumns") != null}")

            for (ds in dataSources.filterNotNull()) {
                appendLine()
                appendLine("- '${nameOf(ds) ?: "?"}' (${ds.javaClass.name})")
                for (m in getTablesCandidates.filter { it.parameterTypes[0].isInstance(ds) }) {
                    val n = runCatching { (m.invoke(null, ds) as? Iterable<*>)?.count() }.getOrNull()
                    appendLine("    getTables(${m.parameterTypes[0].simpleName})  -> ${n ?: "error"}")
                }
                for (accessor in listOf("getDelegateDataSource", "getDelegate")) {
                    val d = callNoArg(ds, accessor)
                    if (d == null) { appendLine("    $accessor -> null"); continue }
                    val m = getTablesCandidates.firstOrNull { it.parameterTypes[0].isInstance(d) }
                    val n = m?.let { runCatching { (it.invoke(null, d) as? Iterable<*>)?.count() }.getOrNull() }
                    appendLine("    $accessor (${d.javaClass.simpleName}) -> ${n ?: "no overload/error"}")
                }
                val model = callNoArg(ds, "getModel")
                appendLine("    getModel() -> ${model?.javaClass?.simpleName ?: "null"}")
                val tables = tablesOf(ds, getTablesCandidates, loader)
                appendLine("    RESOLVED tables: ${tables.size}")
                if (tables.isNotEmpty()) appendLine("    sample: ${tables.take(30).mapNotNull(::nameOf)}")
            }

            if (!tableName.isNullOrBlank()) {
                clearCache()
                val cols = tableColumns(project, tableName)
                appendLine()
                appendLine("Lookup '$tableName' -> ${cols.size} column(s)")
                if (cols.isNotEmpty()) appendLine("columns: $cols")
            }
        } catch (t: Throwable) {
            appendLine("Probe failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /** Invokes a public no-arg method, resolving it on the object's interfaces to avoid IllegalAccessException. */
    private fun callNoArg(target: Any, name: String): Any? {
        val candidates = sequence {
            yieldAll(interfacesOf(target.javaClass))
            yield(target.javaClass)
        }
        for (type in candidates) {
            val m = runCatching { type.getMethod(name) }.getOrNull() ?: continue
            val result = runCatching { m.isAccessible = true; m.invoke(target) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun interfacesOf(clazz: Class<*>): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        var c: Class<*>? = clazz
        while (c != null) {
            fun walk(i: Class<*>) {
                if (out.add(i)) i.interfaces.forEach(::walk)
            }
            c.interfaces.forEach(::walk)
            c = c.superclass
        }
        return out.toList()
    }

    /** Public static methods with the given name taking exactly one argument. */
    private fun Class<*>.staticMethods(name: String): List<java.lang.reflect.Method> =
        methods.filter { it.name == name && it.parameterCount == 1 && java.lang.reflect.Modifier.isStatic(it.modifiers) }

    private fun Class<*>.staticMethod(name: String): java.lang.reflect.Method? = staticMethods(name).firstOrNull()

    private fun logFailureOnce(message: String) {
        if (!failureLogged) {
            failureLogged = true
            LOG.warn("$message (further occurrences suppressed)")
        } else {
            LOG.debug(message)
        }
    }

    /** getName() via interface lookup — impl classes are non-public, so a direct call would be illegal. */
    private fun nameOf(obj: Any?): String? = obj?.let { callNoArg(it, "getName") as? String }
}

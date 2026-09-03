# Cfml Extra Completion

An IntelliJ IDEA plugin that fills gaps in the bundled **CFML** plugin's code completion. It adds
project-wide symbols the built-in completion misses — without replacing anything it already offers.

## What it adds

Completion is pulled from these project-wide sources (on top of the built-in, which only sees the current
file):

| Source | Symbols |
| --- | --- |
| Nearest `Application.cfm` (walking up the dir tree) | unscoped `cfset`/`cfparam` vars & `cffunction`/script functions, plus `application.*` / `session.*` / `request.*` assignments |
| Nearest `Application.cfc` | `application.*`, `session.*`, `request.*` (set in lifecycle methods) and custom `this.*` settings |
| `cfinclude` / script `include` graph (recursive, cycle-safe) | everything above, from each included file |

They complete unscoped (and after `variables.`) or after the matching scope prefix — `application.`,
`session.`, `request.`, `this.` — shown **qualified and prefix-highlighted** to match the built-in, and
surfaced while you type the scope name (before the dot).

Inside a `<cfoutput query="q">` / `<cfloop query="q">` block, the **query's columns** also complete
(unqualified and as `q.column`) when `q` is built in the same file by a `<cfquery>` with an explicit
`SELECT` list or by `queryNew("col1,col2,…")`. If a **data source is attached**, `SELECT *` / `alias.*`
(across `FROM`/`JOIN` tables) expand to the real table columns from the schema; without one they yield
nothing (silent fallback). Query names also complete inside the `query="…"` attribute.

Beyond completion, for these same symbols:

- **Parameter info** — function signatures in the completion popup, plus the argument hint (auto after `(`
  and while typing arguments).
- **Documentation** — a function's preceding comment (`<!--- --->` / JS-doc) or `hint`, shown in the
  completion doc pane and via Quick Documentation (Ctrl+Q).
- **Go to Declaration** — Ctrl/Cmd+Click jumps to the declaration; also follows `cfinclude` paths that
  resolve through a `this.mappings` alias.
- **No false "unresolved" warnings** — the wavy underline is suppressed for symbols we know exist (a scoped
  `InspectionSuppressor`, not disabling the inspection); genuinely unknown identifiers stay flagged.

Same-file symbols and standard `Application.cfc` `this.*` settings are left to the built-in, so nothing is
offered twice.

## Requirements

- IntelliJ IDEA Ultimate **2026.2** (build 262.x).
- The **CFML** plugin (Marketplace id `CFML Support`) installed and enabled — this plugin depends on it.

## Build

```bash
./gradlew buildPlugin      # -> build/distributions/cfmlextracompletion-1.0.0.zip
./gradlew test             # runs the symbol-extractor unit tests
./gradlew verifyPlugin     # checks API compatibility against 2026.2
```

## Install (personal use)

1. Build the ZIP (above).
2. In IntelliJ: **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** and pick
   `build/distributions/cfmlextracompletion-1.0.0.zip`.
3. Restart the IDE.

## Try it

```bash
./gradlew runIde          # launches a sandbox IDE with this plugin + CFML installed
```

Open the `sample/` folder as a project in the sandbox and follow the inline hints:

- `sample/index.cfm` — completion for `Application.cfm` symbols (`appTitle`, `formatMoney`) and for
  `cfinclude`d symbols (`headerVar`, `renderNav`, `footerVar` via a 2nd-level include; a deliberate
  include cycle exercises the guard).
- `sample/appcfc/dashboard.cfm` — type `application.` and `this.` to see `Application.cfc` symbols.
- `sample/scopesAndDocs.cfm` — `application.` / `session.` / `request.` completion, function parameter
  info, Quick Doc (Ctrl+Q), and Ctrl/Cmd+Click go-to on `Application.cfm` functions.
- `sample/queries/queryDemo.cfm` — query-column completion inside `<cfoutput query>` / `<cfloop query>`
  for a `<cfquery>` SELECT list and a `queryNew(...)`.
- `sample/mappings/usesMapping.cfm` — demonstrates the **this.mappings** option: with it off the
  `/lib/...` include is unresolved; with it on, `libVersion` / `slugify` complete.
- `sample/nested/admin/adminPage.cfm` — demonstrates the **ancestor merge** option: `adminSetting`
  (nearest) always completes; `siteWideSetting` / `siteWideHelper` (ancestor) complete only when the
  option is on.

## Optional features

Two opt-in features live under **Settings ▸ Other Settings ▸ Cfml Extra Completion** (both **off** by
default). Toggling them takes effect immediately — no restart:

- **Resolve ColdFusion mappings (`this.mappings`) when following cfinclude.** Reads `this.mappings` from
  the nearest `Application.cfc` (literal paths and `expandPath("./rel")` forms) so an include like
  `<cfinclude template="/alias/file.cfm">` resolves to the mapped directory.
- **Merge Application.cfm / Application.cfc from all ancestor directories.** Offers symbols from Application
  files higher up the tree, not just the nearest one (nearest wins on name conflicts).

## Notes / limitations

- Include path resolution is directory-relative, plus root-relative for a leading `/`. Custom mappings are
  resolved only when the option above is enabled, and only for statically-evaluable values.
- Templates with interpolated expressions (`#...#`) in the path are skipped.
- Extraction is text/regex based on purpose, to avoid coupling to the CFML plugin's internal PSI classes.
- At a function's *own* declaration, Quick Doc shows the built-in `<cffunction>` tag help rather than the
  function's own doc — a built-in behavior we don't override. Docs work everywhere the function is used.

## Changelog

### 1.0.0
- Project-wide completion the built-in CFML plugin misses: symbols from the nearest `Application.cfm`,
  `Application.cfc` (`application.` / `session.` / `request.` / `this.` scopes), and the `cfinclude` graph —
  shown qualified and prefix-highlighted, and surfaced while typing the scope name.
- **Query support** — inside `<cfoutput query="q">` / `<cfloop query="q">`, the query's columns complete
  (unqualified and as `q.column`) from a same-file `<cfquery>` `SELECT` list or `queryNew("...")`; with a
  data source attached, `SELECT *` / `alias.*` expand to the real schema columns. `q.column` also works
  anywhere outside a loop (ColdFusion's first-row dot notation). Query names complete inside the
  `query="..."` attribute.
- **Tools ▸ "CFML Extra Completion: Diagnose Query Schema"** — reports the attached data sources and the
  columns resolved for a table, for when `SELECT *` expansion doesn't behave as expected.
- **Parameter info** (function signature + argument hint) and **documentation** (a preceding comment or the
  `hint` attribute) for these functions; Quick Doc also describes query names and columns.
- **Ctrl/Cmd+Click (Go To Declaration)** for contributed variables and functions, query names and columns
  (jumps into the `<cfquery>`/`queryNew`), and `cfinclude` paths that resolve through a `this.mappings` alias.
- Suppresses false "unresolved" warnings for symbols we know exist (genuinely unknown ones stay flagged).
- Opt-in (Settings > Other Settings > Cfml Extra Completion): resolve `this.mappings` for `cfinclude`, and
  merge `Application.cfm` / `Application.cfc` from all ancestor directories.

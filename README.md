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
./gradlew buildPlugin      # -> build/distributions/cfmlextracompletion-0.1.2.zip
./gradlew test             # runs the symbol-extractor unit tests
./gradlew verifyPlugin     # checks API compatibility against 2026.2
```

## Install (personal use)

1. Build the ZIP (above).
2. In IntelliJ: **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** and pick
   `build/distributions/cfmlextracompletion-0.1.2.zip`.
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

### 0.1.2
- **Ctrl/Cmd+Click (Go To Declaration)** on contributed variables and functions jumps to their
  declaration in `Application.cfm` / `Application.cfc` / an included file. Also navigates `cfinclude`
  paths that resolve through a `this.mappings` alias.
- **`session.*` and `request.*`** variables (set in `Application.cfm`/`.cfc`/included files) now complete
  like `application.*`.
- Contributed functions now show their parameter signature in completion (e.g.
  `formatMoney(amount : numeric, [symbol : string])`) and provide a parameter-info hint (Ctrl+P, and
  automatically while typing arguments) — matching how the built-in CFML functions behave.
- Function documentation — a preceding comment (`<!--- --->` or a JS-doc block) or the `hint` attribute —
  is shown in the completion doc pane and via Quick Documentation (Ctrl+Q) on the function name. (This goes
  beyond the built-in CFML completion, which shows no docs for these functions.)
- `application.*` variables assigned directly in `Application.cfm` (e.g. `<cfset application.test = 1>`) now
  appear in `application.` completion.
- Scope members now match the built-in completion look: shown qualified (`application.name`), with the
  typed text prefix-highlighted, and surfaced while typing the scope name (before the dot is typed).
- No more duplicate entries: symbols the built-in already completes (same-file symbols, standard
  `Application.cfc` settings) are no longer offered a second time.

### 0.1.1
- Initial release: project-wide completion from the nearest `Application.cfm`, `Application.cfc`
  (`application.` / `this.` scope), and the `cfinclude` graph; suppression of the CFML unresolved-reference
  warning for these symbols; opt-in `this.mappings` resolution and ancestor-Application merge.

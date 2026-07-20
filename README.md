# Cfml Extra Completion

An IntelliJ IDEA plugin that fills gaps in the bundled **CFML** plugin's code completion. It adds
project-wide symbols the built-in completion misses — without replacing anything it already offers.

## What it adds

| Source | Symbols offered | Where they complete |
| --- | --- | --- |
| Nearest `Application.cfm` (walking up the dir tree) | `cfset` / `cfparam` variables and `cffunction` / script `function` definitions | unscoped and after `variables.` in any page under that directory |
| Nearest `Application.cfc` | `application.*` set in `onApplicationStart` etc., and `this.*` settings | after `application.` / `this.` |
| `cfinclude` / script `include` graph of the current file (recursive, cycle-safe) | variables and functions from each included file | unscoped and after `variables.` in the current file |

It also **suppresses the CFML "unresolved reference" warning** (the wavy underline) for those same
symbols, so variables that really do exist project-wide are no longer flagged. Genuinely unknown
identifiers are still underlined as normal — this is done via a scoped `InspectionSuppressor` for the
`CfmlReferenceInspection`, not by disabling the inspection.

## Requirements

- IntelliJ IDEA Ultimate **2026.2** (build 262.x).
- The **CFML** plugin (Marketplace id `CFML Support`) installed and enabled — this plugin depends on it.

## Build

```bash
./gradlew buildPlugin      # -> build/distributions/cfmlextracompletion-0.1.1.zip
./gradlew test             # runs the symbol-extractor unit tests
./gradlew verifyPlugin     # checks API compatibility against 2026.2
```

## Install (personal use)

1. Build the ZIP (above).
2. In IntelliJ: **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** and pick
   `build/distributions/cfmlextracompletion-0.1.1.zip`.
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

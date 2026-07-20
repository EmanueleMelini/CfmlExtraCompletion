<!---
  FEATURE: "Resolve ColdFusion mappings (this.mappings) when following cfinclude"
  Enable it under: Settings > Other Settings > CFML Extra Completion.

  The include below uses the "/lib" alias defined in Application.cfc.
    - Setting OFF (default): "/lib/stringUtils.cfm" cannot be resolved, so libVersion / slugify are
      unknown -> no completion and they are underlined as unresolved.
    - Setting ON: the alias resolves to ./libs, so:
        #libVersion   -> completes to libVersion   (no underline)
        #slugify      -> completes to slugify()     (no underline)
--->
<cfinclude template="/lib/stringUtils.cfm">

<cfoutput>
    <p>lib #libVersion#</p>
    <p>#slugify("Hello World")#</p>
</cfoutput>

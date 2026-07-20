<!---
  FEATURE: "Merge Application.cfm / Application.cfc from all ancestor directories"
  Enable it under: Settings > Other Settings > CFML Extra Completion.

  This page sits under admin/. There are two Application.cfm files above it:
    - nested/admin/Application.cfm  -> adminSetting        (nearest: always available)
    - nested/Application.cfm        -> siteWideSetting     (ancestor: only when merge is ON)

  Try typing after '#':
    #adminSetting     -> completes in both modes (nearest)
    #siteWideSetting  -> completes ONLY when the merge option is ON
    #siteWideHelper   -> completes ONLY when the merge option is ON (ancestor function)

  With the option OFF, siteWideSetting / siteWideHelper are underlined as unresolved.
--->
<cfoutput>
    <p>admin: #adminSetting#</p>
    <p>site: #siteWideSetting#</p>
    <p>#siteWideHelper()#</p>
</cfoutput>

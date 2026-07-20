<cfcomponent>
    <cfset this.name = "MappingSample">

    <!---
      Defines a ColdFusion mapping "/lib" -> ./libs (relative to this Application.cfc).
      Used by usesMapping.cfm via <cfinclude template="/lib/stringUtils.cfm">.
    --->
    <cfset this.mappings["/lib"] = expandPath("./libs")>

    <cffunction name="onApplicationStart" returntype="boolean">
        <cfreturn true>
    </cffunction>
</cfcomponent>

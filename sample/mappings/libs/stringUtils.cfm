<!--- Reached only through the "/lib" mapping. Its symbols should complete in usesMapping.cfm. --->
<cfset libVersion = "2.1">
<cffunction name="slugify" returntype="string">
    <cfargument name="text" type="string">
    <cfreturn lCase(replace(arguments.text, " ", "-", "all"))>
</cffunction>

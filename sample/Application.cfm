<!--- Legacy Application.cfm: symbols here should complete unscoped in sibling/child pages. --->
<cfset appTitle = "My Sample App">
<cfset dataSourceName = "mainDSN">
<cfparam name="defaultLocale" default="en_US">

<cffunction name="formatMoney" returntype="string">
    <cfargument name="amount" type="numeric">
    <cfreturn dollarFormat(arguments.amount)>
</cffunction>

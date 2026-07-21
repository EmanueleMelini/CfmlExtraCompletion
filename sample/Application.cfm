<!--- Legacy Application.cfm: symbols here should complete unscoped in sibling/child pages. --->
<cfset appTitle = "My Sample App">
<cfset dataSourceName = "mainDSN">
<cfparam name="defaultLocale" default="en_US">
<cfset application.test = 1>

<!--- TEST --->
<cffunction name="formatMoney" returntype="string">
    <cfargument name="amount" type="numeric" required="true">
    <cfargument name="amount2" type="string" required="false">
    <cfreturn dollarFormat(arguments.amount)>
</cffunction>

<cfscript>
    
    /**
    TEST2
    
    **/
    function testFunc(any t1, required numeric t2) {
        
    }
</cfscript>

<cfset a = formatMoney()>
<cfset session.user = structNew()>
<cfset request.aaaa = 1>
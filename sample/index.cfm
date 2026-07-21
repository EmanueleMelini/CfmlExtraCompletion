<cfinclude template="header.cfm">

<!---
  Type after the '#' to test completion:
    #app        -> appTitle, dataSourceName (from Application.cfm)
    #formatM    -> formatMoney() (from Application.cfm)
    #header     -> headerVar, renderNav (from header.cfm include)
    #footer     -> footerVar (from footer.cfm, 2nd-level include)
--->
<cfoutput>
    <h1>#appTitle#</h1>
    <p>#headerVar#</p>
    <p>#footerVar#</p>
    #formatMoney()#
    #formatBaseN()#
    #testFunc()#
    #session.user#
    #request.aaaa#
</cfoutput>

<cfset x = f>

<cffunction name="testFunc2" returntype="void">
    
</cffunction>

<cfset testFunc2()>
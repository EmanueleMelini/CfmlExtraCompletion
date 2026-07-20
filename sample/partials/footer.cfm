<!--- Second-level include (header.cfm -> footer.cfm): footerVar should also reach index.cfm. --->
<cfset footerVar = "footer value">

<!--- Deliberate cycle back to header to exercise the cycle guard. --->
<cfinclude template="../header.cfm">

<cfoutput>
    #headerVar#
</cfoutput>
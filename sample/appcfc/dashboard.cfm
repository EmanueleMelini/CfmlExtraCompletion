<!---
  Sibling of appcfc/Application.cfc. Type after the '#' to test application-scope completion:
    #application.       -> datasource, uploadPath, maxRetries
    #this.              -> name, sessionManagement
--->
<cfoutput>
    <p>DSN: #application.datasource#</p>
    #application.maxRetries#
    #this.sessionManagement#
</cfoutput>

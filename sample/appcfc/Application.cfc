<cfcomponent>
    <cfset this.name = "SampleAppCfc">
    <cfset this.sessionManagement = true>
    <cfset this.test = 2>

    <cffunction name="onApplicationStart" returntype="boolean">
        <cfscript>
            application.datasource = "mainDSN";
            application.uploadPath = "/var/www/uploads";
            application.maxRetries = 3;
        </cfscript>
        <cfreturn true>
    </cffunction>
</cfcomponent>

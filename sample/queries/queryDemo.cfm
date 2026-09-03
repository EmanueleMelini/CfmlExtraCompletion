<!---
  Query completion demo.

  1) Query NAME completion: inside a query="" attribute, type it and you get the file's query names
     (from <cfquery name="..."> and queryNew). Try:  <cfoutput query="<caret>">  -> users, report
  2) Query COLUMN completion: inside a <cfoutput query="q"> / <cfloop query="q"> block, the columns of q
     complete — unqualified and as q.column — when q is built in THIS SAME FILE by a <cfquery> with an
     explicit SELECT list or by queryNew("..."). SELECT * or dynamic column lists give nothing.
--->

<!--- Columns come from the explicit SELECT list (email is aliased -> "mail"). --->
<cfquery name="users">
    SELECT IDutente, userid, email AS mail
    FROM utenti
</cfquery>

<cfoutput query="users">
    <!--- Type after each '#': you should get id, userName, mail (and users.id, users.userName, users.mail). --->
    #IDutente# - #userid# (#mail#)
    #users.IDutente#
</cfoutput>
<!---
  Dot notation OUTSIDE a query loop: ColdFusion reads the first row. Type after "users." on either line
  and you should get the query's columns (IDutente, userid, mail); Ctrl/Cmd+Click navigates to the SELECT.
--->
<cfset test = users.IDutente>
<cfoutput>
    #users.IDutente#
</cfoutput>

<!--- Columns from a queryNew(...) literal. --->
<cfset report = queryNew("year,total")>

<cfloop query="report">
    <cfoutput>
        <!--- Type after each '#': year, total (and report.year, report.total). --->
        #year#: #total#
    </cfoutput>
</cfloop>

<!---
  SELECT * expansion (needs an attached data source):
  If the project has a data source with a table matching the FROM clause, SELECT * columns are pulled
  from the schema. Without a data source, SELECT * simply yields no columns (silent fallback).
--->
<cfquery name="allUtenti">
    SELECT * FROM utenti
</cfquery>

<cfoutput query="allUtenti">
    <!--- With the DB attached: the real columns of "utenti" complete here. --->
    #<!--- type here --->#
    #IDutente#
    #versione#
    #rsu#
    #Rsu#
    #IDutente#
</cfoutput>

<cfloop query="users">
    <cfoutput>
        <!--- Type after each '#': year, total (and report.year, report.total). --->
        #IDutente#: #userid#
    </cfoutput>
</cfloop>

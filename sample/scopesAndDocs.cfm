<!---
  Demo for the newer features (v0.1.2+). This page is governed by sample/Application.cfm, which declares:
    unscoped:      appTitle, dataSourceName, defaultLocale
    application.:  test
    session.:      user
    request.:      aaaa
    functions:     formatMoney(amount, [amount2]) and testFunc(t1, [t2])  (testFunc has a /** TEST2 */ doc)

  Things to try (type after the '#', or use the shortcut noted):

  1. Scope completion — the plugin offers these while you type the scope name, before the dot:
       #application.   -> test
       #session.       -> user
       #request.       -> aaaa

  2. Parameter info — type "formatMoney(" (or Ctrl+P inside the parens):
       -> hint shows  (amount : numeric, [amount2 : string])

  3. Documentation — Ctrl+Q on "testFunc" below (or see the doc pane in its completion popup):
       -> shows "TEST2"  (docs show where the function is USED, not at its own declaration)

  4. Go to Declaration — Ctrl/Cmd+Click on "formatMoney" or "testFunc":
       -> jumps to sample/Application.cfm
--->
<cfoutput>
    <p>#appTitle#</p>
    <p>app: #application.test#</p>
    <p>session: #session.user.name#</p>
    <p>request: #request.aaaa#</p>
    <p>#formatMoney(100)#</p>
    <p>#testFunc(1, 2)#</p>
</cfoutput>

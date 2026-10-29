Plain-JVM tests for the pure logic (site scoring, phone/email, scam text, fonts, monitor rules, app folders).
Run: kotlinc app/src/main/java/com/toolkit/privacy/{SiteRules,SiteCheck,LookupLogic,LookupNet,ScamRules,FontStyles,MonitorRules,AppCategories}.kt tools/logic-tests/Main.kt -include-runtime -d t.jar && java -jar t.jar

Plain-JVM tests for the pure logic (site scoring, phone/email, scam text, fonts, monitor rules, app folders, Bluetooth, web intel, network maths).
Run from the project root:

    P=app/src/main/java/com/toolkit/privacy
    kotlinc $P/SiteRules.kt $P/SiteCheck.kt $P/LookupLogic.kt $P/LookupNet.kt $P/ScamRules.kt $P/FontStyles.kt $P/MonitorRules.kt $P/AppCategories.kt $P/BtLogic.kt $P/WebIntel.kt $P/NetMath.kt tools/logic-tests/Main.kt -include-runtime -d t.jar && java -jar t.jar
    kotlinc $P/NetMath.kt tools/logic-tests/NetMathTest.kt -include-runtime -d n.jar && java -jar n.jar

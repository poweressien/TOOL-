import com.toolkit.privacy.*
var fails = 0
fun check(n: String, c: Boolean, i: String = "") { if (!c) { fails++; println("FAIL $n $i") } else println("ok   $n") }
fun main() {
    check("ports single", NetMath.parsePorts("22") == listOf(22))
    check("ports list+range", NetMath.parsePorts("22,80,8000-8003") == listOf(22, 80, 8000, 8001, 8002, 8003), NetMath.parsePorts("22,80,8000-8003").toString())
    check("ports dedupe+sort", NetMath.parsePorts("80 22,80;22") == listOf(22, 80))
    check("ports reject 0/70000/backwards", NetMath.parsePorts("0,70000,90-80,abc").isEmpty())
    check("ports keep valid beside junk", NetMath.parsePorts("x,443,99999") == listOf(443))
    check("ports full range", NetMath.parsePorts("1-65535").size == 65535)
    val c = NetMath.cidr("192.168.1.10/24")
    println(c)
    check("cidr network", c.contains("192.168.1.0/24"))
    check("cidr mask", c.contains("255.255.255.0"))
    check("cidr bad", NetMath.cidr("999.1.1.1/8") == "Bad address")
    check("cidr noprefix", NetMath.cidr("10.0.0.1").contains("10.0.0.0/24"))
    check("cidr /32", NetMath.cidr("8.8.8.8/32").contains("8.8.8.8"))
    check("cidr /31 not negative", !NetMath.cidr("10.0.0.0/31").contains("-"))
    check("cidr /0", NetMath.cidr("0.0.0.0/0").contains("0.0.0.0/0"))
    if (fails > 0) { println("$fails FAILED"); System.exit(1) } else println("ALL OK")
}

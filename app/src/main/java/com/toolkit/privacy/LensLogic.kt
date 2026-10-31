package com.toolkit.privacy

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure logic for RealityLens: electronics knowledge, SMD code decoding, plant keyword filter, defect finding and multi-frame super-zoom. */

object ComponentKb {
    data class Part(val name: String, val kind: String, val info: String)
    private class E(val re: Regex, val p: Part)
    private fun e(re: String, name: String, kind: String, info: String) = E(Regex(re), Part(name, kind, info))

    private val db = listOf(
        e("^LM358", "LM358", "Op-amp", "Dual low-power op-amp, 3-32 V single supply. Used for amplifying sensor signals, buffers, simple comparators."),
        e("^LM324", "LM324", "Op-amp", "Quad op-amp, 3-32 V. Four amplifiers in one 14-pin package."),
        e("^LM393", "LM393", "Comparator", "Dual voltage comparator, open-collector output; needs a pull-up resistor."),
        e("^(NE|LM|SE)?555($|[A-Z])", "555 timer", "Timer", "Classic timer IC, 4.5-16 V. Astable (blinker/oscillator) and monostable (one-shot) circuits."),
        e("^ATMEGA328", "ATmega328P", "Microcontroller", "8-bit AVR, 32 KB flash, 2 KB RAM, 16 MHz at 5 V. The chip inside Arduino Uno / Nano."),
        e("^ATMEGA2560", "ATmega2560", "Microcontroller", "8-bit AVR, 256 KB flash, 86 I/O. Arduino Mega."),
        e("^ATTINY(85|13|45)", "ATtiny", "Microcontroller", "Tiny 8-pin AVR microcontroller, 1-8 KB flash."),
        e("^ESP32", "ESP32", "Microcontroller", "Dual-core 240 MHz MCU with Wi-Fi and Bluetooth, 3.3 V logic. Do not feed 5 V into its pins."),
        e("^ESP(8266|-?12)", "ESP8266", "Microcontroller", "80/160 MHz Wi-Fi MCU, 3.3 V logic. NodeMCU / ESP-01 / ESP-12."),
        e("^STM32F1", "STM32F1", "Microcontroller", "ARM Cortex-M3, 72 MHz, 3.3 V. 'Blue Pill' boards (F103)."),
        e("^STM32", "STM32", "Microcontroller", "ARM Cortex-M microcontroller family from ST."),
        e("^(PIC1[68]F|PIC18F)", "PIC", "Microcontroller", "Microchip PIC 8-bit microcontroller."),
        e("^(L|LM|MC)?7805", "7805", "Regulator", "Linear 5 V 1 A regulator. Input must be at least 7 V. Gets hot: heat = (Vin-5 V) x current."),
        e("^(L|LM|MC)?7812", "7812", "Regulator", "Linear 12 V 1 A regulator, input 14.5-35 V."),
        e("^(L|LM|MC)?7809", "7809", "Regulator", "Linear 9 V 1 A regulator."),
        e("^(L|LM|MC)?7905", "7905", "Regulator", "Negative -5 V linear regulator."),
        e("^(AMS|LD)?1117", "AMS1117 / LD1117", "Regulator", "Low-dropout regulator, usually 3.3 V or 5 V, 1 A max. Common on dev boards."),
        e("^LM317", "LM317", "Regulator", "Adjustable regulator 1.25-37 V, 1.5 A. Vout = 1.25 x (1 + R2/R1)."),
        e("^LM2596", "LM2596", "Buck converter", "Step-down switching regulator up to 3 A, 4.5-40 V in. Much more efficient than a 7805."),
        e("^MP1584", "MP1584", "Buck converter", "Tiny step-down converter, 28 V in, 3 A."),
        e("^MT3608", "MT3608", "Boost converter", "Step-up converter, 2-24 V in, up to 28 V out, 2 A switch."),
        e("^XL6009", "XL6009", "Boost converter", "Adjustable step-up/step-down (SEPIC) module, 4 A switch."),
        e("^TP4056", "TP4056", "Charger", "1 A single-cell Li-ion/LiPo charger, 4.2 V. Often paired with a DW01 protection chip."),
        e("^DW01", "DW01", "Protection", "Li-ion battery protection (over-charge, over-discharge, short)."),
        e("^TL431", "TL431", "Reference", "Adjustable 2.5-36 V shunt reference; common in power-supply feedback."),
        e("^(2N|PN)2222", "2N2222 / PN2222", "NPN transistor", "General-purpose NPN, 40 V, 600-800 mA. Good for switching small loads."),
        e("^2N3904", "2N3904", "NPN transistor", "General-purpose NPN, 40 V, 200 mA."),
        e("^2N3906", "2N3906", "PNP transistor", "General-purpose PNP, 40 V, 200 mA."),
        e("^BC547", "BC547", "NPN transistor", "NPN, 45 V, 100 mA. Very common small-signal transistor."),
        e("^BC557", "BC557", "PNP transistor", "PNP complement of the BC547."),
        e("^S?8050", "S8050", "NPN transistor", "NPN, 25 V, 500 mA, SOT-23/TO-92."),
        e("^S?8550", "S8550", "PNP transistor", "PNP complement of the S8050."),
        e("^TIP120", "TIP120", "Darlington", "NPN Darlington, 60 V, 5 A. Needs a heatsink at higher current."),
        e("^2N7000", "2N7000", "N-MOSFET", "Small N-channel MOSFET, 60 V, 200 mA."),
        e("^IRF(Z)?44", "IRFZ44N", "N-MOSFET", "N-channel power MOSFET, 55 V, ~49 A. Needs 10 V gate drive (not logic-level)."),
        e("^IRLZ44", "IRLZ44N", "N-MOSFET", "Logic-level N-channel power MOSFET: fully on at 5 V gate drive."),
        e("^IRF540", "IRF540N", "N-MOSFET", "N-channel power MOSFET, 100 V, 33 A, 10 V gate drive."),
        e("^IRF9540", "IRF9540", "P-MOSFET", "P-channel power MOSFET, 100 V, 23 A."),
        e("^AO340[01]", "AO3400 / AO3401", "SOT-23 MOSFET", "Tiny MOSFETs: AO3400 is N-channel 30 V 5.7 A, AO3401 is P-channel."),
        e("^1N400[1-7]", "1N400x", "Rectifier diode", "1 A rectifier. 1N4001 = 50 V ... 1N4007 = 1000 V. Silver band marks the cathode."),
        e("^1N4148", "1N4148", "Switching diode", "Fast small-signal diode, 100 V, 200 mA."),
        e("^1N581[79]", "1N5817/19", "Schottky diode", "Low-drop (0.3 V) 1 A Schottky; 1N5819 is 40 V."),
        e("^(SS34|1N5822)", "SS34 / 1N5822", "Schottky diode", "3 A Schottky rectifier."),
        e("^PC817", "PC817", "Optocoupler", "Optocoupler for isolating a signal from a different power domain."),
        e("^L293D?", "L293D", "Motor driver", "Dual H-bridge, 600 mA per channel (L293D has flyback diodes). Drives small DC motors/steppers."),
        e("^L298", "L298N", "Motor driver", "Dual H-bridge, 2 A per channel, up to 46 V. Gets hot, drops ~2 V."),
        e("^ULN28", "ULN2003 / ULN2803", "Driver array", "Darlington array, 500 mA per channel; drives relays, stepper motors (28BYJ-48)."),
        e("^(A4988|DRV8825)", "A4988 / DRV8825", "Stepper driver", "Microstepping driver for 3D printers and CNC. Set current limit with the pot."),
        e("^(SN)?74HC595", "74HC595", "Shift register", "8-bit serial-in parallel-out. Expand 3 pins into 8 outputs."),
        e("^(SN)?74HC14", "74HC14", "Logic", "Hex Schmitt-trigger inverter."),
        e("^CD4017", "CD4017", "Counter", "Decade counter with 10 outputs: LED chasers, sequencers."),
        e("^(CD|74HC)4051", "4051", "Analog mux", "8-channel analog multiplexer."),
        e("^PCF8574", "PCF8574", "I/O expander", "I2C 8-bit I/O expander; the chip behind most LCD I2C backpacks."),
        e("^MCP23017", "MCP23017", "I/O expander", "I2C 16-bit I/O expander."),
        e("^ADS1115", "ADS1115", "ADC", "16-bit I2C ADC, 4 channels, programmable gain."),
        e("^MCP3008", "MCP3008", "ADC", "10-bit 8-channel SPI ADC."),
        e("^DS3231", "DS3231", "RTC", "Precise real-time clock (+-2 ppm) with temperature compensation, I2C."),
        e("^DS1307", "DS1307", "RTC", "Basic I2C real-time clock, needs a 32.768 kHz crystal and 5 V."),
        e("^MPU-?6050", "MPU-6050", "IMU", "6-axis accelerometer + gyroscope, I2C 0x68."),
        e("^(BMP|BME)280", "BMP280 / BME280", "Sensor", "Pressure + temperature sensor (BME280 adds humidity). I2C/SPI, 3.3 V."),
        e("^DHT(11|22)", "DHT11 / DHT22", "Sensor", "Temperature + humidity sensor. DHT22 is more accurate (+-0.5 C, +-2 %RH)."),
        e("^DS18B20", "DS18B20", "Sensor", "Digital 1-Wire temperature sensor, -55..125 C, needs a 4.7k pull-up."),
        e("^LM35", "LM35", "Sensor", "Analog temperature sensor: 10 mV per degree C."),
        e("^HC-?SR04", "HC-SR04", "Sensor", "Ultrasonic distance sensor, 2-400 cm, 5 V."),
        e("^HX711", "HX711", "ADC", "24-bit amplifier/ADC for load cells (kitchen scales)."),
        e("^NEO-?6M", "NEO-6M", "GPS", "u-blox GPS receiver module, UART 9600 baud."),
        e("^SG90", "SG90", "Servo", "9 g micro servo, 4.8-6 V, ~180 degrees."),
        e("^WS281[12]", "WS2812", "LED", "Addressable RGB LED (NeoPixel), single data wire, 5 V."),
        e("^MAX7219", "MAX7219", "LED driver", "Drives 8 digits of 7-segment or an 8x8 LED matrix over SPI."),
        e("^MAX232", "MAX232", "Interface", "RS-232 level shifter (TTL <-> +-10 V)."),
        e("^CH340", "CH340", "USB-serial", "USB to serial chip; needs a driver on some PCs."),
        e("^CP210", "CP2102 / CP2104", "USB-serial", "Silicon Labs USB to UART bridge."),
        e("^FT232", "FT232", "USB-serial", "FTDI USB to UART bridge (beware of fake chips)."),
        e("^PL2303", "PL2303", "USB-serial", "Prolific USB to serial chip."),
        e("^NRF24L01", "nRF24L01", "Radio", "2.4 GHz transceiver, 3.3 V logic, SPI."),
        e("^HC-?0[56]", "HC-05 / HC-06", "Radio", "Bluetooth serial module; HC-05 can be master or slave, HC-06 slave only."),
        e("^SIM800", "SIM800L", "Radio", "2G GSM/GPRS module, needs 3.4-4.4 V and 2 A peaks."),
        e("^SX127[26]|^RFM9", "SX127x LoRa", "Radio", "Long-range LoRa radio (433/868/915 MHz)."),
        e("^W25Q", "W25Qxx", "Flash memory", "SPI NOR flash (e.g. W25Q64 = 64 Mbit = 8 MB)."),
        e("^(AT)?24C\\d", "24Cxx", "EEPROM", "I2C EEPROM (24C02 = 2 Kbit, 24C256 = 256 Kbit)."),
        e("^LM386", "LM386", "Audio amp", "Low-voltage 0.5 W audio amplifier."),
        e("^PAM8403", "PAM8403", "Audio amp", "Class-D stereo amplifier, 3 W x 2, 5 V."),
        e("^TDA2030", "TDA2030", "Audio amp", "18 W audio power amplifier.")
    )

    fun lookup(text: String): List<Part> {
        val tokens = Regex("[A-Z0-9][A-Z0-9-]{2,}").findAll(text.uppercase().replace('O', '0').let { text.uppercase() + " " + it }).map { it.value }.toSet()
        val out = LinkedHashMap<String, Part>()
        for (t in tokens) for (x in db) if (x.re.containsMatchIn(t)) out.putIfAbsent(x.p.name, x.p)
        return out.values.toList()
    }
}

object Smd {
    private fun fmtR(ohm: Double): String = when { ohm >= 1e6 -> "%s MΩ".format(trim(ohm / 1e6)); ohm >= 1e3 -> "%s kΩ".format(trim(ohm / 1e3)); else -> "%s Ω".format(trim(ohm)) }
    internal fun trim(d: Double): String = java.math.BigDecimal(d).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()

    /** 3/4-digit SMD resistor codes and R-notation: 103 -> 10 kΩ, 4R7 -> 4.7 Ω, 1002 -> 10 kΩ. */
    fun resistor(code: String): String? {
        val c = code.trim().uppercase()
        Regex("^(\\d*)R(\\d+)$").matchEntire(c)?.let { return fmtR("${it.groupValues[1].ifEmpty { "0" }}.${it.groupValues[2]}".toDouble()) }
        Regex("^(\\d*)([KM])(\\d*)$").matchEntire(c)?.let { m ->
            val v = "${m.groupValues[1].ifEmpty { "0" }}.${m.groupValues[3].ifEmpty { "0" }}".toDouble() * (if (m.groupValues[2] == "K") 1e3 else 1e6); return fmtR(v)
        }
        if (Regex("^\\d{3}$").matches(c)) return fmtR(c.substring(0, 2).toDouble() * 10.0.pow(c[2] - '0'))
        if (Regex("^\\d{4}$").matches(c)) return fmtR(c.substring(0, 3).toDouble() * 10.0.pow(c[3] - '0'))
        return null
    }

    /** Ceramic capacitor codes: 104 -> 100 nF, 473 -> 47 nF, 22 -> 22 pF, 4n7 -> 4.7 nF. */
    fun capacitor(code: String): String? {
        val c = code.trim().lowercase()
        Regex("^(\\d*)([pnu])(\\d*)$").matchEntire(c)?.let { m ->
            val v = "${m.groupValues[1].ifEmpty { "0" }}.${m.groupValues[3].ifEmpty { "0" }}".toDouble()
            return "${trim(v)} ${when (m.groupValues[2]) { "p" -> "pF"; "n" -> "nF"; else -> "µF" }}"
        }
        val pf = when {
            Regex("^\\d{3}$").matches(c) -> c.substring(0, 2).toDouble() * 10.0.pow(c[2] - '0')
            Regex("^\\d{1,2}$").matches(c) -> c.toDouble()
            else -> return null
        }
        return when { pf >= 1e6 -> "${trim(pf / 1e6)} µF"; pf >= 1e3 -> "${trim(pf / 1e3)} nF"; else -> "${trim(pf)} pF" }
    }

    fun find(text: String): List<String> {
        val out = ArrayList<String>()
        Regex("\\b(\\d{3,4}|\\d?R\\d+|\\d+[KM]\\d*)\\b", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val c = m.value
            resistor(c)?.let { out += "$c → resistor $it" }
        }
        return out.distinct().take(8)
    }
}

object ResistorBands {
    val colors = listOf("black", "brown", "red", "orange", "yellow", "green", "blue", "violet", "grey", "white", "gold", "silver")
    private fun digit(c: String) = colors.indexOf(c).takeIf { it in 0..9 }
    private fun mult(c: String): Double? = when (c) { "gold" -> 0.1; "silver" -> 0.01; else -> digit(c)?.let { 10.0.pow(it) } }
    private fun tol(c: String): String = when (c) { "brown" -> "±1%"; "red" -> "±2%"; "green" -> "±0.5%"; "blue" -> "±0.25%"; "violet" -> "±0.1%"; "grey" -> "±0.05%"; "gold" -> "±5%"; "silver" -> "±10%"; else -> "±20%" }

    /** 4 bands (d d mult tol) or 5 bands (d d d mult tol). */
    fun value(bands: List<String>): String? {
        if (bands.size !in 4..5) return null
        val d = bands.take(if (bands.size == 4) 2 else 3).map { digit(it) ?: return null }
        val m = mult(bands[if (bands.size == 4) 2 else 3]) ?: return null
        val n = d.fold(0) { a, x -> a * 10 + x } * m
        val s = when { n >= 1e6 -> "${Smd.trim(n / 1e6)} MΩ"; n >= 1e3 -> "${Smd.trim(n / 1e3)} kΩ"; else -> "${Smd.trim(n)} Ω" }
        return "$s ${tol(bands.last())}"
    }
}

object PlantWords {
    private val words = setOf("plant", "flower", "leaf", "tree", "petal", "grass", "shrub", "flowerpot", "houseplant", "herb", "fern", "moss", "cactus", "succulent", "bush", "vegetable",
        "fruit", "seedling", "branch", "twig", "blossom", "rose", "orchid", "lily", "daisy", "sunflower", "tulip", "palm", "bamboo", "pine", "forest", "garden", "lawn", "crop", "mushroom", "flora", "botany", "weed", "vine", "bud", "stem", "root")
    fun isPlant(label: String) = label.lowercase().split(' ', '-').any { it in words }
}

object Defects {
    data class Flag(val x: Int, val y: Int, val w: Int, val h: Int, val kind: String, val strength: Double)
    data class Report(val sharpness: Double, val flags: List<Flag>, val score: Int, val notes: List<String>, val usable: Boolean)

    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { val c = argb[it]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }

    /** Variance of the Laplacian: below ~60 on a 640-px-wide image means too blurry to judge. */
    fun sharpness(g: IntArray, w: Int, h: Int): Double {
        var n = 0; var s = 0.0; var s2 = 0.0
        for (y in 1 until h - 1 step 2) for (x in 1 until w - 1 step 2) {
            val l = 4.0 * g[y * w + x] - g[y * w + x - 1] - g[y * w + x + 1] - g[(y - 1) * w + x] - g[(y + 1) * w + x]
            s += l; s2 += l * l; n++
        }
        if (n == 0) return 0.0
        val m = s / n; return s2 / n - m * m
    }

    private fun med(v: DoubleArray): Double { if (v.isEmpty()) return 0.0; val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun components(mask: BooleanArray, nx: Int, ny: Int): List<List<Int>> {
        val seen = BooleanArray(mask.size); val out = ArrayList<List<Int>>()
        for (i in mask.indices) if (mask[i] && !seen[i]) {
            val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q.add(i); seen[i] = true
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); comp += c; val cx = c % nx; val cy = c / nx
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)) {
                    val xx = cx + dx; val yy = cy + dy
                    if (xx in 0 until nx && yy in 0 until ny && mask[yy * nx + xx] && !seen[yy * nx + xx]) { seen[yy * nx + xx] = true; q.add(yy * nx + xx) }
                }
            }
            out += comp
        }
        return out
    }

    /** Single-image check: finds tiles whose brightness or edge density is far from what the rest of the surface looks like (stains, cracks, scratches, chips, burn marks). */
    fun analyze(g: IntArray, w: Int, h: Int, tile: Int = 16): Report {
        val sharp = sharpness(g, w, h)
        val nx = w / tile; val ny = h / tile
        if (nx < 6 || ny < 6) return Report(sharp, emptyList(), 0, listOf("Image too small"), false)
        val mean = DoubleArray(nx * ny); val edge = DoubleArray(nx * ny)
        for (ty in 0 until ny) for (tx in 0 until nx) {
            var s = 0.0; var e = 0.0
            for (y in 0 until tile) for (x in 0 until tile) {
                val px = tx * tile + x; val py = ty * tile + y
                s += g[py * w + px]
                if (px + 1 < w && py + 1 < h) e += abs(g[py * w + px + 1] - g[py * w + px]) + abs(g[(py + 1) * w + px] - g[py * w + px])
            }
            mean[ty * nx + tx] = s / (tile * tile); edge[ty * nx + tx] = e / (tile * tile)
        }
        val mM = med(mean); val madM = med(DoubleArray(mean.size) { abs(mean[it] - mM) }) * 1.4826
        val mE = med(edge); val madE = med(DoubleArray(edge.size) { abs(edge[it] - mE) }) * 1.4826
        val tM = maxOf(4 * madM, 18.0); val tE = maxOf(5 * madE, 6.0)
        val mask = BooleanArray(mean.size); val kind = Array(mean.size) { "" }; val str = DoubleArray(mean.size)
        for (i in mean.indices) {
            val dm = abs(mean[i] - mM); val de = edge[i] - mE
            if (dm > tM) { mask[i] = true; kind[i] = if (mean[i] < mM) "dark stain / hole" else "bright spot / glare / chip"; str[i] = dm / tM }
            if (de > tE) { mask[i] = true; kind[i] = if (kind[i].isEmpty()) "scratch / crack / rough patch" else kind[i]; str[i] = maxOf(str[i], de / tE) }
        }
        val frac = mask.count { it }.toDouble() / mask.size
        val notes = ArrayList<String>()
        if (sharp < 40) notes += "Image is blurry (sharpness ${sharp.toInt()}): hold still, tap to focus, add light."
        if (frac > 0.35) return Report(sharp, emptyList(), 0, notes + "Surface is too varied (patterned or cluttered) for automatic inspection. Fill the frame with one plain surface and even lighting.", false)
        val flags = ArrayList<Flag>()
        for (comp in components(mask, nx, ny)) {
            val xs = comp.map { it % nx }; val ys = comp.map { it / nx }
            val s = comp.map { str[it] }.max()
            if (comp.size == 1 && s < 1.6) continue
            val k = comp.groupingBy { kind[it] }.eachCount().maxByOrNull { it.value }!!.key
            flags += Flag(xs.min() * tile, ys.min() * tile, (xs.max() - xs.min() + 1) * tile, (ys.max() - ys.min() + 1) * tile, k, s)
        }
        val area = flags.sumOf { it.w.toDouble() * it.h } / (w.toDouble() * h)
        val score = (100 - minOf(100.0, area * 350 + flags.size * 4 + flags.sumOf { minOf(it.strength, 4.0) } * 2)).roundToInt().coerceIn(0, 100)
        if (flags.isEmpty()) notes += "No irregularities stand out on this surface."
        return Report(sharp, flags.sortedByDescending { it.strength }.take(12), score, notes, true)
    }

    /** Compare a test photo with a reference photo of a good item (same framing). */
    fun diff(ref: IntArray, test: IntArray, w: Int, h: Int, tile: Int = 12): Report {
        var bx = 0; var by = 0; var best = Double.MAX_VALUE
        val m = 10
        for (dy in -8..8 step 2) for (dx in -8..8 step 2) {
            var s = 0.0; var n = 0
            for (y in m until h - m step 6) for (x in m until w - m step 6) { s += abs(ref[y * w + x] - test[(y + dy) * w + x + dx]); n++ }
            if (n > 0 && s / n < best) { best = s / n; bx = dx; by = dy }
        }
        val nx = w / tile; val ny = h / tile
        val d = DoubleArray(nx * ny)
        for (ty in 0 until ny) for (tx in 0 until nx) {
            var s = 0.0; var n = 0
            for (y in 0 until tile) for (x in 0 until tile) {
                val px = tx * tile + x; val py = ty * tile + y; val qx = px + bx; val qy = py + by
                if (qx in 0 until w && qy in 0 until h) { s += abs(ref[py * w + px] - test[qy * w + qx]); n++ }
            }
            d[ty * nx + tx] = if (n > 0) s / n else 0.0
        }
        val mu = d.average(); val sd = sqrt(d.map { (it - mu) * (it - mu) }.average())
        val thr = maxOf(mu + 4 * sd, 22.0)
        val mask = BooleanArray(d.size) { d[it] > thr }
        val flags = components(mask, nx, ny).map { c ->
            val xs = c.map { it % nx }; val ys = c.map { it / nx }
            Flag(xs.min() * tile, ys.min() * tile, (xs.max() - xs.min() + 1) * tile, (ys.max() - ys.min() + 1) * tile, "differs from reference", c.map { d[it] }.max() / thr)
        }
        val area = flags.sumOf { it.w.toDouble() * it.h } / (w.toDouble() * h)
        val score = (100 - minOf(100.0, area * 400 + flags.size * 5)).roundToInt().coerceIn(0, 100)
        val notes = if (flags.isEmpty()) listOf("Matches the reference.") else listOf("${flags.size} area(s) differ from the reference. Alignment shift used: ($bx, $by) px.")
        return Report(sharpness(test, w, h), flags.sortedByDescending { it.strength }.take(12), score, notes, true)
    }
}

object SuperRes {
    private fun px(g: IntArray, w: Int, h: Int, x: Int, y: Int) = g[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** Sub-pixel shift (dx,dy) such that ref(x,y) ~ cur(x+dx, y+dy). */
    fun shift(ref: IntArray, cur: IntArray, w: Int, h: Int, range: Int = 6): Pair<Double, Double> {
        val m = range + 2
        fun sad(dx: Int, dy: Int): Double {
            var s = 0.0; var n = 0
            var y = m; while (y < h - m) { var x = m; while (x < w - m) { s += abs(ref[y * w + x] - cur[(y + dy) * w + x + dx]); n++; x += 2 }; y += 2 }
            return if (n == 0) 0.0 else s / n
        }
        var bx = 0; var by = 0; var best = Double.MAX_VALUE
        for (dy in -range..range) for (dx in -range..range) { val s = sad(dx, dy); if (s < best) { best = s; bx = dx; by = dy } }
        fun sub(a: Double, b: Double, c: Double): Double { val den = a - 2 * b + c; return if (den > 1e-9) ((a - c) / (2 * den)).coerceIn(-0.5, 0.5) else 0.0 }
        val sx = sub(sad(bx - 1, by), sad(bx, by), sad(bx + 1, by)); val sy = sub(sad(bx, by - 1), sad(bx, by), sad(bx, by + 1))
        return (bx + sx) to (by + sy)
    }

    private fun upscaleBilinear(argb: IntArray, w: Int, h: Int, s: Int): IntArray {
        val W = w * s; val H = h * s; val out = IntArray(W * H)
        for (y in 0 until H) { val fy = (y + 0.5) / s - 0.5; val y0 = floor(fy).toInt(); val ty = fy - y0
            for (x in 0 until W) { val fx = (x + 0.5) / s - 0.5; val x0 = floor(fx).toInt(); val tx = fx - x0
                var r = 0.0; var gg = 0.0; var b = 0.0
                for ((ox, oy, wt) in listOf(Triple(0, 0, (1 - tx) * (1 - ty)), Triple(1, 0, tx * (1 - ty)), Triple(0, 1, (1 - tx) * ty), Triple(1, 1, tx * ty))) {
                    val c = argb[(y0 + oy).coerceIn(0, h - 1) * w + (x0 + ox).coerceIn(0, w - 1)]
                    r += wt * ((c shr 16) and 255); gg += wt * ((c shr 8) and 255); b += wt * (c and 255)
                }
                out[y * W + x] = (255 shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (gg.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
            } }
        return out
    }

    fun bilinear(argb: IntArray, w: Int, h: Int, s: Int) = upscaleBilinear(argb, w, h, s)

    /** Shift-and-add super-resolution: frames are aligned to the first, then accumulated on an s-times finer grid. */
    fun stack(frames: List<IntArray>, w: Int, h: Int, s: Int = 2): IntArray {
        val ref = frames[0]; val gr = Defects.toGray(ref)
        val W = w * s; val H = h * s
        val sr = FloatArray(W * H); val sg = FloatArray(W * H); val sb = FloatArray(W * H); val cnt = FloatArray(W * H)
        for ((k, f) in frames.withIndex()) {
            val (dx, dy) = if (k == 0) (0.0 to 0.0) else shift(gr, Defects.toGray(f), w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val hx = ((x - dx) * s + (s - 1) / 2.0).roundToInt(); val hy = ((y - dy) * s + (s - 1) / 2.0).roundToInt()
                if (hx !in 0 until W || hy !in 0 until H) continue
                val c = f[y * w + x]; val i = hy * W + hx
                sr[i] += ((c shr 16) and 255).toFloat(); sg[i] += ((c shr 8) and 255).toFloat(); sb[i] += (c and 255).toFloat(); cnt[i] += 1f
            }
        }
        val base = upscaleBilinear(ref, w, h, s)
        return IntArray(W * H) { i ->
            val b = base[i]; val bw = 0.3f; val n = cnt[i] + bw
            val r = ((sr[i] + bw * ((b shr 16) and 255)) / n).roundToInt().coerceIn(0, 255)
            val g = ((sg[i] + bw * ((b shr 8) and 255)) / n).roundToInt().coerceIn(0, 255)
            val bl = ((sb[i] + bw * (b and 255)) / n).roundToInt().coerceIn(0, 255)
            (255 shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    fun unsharp(argb: IntArray, w: Int, h: Int, amount: Double = 0.8, radius: Int = 2): IntArray {
        val ch = Array(3) { c -> FloatArray(w * h) { ((argb[it] shr (16 - 8 * c)) and 255).toFloat() } }
        val out = IntArray(w * h) { 255 shl 24 }
        for (c in 0 until 3) {
            val tmp = FloatArray(w * h); val blur = FloatArray(w * h); val src = ch[c]
            for (y in 0 until h) { var acc = 0f; for (x in -radius..radius) acc += src[y * w + x.coerceIn(0, w - 1)]
                for (x in 0 until w) { tmp[y * w + x] = acc / (2 * radius + 1); acc += src[y * w + (x + radius + 1).coerceIn(0, w - 1)] - src[y * w + (x - radius).coerceIn(0, w - 1)] } }
            for (x in 0 until w) { var acc = 0f; for (y in -radius..radius) acc += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) { blur[y * w + x] = acc / (2 * radius + 1); acc += tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - radius).coerceIn(0, h - 1) * w + x] } }
            for (i in 0 until w * h) { val v = (src[i] + amount * (src[i] - blur[i])).roundToInt().coerceIn(0, 255); out[i] = out[i] or (v shl (16 - 8 * c)) }
        }
        return out
    }
}

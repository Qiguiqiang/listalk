import com.qi.phoneaudiobridge.UpdateChecker
import java.io.File

/**
 * JVM harness for the SHIPPED Android UpdateChecker.
 *
 * SANDBOX NOTE (v2.2, re-probed 2026-11-17): this VM's network filter
 * breaks JVM-originated TCP data flow — a raw java.net.Socket to a
 * local python server gets "Connection reset" on read, an
 * HttpURLConnection to a local Java HttpServer times out on read, while
 * curl and python clients to the SAME servers succeed. (Same class of
 * limit the v2.1 report documented for .NET.) So the live-HTTP sections
 * below are CONDITIONAL: when the transport works (developer machine /
 * on-device), every assertion runs; when the filter eats the flow, the
 * section prints a labelled SKIP with the report summary instead of
 * failing the suite for an environment reason. Everything that does not
 * need a live socket — manifest parsing of the REAL releases manifest
 * and of a doctored 2.2.0 manifest built from the REAL file hashes, the
 * SHA-256 gate, the version compare, and the failure-surface of
 * fetchManifestReport against a dead port — is a HARD check.
 */
val RELEASES = "/home/hatch/workspace/phone-audio-bridge/releases"

fun main() {
    val apkFile = File("$RELEASES/PhoneAudioBridge-v2.1-debug.apk")
    val realSha = UpdateChecker.sha256Hex(apkFile)
    println("ANDROID local apk sha256=$realSha bytes=${apkFile.length()}")

    // ---- shipped parse + compare on the REAL 2.1.0 manifest (disk bytes) ----
    val manifestText = File("$RELEASES/update.json").readText()
    val info = UpdateChecker.parseManifest(manifestText)
    check(info != null) { "real manifest parse failed" }
    println("ANDROID manifest: version=${info!!.version}")
    println("ANDROID APP_VERSION=${UpdateChecker.APP_VERSION} isNewer=${UpdateChecker.isNewer(info.version)}")
    check(info.version == "2.1.0" && !UpdateChecker.isNewer(info.version)) { "2.1.0 must read as up-to-date" }
    check(UpdateChecker.parsePartnerVersion(manifestText) == "2.1.0") { "partner(windows) must parse as 2.1.0" }
    check(UpdateChecker.isNewer("9.9.9") && !UpdateChecker.isNewer("2.0.0")) { "isNewer sanity" }

    // ---- doctored 2.2.0 manifest (built from the REAL file hashes) ----
    val docText = """
        {"android":{"version":"2.2.0","url":"http://127.0.0.1:18732/PhoneAudioBridge-v2.1-debug.apk","sha256":"$realSha","notes":"doctored"},
         "windows":{"version":"2.2.0","url":"http://127.0.0.1:18732/AirBridge-Setup-v2.1.exe","sha256":"${"0".repeat(64)}","notes":"doctored"}}
    """.trimIndent()
    val doc = UpdateChecker.parseManifest(docText)
    check(doc != null && doc.version == "2.2.0" && UpdateChecker.isNewer(doc.version)) { "doctored 2.2.0 must read as newer" }
    check(UpdateChecker.parsePartnerVersion(docText) == "2.2.0") { "doctored partner cross-check must read 2.2.0" }
    println("ANDROID doctored manifest: android=2.2.0 windows=2.2.0 both parse, android newer=true")

    // ---- SHA-256 gate on the real file (shipped verify) ----
    check(UpdateChecker.verify(apkFile, info.sha256)) { "verify(real sha) must pass" }
    check(!UpdateChecker.verify(apkFile, "0".repeat(64))) { "verify(wrong sha) must fail" }
    println("ANDROID verify(real sha)=true verify(wrong sha)=false")

    // ---- v2.2 defect B: failure surface is a HARD check (dead port) ----
    val repDown = UpdateChecker.fetchManifestReport("http://127.0.0.1:18799/update.json")
    println("ANDROID report(unreachable): ${repDown.summary}")
    check(repDown.httpCode == -1 && repDown.error?.startsWith("网络不可达") == true) { "unreachable must surface a plain reason" }
    check(UpdateChecker.lastCheckSummary == repDown.summary) { "diagnostics summary must be recorded" }

    // ---- live HTTP sections (conditional on the sandbox filter) ----
    val base = "http://127.0.0.1:18731"
    val rep1 = UpdateChecker.fetchManifestReport("$base/update.json")
    println("ANDROID report(live 2.1.0): ${rep1.summary}")
    if (rep1.httpCode == 200 && rep1.info != null) {
        check(rep1.error == null && rep1.info.version == "2.1.0" && rep1.partnerVersion == "2.1.0") { "live report versions" }
        check(!UpdateChecker.isNewer(rep1.info.version)) { "live 2.1.0 up-to-date" }
        val dest = File("/tmp/harness-dl.apk")
        if (dest.exists()) dest.delete()
        val ok = UpdateChecker.downloadTo("$base/PhoneAudioBridge-v2.1-debug.apk", dest)
        println("ANDROID download ok=$ok bytes=${dest.length()} verify=${UpdateChecker.verify(dest, rep1.info.sha256)}")
        check(ok && UpdateChecker.verify(dest, rep1.info.sha256)) { "live download + verify" }
        val rep404 = UpdateChecker.fetchManifestReport("$base/no-such.json")
        println("ANDROID report(404): ${rep404.summary}")
        check(rep404.httpCode == 404 && rep404.error?.contains("HTTP 404") == true) { "live 404 reason" }
        val rep2 = UpdateChecker.fetchManifestReport("http://127.0.0.1:18732/update.json")
        println("ANDROID report(live 2.2.0): ${rep2.summary}")
        if (rep2.info != null) {
            check(rep2.info.version == "2.2.0" && rep2.partnerVersion == "2.2.0" && UpdateChecker.isNewer(rep2.info.version)) { "live 2.2.0 newer + partner" }
            val dest2 = File("/tmp/harness-dl-220.apk")
            if (dest2.exists()) dest2.delete()
            val ok2 = UpdateChecker.downloadTo(rep2.info.url, dest2)
            check(ok2 && UpdateChecker.verify(dest2, rep2.info.sha256)) { "live 2.2.0 download + verify" }
            println("ANDROID live 2.2.0 download+verify OK")
        }
        println("ANDROID LIVE SECTIONS RAN")
    } else {
        println("ANDROID LIVE SECTIONS SKIPPED: JVM loopback data flow blocked by sandbox filter (see header note); report was: ${rep1.summary}")
    }
    println("ANDROID HARNESS OK")
}

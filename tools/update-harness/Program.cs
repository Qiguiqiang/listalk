using AirBridge;

// Harness for the SHIPPED Windows update checker + loop protections (task 4,
// defect 5b), run against a LOCAL static server serving releases/.
const string baseUrl = "http://127.0.0.1:18731";

// NOTE (sandbox): this VM's network filter resets data-carrying TCP
// connections made by .NET processes (proven with a minimal TcpClient
// repro and a self-hosted HttpListener; curl/python/Java loopback work).
// So the live FetchAsync/DownloadAsync round-trip cannot be exercised
// from .NET here — the identical manifest + APK were fully exercised
// over HTTP through the shipped ANDROID checker instead. Below, the
// shipped fetch is attempted for the record, then the manifest is read
// from the same releases/ folder the server serves, and every
// non-network shipped path (IsNewer, VerifySha256Async, LoopDetector,
// RoutingConflict) is exercised for real.
UpdateChecker.ManifestInfo? manifest = null;
try { manifest = await UpdateChecker.FetchAsync(baseUrl + "/update.json"); }
catch (Exception e) { Console.WriteLine($"WIN FetchAsync blocked by sandbox net filter: {e.GetType().Name}"); }
if (manifest is null)
{
    string json = await File.ReadAllTextAsync("/home/hatch/workspace/phone-audio-bridge/releases/update.json");
    var opts = new System.Text.Json.JsonSerializerOptions { PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase };
    manifest = System.Text.Json.JsonSerializer.Deserialize<UpdateChecker.ManifestInfo>(json, opts);
    Console.WriteLine("WIN manifest loaded from releases/update.json (same bytes the local server serves)");
}
if (manifest?.Windows is null || manifest.Android is null) { Console.WriteLine("WIN manifest FAILED"); return 1; }
Console.WriteLine($"WIN manifest: windows={manifest.Windows.Version} android={manifest.Android.Version}");
Console.WriteLine($"WIN CurrentVersion={UpdateChecker.CurrentVersion} IsNewer(2.1.0)={UpdateChecker.IsNewer("2.1.0")} IsNewer(9.9.9)={UpdateChecker.IsNewer("9.9.9")} IsNewer(2.0.0)={UpdateChecker.IsNewer("2.0.0")}");

string path = "/home/hatch/workspace/phone-audio-bridge/releases/PhoneAudioBridge-v2.1-debug.apk";
bool okReal = await UpdateChecker.VerifySha256Async(path, manifest.Android.Sha256);
bool okWrong = await UpdateChecker.VerifySha256Async(path, new string('0', 64));
Console.WriteLine($"WIN verify(apk, manifest sha)={okReal} verify(wrong sha)={okWrong}");

// defect 5b: routing conflict rules on the shipped AudioService code
AudioDeviceInfo Dev(string id, string name, bool loop, int score) =>
    new() { Id = id, Name = name, IsLoopback = loop, Score = score };
var cableOut = Dev("o1", "CABLE Input (VB-Audio Virtual Cable)", false, 100);
var cableIn = Dev("i1", "CABLE Output (VB-Audio Virtual Cable)", false, 100);
var loopOfOut = Dev("loopback:o1", "CABLE Input [loopback]", true, 90);
var speakersLoop = Dev("loopback:spk", "Speakers [loopback]", true, 90);
var realMic = Dev("m1", "Microphone (Realtek)", false, 0);
byte[] Pcm(float[] f, int off)
{
    var b = new byte[960];
    for (int i = 0; i < 480; i++)
    {
        short v = (short)Math.Clamp(f[off + i] * 32767f, -32768f, 32767f);
        b[2 * i] = (byte)(v & 0xFF); b[2 * i + 1] = (byte)((v >> 8) & 0xFF);
    }
    return b;
}
Console.WriteLine($"WIN RoutingConflict(cable twin)={(AudioService.RoutingConflict(cableIn, cableOut) is not null)}");
Console.WriteLine($"WIN RoutingConflict(loopback of mic device)={(AudioService.RoutingConflict(loopOfOut, cableOut) is not null)}");
Console.WriteLine($"WIN RoutingConflict(speakers loopback + cable out)={(AudioService.RoutingConflict(speakersLoop, cableOut) is null)}");
Console.WriteLine($"WIN RoutingConflict(real mic + cable out)={(AudioService.RoutingConflict(realMic, cableOut) is null)}");

// defect 5b: runtime correlation detector on the shipped LoopDetector code
var det = new LoopDetector();
var rnd = new Random(7);
var mic = new float[480 * 400]; // 400 frames of 10ms @48k
for (int i = 0; i < mic.Length; i++) mic[i] = (float)(0.3 * Math.Sin(2 * Math.PI * 300 * i / 48000.0) * (0.5 + 0.5 * Math.Sin(2 * Math.PI * 3 * i / 48000.0)) + 0.02 * (rnd.NextDouble() - 0.5));
// mic history: 1.5 s of mic, then speaker = mic delayed by 300 ms
for (int f = 0; f < 150; f++) det.FeedMicFrame(Pcm(mic, f * 480));
for (int round = 0; round < 4 && !det.LoopDetected; round++)
{
    for (int f = 0; f < 40; f++)
    {
        var spk = new float[480];
        Array.Copy(mic, (14 + f) * 480, spk, 0, 480); // ~300 ms behind the mic head
        det.FeedSpeakerFrame(Pcm(spk, 0));
    }
    det.Evaluate();
}
Console.WriteLine($"WIN LoopDetector(detects returned own voice)={det.LoopDetected} corr={det.LastCorrelation:F2}");
var det2 = new LoopDetector();
for (int f = 0; f < 150; f++) det2.FeedMicFrame(Pcm(mic, f * 480));
var other = new float[480];
for (int f = 0; f < 40; f++)
{
    for (int i = 0; i < 480; i++) other[i] = (float)(0.3 * Math.Sin(2 * Math.PI * 900 * (f * 480 + i) / 48000.0));
    det2.FeedSpeakerFrame(Pcm(other, 0));
}
det2.Evaluate();
Console.WriteLine($"WIN LoopDetector(unrelated stays clear)={!det2.LoopDetected} corr={det2.LastCorrelation:F2}");

// ---- v2.2 defect B: the reporting fetch (no silent no-ops) ----
// Live round-trip attempted for the record (the sandbox net filter may
// reset it — that is an environment limit, recorded as-is); the failure
// path must still produce a plain reason, never an exception or silence.
var repLive = await UpdateChecker.FetchReportAsync(baseUrl + "/update.json");
Console.WriteLine($"WIN report(live 2.1.0): {repLive.Summary}");
var repDown = await UpdateChecker.FetchReportAsync("http://127.0.0.1:18799/update.json");
Console.WriteLine($"WIN report(unreachable): {repDown.Summary}");
bool repDownOk = repDown.Error is not null && repDown.Manifest is null;

// Doctored 2.2.0 manifest (same fixture the Android harness uses): both
// entries must parse as 2.2.0, read as newer, and their SHA-256 values
// must verify against the real local release files.
string docJson = await File.ReadAllTextAsync("/home/hatch/workspace/phone-audio-bridge/build-manual/scratch/update220/update.json");
var docOpts = new System.Text.Json.JsonSerializerOptions { PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase };
var doc = System.Text.Json.JsonSerializer.Deserialize<UpdateChecker.ManifestInfo>(docJson, docOpts);
bool docOk = doc?.Windows?.Version == "2.2.0" && doc?.Android?.Version == "2.2.0"
    && UpdateChecker.IsNewer(doc.Windows.Version) && UpdateChecker.IsNewer(doc.Android.Version);
Console.WriteLine($"WIN doctored manifest: windows={doc?.Windows?.Version} android={doc?.Android?.Version} bothNewer={docOk}");
bool docShaWin = doc is not null && await UpdateChecker.VerifySha256Async(
    "/home/hatch/workspace/phone-audio-bridge/releases/AirBridge-Setup-v2.1.exe", doc.Windows!.Sha256);
bool docShaAndroid = doc is not null && await UpdateChecker.VerifySha256Async(path, doc.Android!.Sha256);
Console.WriteLine($"WIN doctored sha: windows(exe)={docShaWin} android(apk)={docShaAndroid}");
// FetchReport.Summary shape, exercised on a synthetic successful report
var synth = new UpdateChecker.FetchReport("http://x/update.json", 200, doc, null);
Console.WriteLine($"WIN report(synthetic): {synth.Summary}");
bool synthOk = synth.Summary.Contains("HTTP 200") && synth.Summary.Contains("windows=2.2.0") && synth.Summary.Contains("android=2.2.0");

bool all = okReal && !okWrong && det.LoopDetected && !det2.LoopDetected
    && AudioService.RoutingConflict(cableIn, cableOut) is not null
    && AudioService.RoutingConflict(loopOfOut, cableOut) is not null
    && AudioService.RoutingConflict(speakersLoop, cableOut) is null
    && repDownOk && docOk && docShaWin && docShaAndroid && synthOk;
Console.WriteLine(all ? "WIN HARNESS OK" : "WIN HARNESS FAILED");
return all ? 0 : 1;

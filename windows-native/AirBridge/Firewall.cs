using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace AirBridge;

/// <summary>
/// Windows Firewall self-check + one-click repair (discovery hardening
/// §5.3, failure F5). Nothing installed an inbound rule and nothing
/// checked: on a Public-profile network (or after the one-time Defender
/// prompt was dismissed) the PC could beacon out but never receive a
/// single DISCOVER/HELLO — total, silent discovery failure. Now:
///  - the installer attempts the rule at install time (NSIS, same name);
///  - the app checks at engine start (this class, read-only netsh);
///  - if the rule is missing/disabled, diagnostics expose the state and
///    RepairAsync() re-creates it behind ONE UAC prompt.
///
/// The rule name carries the current product brand (听说 LisTalk,
/// renamed in v2.3.0). The NSIS script uses the identical literal and
/// deletes the previous (never-shipped) MediaRelay rule name at
/// install time as upgrade migration; keep the two in sync.
/// </summary>
public sealed class FirewallGuard
{
    public const string RuleName = "听说 LisTalk UDP 47731";
    /// <summary>Profiles the rule covers (installer + repair agree).</summary>
    public const string RuleProfiles = "private,domain";

    public enum State { Unknown, Ok, RuleMissing }

    public State CurrentState { get; private set; } = State.Unknown;
    public string Detail { get; private set; } = "";
    /// <summary>True when any connected network is in the Public category
    /// (best-effort via the HNetCfg.FwPolicy2 COM object; null = could
    /// not determine — never guessed).</summary>
    public bool? ActiveProfileIsPublic { get; private set; }
    public DateTime CheckedAtUtc { get; private set; }

    /// <summary>Read-only check: does an enabled inbound allow rule for
    /// UDP 47731 exist under our name? Never throws.</summary>
    public async Task<State> CheckAsync()
    {
        try
        {
            ActiveProfileIsPublic = DetectPublicProfile();
            string output = await RunNetshAsync($"advfirewall firewall show rule name=\"{RuleName}\"");
            bool present = output.Contains(RuleName) || output.Contains(Protocol.Port.ToString());
            if (!present)
            {
                CurrentState = State.RuleMissing;
                Detail = "no inbound rule found";
            }
            else
            {
                var m = Regex.Match(output, @"(?im)^\s*(Enabled|已启用)\s*[:：]\s*(Yes|No|是|否)");
                if (m.Success && (m.Groups[2].Value == "No" || m.Groups[2].Value == "否"))
                {
                    CurrentState = State.RuleMissing;
                    Detail = "rule exists but is disabled";
                }
                else
                {
                    CurrentState = State.Ok;
                    Detail = "inbound rule present and enabled";
                }
            }
        }
        catch (Exception ex)
        {
            CurrentState = State.Unknown;
            Detail = $"check failed: {ex.GetType().Name}";
        }
        CheckedAtUtc = DateTime.UtcNow;
        return CurrentState;
    }

    /// <summary>One-click elevated repair: a single UAC prompt runs one
    /// PowerShell (EncodedCommand — no quoting pitfalls with the CJK
    /// rule name) that deletes any stale rule and adds the correct one,
    /// then the state is re-checked. False = declined/failed; the state
    /// and Detail say which, nothing is hidden.</summary>
    public async Task<bool> RepairAsync()
    {
        try
        {
            string exe = Environment.ProcessPath ?? "";
            string script =
                $"netsh advfirewall firewall delete rule name='{RuleName}' | Out-Null; " +
                $"netsh advfirewall firewall add rule name='{RuleName}' dir=in action=allow " +
                $"protocol=UDP localport={Protocol.Port} program='{exe.Replace("'", "''")}' " +
                $"profile={RuleProfiles} enable=yes";
            string encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            var psi = new ProcessStartInfo("powershell",
                $"-NoProfile -ExecutionPolicy Bypass -EncodedCommand {encoded}")
            {
                Verb = "runas",
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p is null) { Detail = "repair could not start"; return false; }
            await p.WaitForExitAsync();
        }
        catch (Exception ex)
        {
            // 1223 = the user declined the UAC prompt; anything else is a
            // start failure. Either way: no rule was changed by us.
            Detail = $"repair not run: {ex.GetType().Name}";
            return false;
        }
        return await CheckAsync() == State.Ok;
    }

    private static async Task<string> RunNetshAsync(string args)
    {
        var psi = new ProcessStartInfo("netsh", args)
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        string output = await p.StandardOutput.ReadToEndAsync();
        await p.WaitForExitAsync();
        return output;
    }

    /// <summary>Active firewall profile types via HNetCfg.FwPolicy2
    /// (NET_FW_PROFILE2_PUBLIC = 4). Best-effort: any failure → null.</summary>
    private static bool? DetectPublicProfile()
    {
        try
        {
            var t = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if (t is null) return null;
            dynamic policy = Activator.CreateInstance(t)!;
            int types = (int)policy.CurrentProfileTypes;
            return (types & 4) != 0;
        }
        catch { return null; }
    }
}

# SoundRelay virtual audio driver — uninstall cleanup (test-signed route A).
# Invoked ELEVATED (via elevate.ps1) by the per-user NSIS uninstaller.
# Per INTEGRATION-IN-SETUP.md §5: remove the RunOnce leftover, remove the
# device node (best effort, never blocks), delete the driver package from
# the driver store. Certificate removal and turning Test Mode OFF are NOT
# done here — other test-signed drivers may rely on them; the uninstaller
# shows text guidance instead. Always exits 0.
$ErrorActionPreference = 'Continue'

Write-Host 'Removing RunOnce leftover (AirBridgeDriverSetup)...'
& reg delete 'HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce' /v AirBridgeDriverSetup /f 2>$null | Out-Null

Write-Host 'Removing device node ROOT\SimpleAudioSample (best effort)...'
try { & pnputil /remove-device 'ROOT\SimpleAudioSample' | Out-Null } catch { }

Write-Host 'Deleting driver package SimpleAudioSample.inf from the driver store...'
try {
    $oem = $null
    foreach ($l in (& pnputil /enum-drivers)) {
        if ($l -match 'Published Name:\s*(oem\d+\.inf)') { $oem = $Matches[1] }
        elseif ($l -match 'Original Name:\s*SimpleAudioSample\.inf' -and $oem) {
            Write-Host "  pnputil /delete-driver $oem /uninstall"
            & pnputil /delete-driver $oem /uninstall | Out-Null
            $oem = $null
        }
    }
} catch { }

Write-Host 'Driver cleanup finished (certificate and Test Mode intentionally left untouched).'
exit 0

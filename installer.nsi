; 听说 LisTalk v2.3 — Windows installer (NSIS 3, per-user, no admin)
; Native .NET 8 / Avalonia app — NO Python runtime involved.
; Build: makensis installer-v2.2.nsi  (app source: windows-native\stage-v22\ = AirBridge.exe + airbridge.ico;
;        driver source: windows-native\stage-v22-driver\ — an ASCII-only staging
;        copy of driver\build-output\ (5 files) + driver\一键安装.bat + driver\*.ps1.
;        Refresh the staging dir from driver\ before building (see §1 guard below).
; Driver component follows driver/INTEGRATION-IN-SETUP.md, with ONE deliberate
; deviation ordered for this release: the component is CHECKED BY DEFAULT
; (the doc says unchecked by default). Privileged driver steps run through the
; bundled elevate.ps1 -> install-driver.ps1 (Start-Process -Verb RunAs),
; because this installer itself is per-user (RequestExecutionLevel user).
Unicode true
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"

!define PRODUCT_NAME "听说 LisTalk"
!define PRODUCT_VERSION "2.3.2"
!define PRODUCT_PUBLISHER "Gray"
!ifndef SRC_DIR
    !define SRC_DIR "windows-native\stage-v22"
!endif
!define DRV_DIR "driver"
!define DRV_OUT "driver\build-output"
!define DRV_STAGE "windows-native\stage-v22-driver"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirBridge"

; --- build-time guard (INTEGRATION-IN-SETUP.md §1): never ship a selectable
; --- driver component without the signed driver files in the package.
!macro RequireDriverFile f
    !if /FileExists "${f}"
    !else
        !error "缺少驱动文件: ${f} — 请先产出 driver/build-output（Windows VS2022+WDK 构建机）再打包，见 driver/INTEGRATION-IN-SETUP.md §1"
    !endif
!macroend
!insertmacro RequireDriverFile "${DRV_STAGE}\SimpleAudioSample.inf"
!insertmacro RequireDriverFile "${DRV_STAGE}\simpleaudiosample.sys"
!insertmacro RequireDriverFile "${DRV_STAGE}\SimpleAudioSample.cat"
!insertmacro RequireDriverFile "${DRV_STAGE}\driver-test-cert.cer"
!insertmacro RequireDriverFile "${DRV_STAGE}\driver-test-cert.pfx"
; NOTE: ALL driver payloads are packed from the ASCII-only staging dir
; ${DRV_STAGE}: this POSIX makensis 3.09 build SEGFAULTS whenever a File /
; !if /FileExists operand touches the driver\ directory, because that
; directory contains a non-ASCII filename (一键安装.bat) and its directory
; enumeration crashes. The bat itself is staged as oneclick-install.bat
; and restored to 一键安装.bat at install time via File /oname=.
!insertmacro RequireDriverFile "${DRV_STAGE}\oneclick-install.bat"
; devnode.exe: the bundled SetupAPI helper that creates the device node
; in 一键安装.bat Step 4 (replaces the WDK-only devcon.exe; without it
; the install stages the package but no device ever appears).
!insertmacro RequireDriverFile "${DRV_STAGE}\devnode.exe"
!insertmacro RequireDriverFile "${DRV_STAGE}\install-driver.ps1"
!insertmacro RequireDriverFile "${DRV_STAGE}\elevate.ps1"
!insertmacro RequireDriverFile "${DRV_STAGE}\uninstall-driver.ps1"

Name "${PRODUCT_NAME}"
OutFile "LisTalk-Setup-v${PRODUCT_VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\AirBridge"
InstallDirRegKey HKCU "Software\AirBridge" "InstallDir"
RequestExecutionLevel user
BrandingText "听说 LisTalk ${PRODUCT_VERSION}"

VIProductVersion "${PRODUCT_VERSION}.0"
VIAddVersionKey "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey "FileDescription" "听说 LisTalk 安装程序"
VIAddVersionKey "FileVersion" "${PRODUCT_VERSION}"

!define MUI_ICON "${SRC_DIR}\airbridge.ico"
!define MUI_UNICON "${SRC_DIR}\airbridge.ico"
!define MUI_ABORTWARNING

Var DriverState      ; 0 = not selected, 1 = privileged steps OK, 2 = skipped/failed
Var RebootCheckbox
Var HadDriver

Function RunAirBridge
    Exec '"$INSTDIR\AirBridge.exe"'
FunctionEnd

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
Page custom RebootPageCreate RebootPageLeave
!define MUI_FINISHPAGE_RUN "$INSTDIR\AirBridge.exe"
!define MUI_FINISHPAGE_RUN_FUNCTION "RunAirBridge"
!define MUI_FINISHPAGE_RUN_TEXT "立即运行 听说 LisTalk"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

Section "主程序" SEC_MAIN
    SectionIn RO
    SetOutPath "$INSTDIR"
    File "${SRC_DIR}\AirBridge.exe"
    File "${SRC_DIR}\airbridge.ico"

    WriteUninstaller "$INSTDIR\uninstall.exe"

    CreateDirectory "$SMPROGRAMS\AirBridge"
    CreateShortcut "$SMPROGRAMS\AirBridge\听说 LisTalk.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "听说 LisTalk"
    CreateShortcut "$SMPROGRAMS\AirBridge\卸载 听说 LisTalk.lnk" \
        "$INSTDIR\uninstall.exe" "" "$INSTDIR\airbridge.ico"
    CreateShortcut "$DESKTOP\听说 LisTalk.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "听说 LisTalk"
    ; Rename migration (v2.3.0): remove the previous brand's shortcuts,
    ; or upgraders end up with two desktop/start-menu icons pointing at
    ; the same exe (old names: 声音接力 SoundRelay; the MediaRelay name
    ; was decided but never shipped — deleted defensively anyway).
    Delete "$DESKTOP\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 声音接力 SoundRelay.lnk"
    Delete "$DESKTOP\影音接力 MediaRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\影音接力 MediaRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 影音接力 MediaRelay.lnk"

    WriteRegStr HKCU "Software\AirBridge" "InstallDir" "$INSTDIR"
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
    WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
    WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\AirBridge.exe"
    WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1

    ; Discovery hardening (F5): inbound firewall rule for the app
    ; (UDP 47731, Private+Domain profiles). Without it, a Public-profile
    ; network or a dismissed Defender prompt leaves the PC able to SEND
    ; beacons but unable to RECEIVE anything — total silent discovery
    ; failure. The rule name carries the current brand (听说 LisTalk,
    ; renamed in v2.3.0) and must stay identical to FirewallGuard.RuleName
    ; in the app, which self-checks this rule at every start. The previous
    ; rule name (影音接力 MediaRelay — decided mid-development, never
    ; shipped) is deleted first as upgrade migration. NOTE: this installer
    ; is per-user (unelevated), so netsh will usually refuse here — that is
    ; expected and NEVER fatal: the result is only logged, and the app
    ; offers a one-click elevated repair when its start-up check finds
    ; the rule missing.
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="影音接力 MediaRelay UDP 47731"'
    Pop $0
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="听说 LisTalk UDP 47731"'
    Pop $0
    nsExec::ExecToLog 'netsh advfirewall firewall add rule name="听说 LisTalk UDP 47731" dir=in action=allow protocol=UDP localport=47731 program="$INSTDIR\AirBridge.exe" profile=private,domain enable=yes'
    Pop $0
    DetailPrint "防火墙入站规则添加退出码: $0（非 0 多为安装器未提权，属预期；App 启动会自检，缺失时可在诊断里一键修复）"
SectionEnd

; Driver component per driver/INTEGRATION-IN-SETUP.md §2/§3.
; DEVIATION (release order for v2.2): checked by default — plain Section,
; no /o flag. The doc's default-unchecked is overridden on purpose.
Section "听说虚拟音频设备驱动（测试签名）" SEC_DRIVER
    StrCpy $DriverState 1

    ; §3.1 release files to $INSTDIR\driver\ (flat, per spec) — plus the
    ; build-output\ and cert\ subfolders 一键安装.bat expects, so the
    ; RunOnce resume entry works exactly like a manual double-click.
    SetOutPath "$INSTDIR\driver"
    File "${DRV_STAGE}\SimpleAudioSample.inf"
    File "${DRV_STAGE}\simpleaudiosample.sys"
    File "${DRV_STAGE}\SimpleAudioSample.cat"
    File "${DRV_STAGE}\driver-test-cert.cer"
    File "${DRV_STAGE}\driver-test-cert.pfx"
    File "/oname=一键安装.bat" "${DRV_STAGE}\oneclick-install.bat"
    File "${DRV_STAGE}\install-driver.ps1"
    File "${DRV_STAGE}\elevate.ps1"
    File "${DRV_STAGE}\uninstall-driver.ps1"
    SetOutPath "$INSTDIR\driver\build-output"
    File "${DRV_STAGE}\SimpleAudioSample.inf"
    File "${DRV_STAGE}\simpleaudiosample.sys"
    File "${DRV_STAGE}\SimpleAudioSample.cat"
    File "${DRV_STAGE}\devnode.exe"
    SetOutPath "$INSTDIR\driver\cert"
    File "${DRV_STAGE}\driver-test-cert.cer"

    ; §3.2 Secure Boot pre-check (v2.2.1, FB-1): read the registry state
    ; value UEFISecureBootEnabled — readable WITHOUT elevation. The v2.2
    ; check ran Confirm-SecureBootUEFI from this unelevated installer; that
    ; cmdlet throws "access denied" for non-admins, the catch turned it into
    ; "not enabled", and the install ran on into a bare bcdedit failure
    ; (exit 13) with no guidance. Value 1 = Secure Boot ON: explain with
    ; concrete BIOS steps, skip this component, do NOT abort the install.
    ; Key/value absent (legacy BIOS) -> unknown -> continue; the elevated
    ; install-driver.ps1 re-checks (registry + Confirm-SecureBootUEFI) and
    ; exits 42 if it still finds Secure Boot on.
    StrCpy $0 0
    ClearErrors
    ReadRegDWORD $0 HKLM "SYSTEM\CurrentControlSet\Control\SecureBoot\State" "UEFISecureBootEnabled"
    ${If} ${Errors}
        StrCpy $0 0
        ClearErrors
    ${EndIf}
    DetailPrint "Secure Boot 注册表状态 (UEFISecureBootEnabled): $0"
    ${If} $0 == 1
        StrCpy $DriverState 2
        MessageBox MB_OK|MB_ICONEXCLAMATION "检测到 Secure Boot 处于开启状态，Windows 无法开启测试模式、也就无法加载测试签名驱动。$\r$\n$\r$\n本次已跳过驱动安装，主程序不受影响（App 会自动回退 VB-CABLE）。$\r$\n$\r$\n如需安装驱动，请先关闭 Secure Boot：$\r$\n1) 重启电脑，开机时反复按 Del / F2 / F12 / Esc 进入 BIOS/UEFI 设置（品牌机也可在 Windows 里：设置 → 系统 → 恢复 → 高级启动 → 立即重启 → 疑难解答 → UEFI 固件设置）；$\r$\n2) 在 Security 或 Boot 页找到 Secure Boot，改为 Disabled；$\r$\n3) 保存并退出（通常按 F10）；$\r$\n4) 回到 Windows 后重新运行本安装程序，保持驱动组件勾选即可。"
        Goto driver_done
    ${EndIf}

    ; §3.3–3.5 privileged steps via the bundled elevated helper
    ; (Secure Boot re-check, certutil Root+TrustedPublisher, bcdedit
    ; testsigning on, HKLM RunOnce). The helper checks every step and exits
    ; non-zero on any failure; its full command output is in the log.
    DetailPrint "正在请求管理员权限执行驱动特权步骤（导入证书 / 开启测试模式 / 写入续装项）…"
    nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\driver\elevate.ps1" -Target install-driver.ps1'
    Pop $0
    Pop $1
    DetailPrint "驱动特权步骤输出: $1"
    ${If} $0 == 42
        ; Elevated re-check found Secure Boot ON (pre-check missed it).
        StrCpy $DriverState 2
        MessageBox MB_OK|MB_ICONEXCLAMATION "检测到 Secure Boot 处于开启状态，Windows 无法开启测试模式、也就无法加载测试签名驱动。$\r$\n$\r$\n本次已跳过驱动安装，主程序不受影响（App 会自动回退 VB-CABLE）。$\r$\n$\r$\n如需安装驱动，请先关闭 Secure Boot：$\r$\n1) 重启电脑，开机时反复按 Del / F2 / F12 / Esc 进入 BIOS/UEFI 设置（品牌机也可在 Windows 里：设置 → 系统 → 恢复 → 高级启动 → 立即重启 → 疑难解答 → UEFI 固件设置）；$\r$\n2) 在 Security 或 Boot 页找到 Secure Boot，改为 Disabled；$\r$\n3) 保存并退出（通常按 F10）；$\r$\n4) 回到 Windows 后重新运行本安装程序，保持驱动组件勾选即可。"
        Goto driver_done
    ${EndIf}
    ${If} $0 == 21
        ; elevate.ps1: the UAC prompt itself was declined / could not start.
        StrCpy $DriverState 2
        MessageBox MB_OK|MB_ICONEXCLAMATION "管理员授权（UAC）未通过，驱动组件的特权步骤没有执行。$\r$\n$\r$\n主程序已正常安装，App 会自动回退 VB-CABLE，不影响使用。$\r$\n如需安装驱动：重新运行本安装程序，只勾选驱动组件，并在弹出的 UAC 窗口点「是」。"
        Goto driver_done
    ${EndIf}
    ${If} $0 != 0
        StrCpy $DriverState 2
        ; Failure detail (FB-1 follow-up): install-driver.ps1 writes
        ; install-driver-error.txt on any Fail() — the failing step plus
        ; the command's own output (last lines). Show it IN this dialog
        ; instead of only pointing at the log file. The file is UTF-16LE,
        ; matching this Unicode build's FileRead.
        StrCpy $R0 ""
        IfFileExists "$INSTDIR\driver\install-driver-error.txt" 0 drv_no_detail
            FileOpen $R1 "$INSTDIR\driver\install-driver-error.txt" r
            ${For} $R2 1 8
                FileRead $R1 $R3
                ${If} ${Errors}
                    ${Break}
                ${EndIf}
                StrCpy $R0 "$R0$R3"
            ${Next}
            FileClose $R1
        drv_no_detail:
        ${If} $R0 != ""
            StrCpy $R0 "$\r$\n$\r$\n失败详情（命令原始输出，最后几行）：$\r$\n$R0"
        ${EndIf}
        MessageBox MB_OK|MB_ICONSTOP "驱动组件安装未完成（退出码 $0）。安装驱动需要依次完成：导入测试证书 → 开启 Windows 测试模式 → 写入重启续装项，其中一步没有成功。$R0$\r$\n$\r$\n你可以这样排查：$\r$\n1) 确认 Secure Boot 已关闭（开启时 Windows 会直接拒绝测试模式）；$\r$\n2) UAC 弹窗出现时点「是」；$\r$\n3) 打开日志看具体卡在哪一步：$INSTDIR\driver\install-driver.log（里面有每条命令的完整输出）。$\r$\n$\r$\n主程序已正常安装，App 会自动回退 VB-CABLE，不影响使用。排除原因后重新运行安装程序、只勾选驱动组件即可重试。"
        Goto driver_done
    ${EndIf}
    DetailPrint "驱动特权步骤完成：证书已导入、测试模式已开启、续装项已写入（重启后自动继续）。"
driver_done:
SectionEnd

Function RebootPageCreate
    ${If} $DriverState != 1
        Abort
    ${EndIf}
    nsDialogs::Create 1018
    Pop $0
    ${If} $0 == error
        Abort
    ${EndIf}
    ${NSD_CreateLabel} 0 0 100% 42u "驱动组件的特权步骤已完成：测试证书已导入、Windows 测试模式已开启、续装项已写入。$\r$\n$\r$\n重启并重新登录后，驱动安装会自动继续（登录后请点掉一次 UAC 提示），完成后 Windows 声音设置里会出现「扬声器（AirBridge Wave Speaker）/ 麦克风（AirBridge Wave Microphone）」。"
    Pop $1
    ${NSD_CreateCheckbox} 0 48u 100% 12u "现在重启电脑（推荐）"
    Pop $RebootCheckbox
    ${NSD_SetState} $RebootCheckbox ${BST_CHECKED}
    nsDialogs::Show
FunctionEnd

Function RebootPageLeave
    ${NSD_GetState} $RebootCheckbox $0
    ${If} $0 == ${BST_CHECKED}
        Reboot
    ${EndIf}
FunctionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_MAIN} "听说 LisTalk 主程序（必选）"
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DRIVER} "安装后，Windows 声音设置里会出现「扬声器（AirBridge Wave Speaker）/ 麦克风（AirBridge Wave Microphone）」（设备：声桥 AirBridge 虚拟音频设备），电脑声音可直接选它送往手机。注意：本驱动为测试签名版，需要关闭 Secure Boot、开启 Windows 测试模式（桌面右下角有水印），并在安装后重启一次、自动完成剩余安装。仅建议在自己的电脑上安装。不勾选也不影响使用（App 会自动回退 VB-CABLE）。"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
    ; --- driver cleanup first (INTEGRATION-IN-SETUP.md §5), while the
    ; --- bundled elevated helper is still on disk. Failures never block.
    StrCpy $HadDriver 0
    IfFileExists "$INSTDIR\driver\elevate.ps1" 0 +2
        StrCpy $HadDriver 1
    ; RunOnce leftover: direct attempt (works if uninstaller runs elevated),
    ; the elevated helper below retries it with admin rights.
    DeleteRegValue HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce" "AirBridgeDriverSetup"
    ${If} $HadDriver == 1
        DetailPrint "正在请求管理员权限清理驱动（删除 RunOnce 残值 / 移除设备节点 / 删除驱动包）…"
        nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\driver\elevate.ps1" -Target uninstall-driver.ps1'
        Pop $0
        DetailPrint "驱动清理退出码: $0（非 0 也不阻断卸载）"
    ${EndIf}

    ; Firewall rule cleanup (same names as install + FirewallGuard:
    ; the current LisTalk rule and the never-shipped MediaRelay one):
    ; unelevated it will usually fail — logged only, never blocks.
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="听说 LisTalk UDP 47731"'
    Pop $0
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="影音接力 MediaRelay UDP 47731"'
    Pop $0
    DetailPrint "防火墙规则删除退出码: $0（非 0 不阻断卸载；如需彻底清理请用管理员终端执行同名 delete 命令）"

    Delete "$DESKTOP\听说 LisTalk.lnk"
    Delete "$SMPROGRAMS\AirBridge\听说 LisTalk.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 听说 LisTalk.lnk"
    Delete "$DESKTOP\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 声音接力 SoundRelay.lnk"
    Delete "$DESKTOP\影音接力 MediaRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\影音接力 MediaRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 影音接力 MediaRelay.lnk"
    RMDir "$SMPROGRAMS\AirBridge"
    DeleteRegKey HKCU "${UNINST_KEY}"
    DeleteRegKey HKCU "Software\AirBridge"

    ${If} $HadDriver == 1
        MessageBox MB_OK|MB_ICONINFORMATION "驱动组件清理说明：$\r$\n· 已尝试删除 RunOnce 残值、移除设备节点（ROOT\SimpleAudioSample）并从驱动存储删除 SimpleAudioSample.inf 驱动包。$\r$\n· 测试证书与 Windows 测试模式未自动删除（本机可能有其他测试签名驱动在用）。如需手动清理，请以管理员身份运行：$\r$\n    certutil -delstore Root $\"AirBridge Test Driver Signing$\"$\r$\n    certutil -delstore TrustedPublisher $\"AirBridge Test Driver Signing$\"$\r$\n    bcdedit /set testsigning off$\r$\n  然后重启电脑生效。"
    ${EndIf}

    ; user settings/identity under %LOCALAPPDATA%\AirBridge are kept on purpose
    ; (paired phones survive a reinstall); remove the program directory only
    ; (this also removes $INSTDIR\driver\).
    RMDir /r "$INSTDIR"
SectionEnd

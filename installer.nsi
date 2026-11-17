; 声音接力 SoundRelay v2.2 — Windows installer (NSIS 3, per-user, no admin)
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

!define PRODUCT_NAME "声音接力 SoundRelay"
!define PRODUCT_VERSION "2.2.0"
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
!insertmacro RequireDriverFile "${DRV_STAGE}\install-driver.ps1"
!insertmacro RequireDriverFile "${DRV_STAGE}\elevate.ps1"
!insertmacro RequireDriverFile "${DRV_STAGE}\uninstall-driver.ps1"

Name "${PRODUCT_NAME}"
OutFile "SoundRelay-Setup-v2.2.exe"
InstallDir "$LOCALAPPDATA\Programs\AirBridge"
InstallDirRegKey HKCU "Software\AirBridge" "InstallDir"
RequestExecutionLevel user
BrandingText "声音接力 SoundRelay 2.2.0"

VIProductVersion "2.2.0.0"
VIAddVersionKey "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey "FileDescription" "声音接力 SoundRelay 安装程序"
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
!define MUI_FINISHPAGE_RUN_TEXT "立即运行 声音接力 SoundRelay"
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
    CreateShortcut "$SMPROGRAMS\AirBridge\声音接力 SoundRelay.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "声音接力 SoundRelay"
    CreateShortcut "$SMPROGRAMS\AirBridge\卸载 声音接力 SoundRelay.lnk" \
        "$INSTDIR\uninstall.exe" "" "$INSTDIR\airbridge.ico"
    CreateShortcut "$DESKTOP\声音接力 SoundRelay.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "声音接力 SoundRelay"

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
SectionEnd

; Driver component per driver/INTEGRATION-IN-SETUP.md §2/§3.
; DEVIATION (release order for v2.2): checked by default — plain Section,
; no /o flag. The doc's default-unchecked is overridden on purpose.
Section "声音接力虚拟音频设备驱动（测试签名）" SEC_DRIVER
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
    SetOutPath "$INSTDIR\driver\cert"
    File "${DRV_STAGE}\driver-test-cert.cer"

    ; §3.2 Secure Boot check — exit code 42 means Confirm-SecureBootUEFI
    ; returned True: explain, skip the rest of this component, do NOT abort
    ; the whole install. (UNKNOWN/legacy BIOS -> exit 0 -> continue, same
    ;口径 as driver\一键安装.bat.)
    nsExec::ExecToStack 'powershell -NoProfile -Command "try { if (Confirm-SecureBootUEFI) { exit 42 } else { exit 0 } } catch { exit 0 }"'
    Pop $0
    Pop $1
    DetailPrint "Secure Boot 检测退出码: $0"
    ${If} $0 == 42
        StrCpy $DriverState 2
        MessageBox MB_OK|MB_ICONEXCLAMATION "检测到 Secure Boot 处于开启状态，Windows 无法加载测试签名驱动。$\r$\n$\r$\n本次已跳过驱动安装，主程序不受影响（App 会自动回退 VB-CABLE）。$\r$\n如需安装驱动：请先重启进入 BIOS/UEFI 关闭 Secure Boot，再重新运行本安装程序并保持驱动组件勾选。"
        Goto driver_done
    ${EndIf}

    ; §3.3–3.5 privileged steps via the bundled elevated helper
    ; (certutil Root+TrustedPublisher, bcdedit testsigning on, HKLM RunOnce).
    ; The helper checks every step and exits non-zero on any failure.
    DetailPrint "正在请求管理员权限执行驱动特权步骤（导入证书 / 开启测试模式 / 写入续装项）…"
    nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\driver\elevate.ps1" -Target install-driver.ps1'
    Pop $0
    Pop $1
    DetailPrint "驱动特权步骤输出: $1"
    ${If} $0 != 0
        StrCpy $DriverState 2
        MessageBox MB_OK|MB_ICONSTOP "驱动组件安装未完成：特权步骤执行失败（退出码 $0）——证书导入、开启测试模式、写入续装项中有一步未成功，或 UAC 被取消。$\r$\n$\r$\n主程序已正常安装，App 会自动回退 VB-CABLE，不影响使用。$\r$\n详情见 $INSTDIR\driver\install-driver.log；排除原因后可重新运行安装程序、只勾选驱动组件重试。"
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
    ${NSD_CreateLabel} 0 0 100% 42u "驱动组件的特权步骤已完成：测试证书已导入、Windows 测试模式已开启、续装项已写入。$\r$\n$\r$\n重启并重新登录后，驱动安装会自动继续（登录后请点掉一次 UAC 提示），完成后 Windows 声音设置里会出现「扬声器 / 麦克风（声音接力 SoundRelay 虚拟音频设备）」。"
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
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_MAIN} "声音接力 SoundRelay 主程序（必选）"
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DRIVER} "安装后，Windows 声音设置里会出现「扬声器 / 麦克风（声音接力 SoundRelay 虚拟音频设备）」，电脑声音可直接选它送往手机。注意：本驱动为测试签名版，需要关闭 Secure Boot、开启 Windows 测试模式（桌面右下角有水印），并在安装后重启一次、自动完成剩余安装。仅建议在自己的电脑上安装。不勾选也不影响使用（App 会自动回退 VB-CABLE）。"
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

    Delete "$DESKTOP\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\声音接力 SoundRelay.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 声音接力 SoundRelay.lnk"
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

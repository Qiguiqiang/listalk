; 声桥 AirBridge v2.1 — Windows installer (NSIS 3, per-user, no admin)
; Native .NET 8 / Avalonia app — NO Python runtime involved.
; Build: makensis installer-v2.nsi   (source: windows-native\stage\ = AirBridge.exe + airbridge.ico)
Unicode true
SetCompressor /SOLID lzma

!include "MUI2.nsh"

!define PRODUCT_NAME "声桥 AirBridge"
!define PRODUCT_VERSION "2.1.0"
!define PRODUCT_PUBLISHER "Gray"
!ifndef SRC_DIR
    !define SRC_DIR "windows-native\stage-v21"
!endif
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirBridge"

Name "${PRODUCT_NAME}"
OutFile "AirBridge-Setup-v2.1.exe"
InstallDir "$LOCALAPPDATA\Programs\AirBridge"
InstallDirRegKey HKCU "Software\AirBridge" "InstallDir"
RequestExecutionLevel user
BrandingText "声桥 AirBridge 2.1.0"

VIProductVersion "2.1.0.0"
VIAddVersionKey "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey "FileDescription" "声桥 AirBridge 安装程序"
VIAddVersionKey "FileVersion" "${PRODUCT_VERSION}"

!define MUI_ICON "${SRC_DIR}\airbridge.ico"
!define MUI_UNICON "${SRC_DIR}\airbridge.ico"
!define MUI_ABORTWARNING

Function RunAirBridge
    Exec '"$INSTDIR\AirBridge.exe"'
FunctionEnd

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\AirBridge.exe"
!define MUI_FINISHPAGE_RUN_FUNCTION "RunAirBridge"
!define MUI_FINISHPAGE_RUN_TEXT "立即运行 声桥 AirBridge"
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
    CreateShortcut "$SMPROGRAMS\AirBridge\声桥 AirBridge.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "声桥 AirBridge"
    CreateShortcut "$SMPROGRAMS\AirBridge\卸载 声桥 AirBridge.lnk" \
        "$INSTDIR\uninstall.exe" "" "$INSTDIR\airbridge.ico"
    CreateShortcut "$DESKTOP\声桥 AirBridge.lnk" \
        "$INSTDIR\AirBridge.exe" "" "$INSTDIR\airbridge.ico" 0 SW_SHOWNORMAL "" "声桥 AirBridge"

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

; Optional driver component per driver/INTEGRATION-IN-SETUP.md.
; This package was built WITHOUT driver/build-output (the signed driver
; binaries must be produced on a Windows VS2022+WDK machine), so the
; section is forced read-only/deselected in .onInit and its description
; says so — a selectable component without driver files must never ship.
Section "声桥虚拟音频设备驱动（测试签名）" SEC_DRIVER
SectionEnd

Function .onInit
    ; SF_RO (16) + deselected: visible but not selectable in this package.
    SectionSetFlags ${SEC_DRIVER} 16
FunctionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_MAIN} "声桥 AirBridge 主程序（必选）"
    !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DRIVER} "本安装包未包含驱动文件。安装后，Windows 声音设置里会出现「扬声器 / 麦克风（声桥 AirBridge 虚拟音频设备）」，电脑声音可直接选它送往手机。注意：本驱动为测试签名版，需要关闭 Secure Boot、开启 Windows 测试模式（桌面右下角有水印），并在安装后重启一次、自动完成剩余安装。仅建议在自己的电脑上安装。不勾选也不影响使用（App 会自动回退 VB-CABLE）。"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
    Delete "$DESKTOP\声桥 AirBridge.lnk"
    Delete "$SMPROGRAMS\AirBridge\声桥 AirBridge.lnk"
    Delete "$SMPROGRAMS\AirBridge\卸载 声桥 AirBridge.lnk"
    RMDir "$SMPROGRAMS\AirBridge"
    DeleteRegKey HKCU "${UNINST_KEY}"
    DeleteRegKey HKCU "Software\AirBridge"
    ; user settings/identity under %LOCALAPPDATA%\AirBridge are kept on purpose
    ; (paired phones survive a reinstall); remove the program directory only.
    RMDir /r "$INSTDIR"
SectionEnd

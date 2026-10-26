; CastBay Desktop installer (NSIS 3, Unicode).
;
; Per-user: installs to %LOCALAPPDATA%\Programs\CastBay Desktop without asking for administrator
; rights, adds a Start menu entry (and, if chosen, a desktop shortcut) and an entry in Settings >
; Apps. Installing over a running or older copy replaces it and keeps the person's pairings.
; Installs the Microsoft Edge WebView2 runtime first when the PC has none.
;
; makensis /DVERSION=0.1.0 /DAPP_EXE=<CastBay Desktop.exe> /DWEBVIEW2_SETUP=<MicrosoftEdgeWebview2Setup.exe> \
;          /DOUTFILE=<...-setup.exe> /DLICENSE_FILE=<LICENSE> castbay-desktop.nsi

Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!insertmacro GetSize

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!ifndef OUTFILE
  !define OUTFILE "CastBay-Desktop-${VERSION}-windows-x64-setup.exe"
!endif

!define APP_NAME "CastBay Desktop"
!define EXE_NAME "CastBay Desktop.exe"
!define APP_KEY "Software\CastBay Desktop"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\CastBayDesktop"
!define RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define WEBVIEW2_KEY "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
; The app's own data (identity, pinned receivers, log), named by its Tauri identifier.
!define DATA_DIR "com.weenas.castbay.desktop"

Name "${APP_NAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "${APP_KEY}" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails nevershow
ShowUninstDetails nevershow
BrandingText "${APP_NAME} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "FileDescription" "${APP_NAME} installer"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "GPL-3.0"

!define MUI_ICON "..\app\icons\icon.ico"
!define MUI_UNICON "..\app\icons\icon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXE_NAME}"
!define MUI_FINISHPAGE_RUN_TEXT "$(RunAfter)"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; The first language is the fallback; the installer picks the one that matches Windows.
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

LangString SecMainName ${LANG_ENGLISH} "${APP_NAME}"
LangString SecMainName ${LANG_SIMPCHINESE} "${APP_NAME}"
LangString SecDesktopName ${LANG_ENGLISH} "Desktop shortcut"
LangString SecDesktopName ${LANG_SIMPCHINESE} "桌面快捷方式"
LangString SecMainDesc ${LANG_ENGLISH} "The program and its Start menu entry."
LangString SecMainDesc ${LANG_SIMPCHINESE} "程序本体和开始菜单项。"
LangString SecDesktopDesc ${LANG_ENGLISH} "Put a shortcut on the desktop."
LangString SecDesktopDesc ${LANG_SIMPCHINESE} "在桌面上放一个快捷方式。"
LangString RunAfter ${LANG_ENGLISH} "Run ${APP_NAME}"
LangString RunAfter ${LANG_SIMPCHINESE} "运行 ${APP_NAME}"
LangString NoWebView2 ${LANG_ENGLISH} "Installing the Microsoft Edge WebView2 runtime..."
LangString NoWebView2 ${LANG_SIMPCHINESE} "正在安装 Microsoft Edge WebView2 运行库…"
LangString KeepData ${LANG_ENGLISH} "Also delete the settings, pairings and logs of ${APP_NAME}?$\r$\n$\r$\nChoose No to keep them for a later install."
LangString KeepData ${LANG_SIMPCHINESE} "是否同时删除 ${APP_NAME} 的设置、配对记录和日志？$\r$\n$\r$\n选“否”会保留它们，以后重新安装可继续使用。"

; The program is running (or sitting in the tray): ask it to close, then make sure.
!macro CloseApp un
  Function ${un}CloseApp
    nsExec::Exec 'taskkill /IM "${EXE_NAME}"'
    Pop $0
    Sleep 1500
    nsExec::Exec 'taskkill /F /IM "${EXE_NAME}"'
    Pop $0
    Sleep 500
  FunctionEnd
!macroend
!insertmacro CloseApp ""
!insertmacro CloseApp "un."

; WebView2 is part of Windows 11 and recent Windows 10; the bootstrapper fills the gap.
Function EnsureWebView2
  ReadRegStr $0 HKLM "SOFTWARE\WOW6432Node\${WEBVIEW2_KEY}" "pv"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "${WEBVIEW2_KEY}" "pv"
  ${EndIf}
  ${If} $0 == ""
  ${OrIf} $0 == "0.0.0.0"
    DetailPrint "$(NoWebView2)"
    InitPluginsDir
    File "/oname=$PLUGINSDIR\webview2-setup.exe" "${WEBVIEW2_SETUP}"
    ExecWait '"$PLUGINSDIR\webview2-setup.exe" /silent /install'
  ${EndIf}
FunctionEnd

Section "$(SecMainName)" SecMain
  SectionIn RO
  Call CloseApp
  Call EnsureWebView2

  SetOutPath "$INSTDIR"
  File "/oname=${EXE_NAME}" "${APP_EXE}"
  File "/oname=LICENSE.txt" "${LICENSE_FILE}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateShortcut "$SMPROGRAMS\${APP_NAME}.lnk" "$INSTDIR\${EXE_NAME}"

  WriteRegStr HKCU "${APP_KEY}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "CastBay"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${EXE_NAME}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "URLInfoAbout" "https://github.com/weenas/castbay"
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "EstimatedSize" "$0"

  ; A copy that was set to start with Windows keeps doing so from the new location.
  ReadRegStr $0 HKCU "${RUN_KEY}" "CastBayDesktop"
  ${If} $0 != ""
    WriteRegStr HKCU "${RUN_KEY}" "CastBayDesktop" '"$INSTDIR\${EXE_NAME}" --minimized'
  ${EndIf}
SectionEnd

Section /o "$(SecDesktopName)" SecDesktop
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${EXE_NAME}"
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "$(SecMainDesc)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "$(SecDesktopDesc)"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  Call un.CloseApp

  ; Only what the installer put there: the folder may be one the person chose.
  Delete "$INSTDIR\${EXE_NAME}"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  Delete "$SMPROGRAMS\${APP_NAME}.lnk"
  Delete "$DESKTOP\${APP_NAME}.lnk"
  DeleteRegValue HKCU "${RUN_KEY}" "CastBayDesktop"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
  DeleteRegKey HKCU "${APP_KEY}"

  ${IfNot} ${Silent}
    MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 "$(KeepData)" IDNO keep
      RMDir /r "$APPDATA\${DATA_DIR}"
      RMDir /r "$LOCALAPPDATA\${DATA_DIR}"
    keep:
  ${EndIf}
SectionEnd

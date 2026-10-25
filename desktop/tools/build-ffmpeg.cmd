@echo off
rem Builds FFmpeg for CastBay Desktop (see build-ffmpeg.sh). Needs MSYS2 (with make, nasm,
rem pkgconf, diffutils), and Visual Studio's C++ build tools. MSYS2_ROOT defaults to
rem %USERPROFILE%\tools\msys64.
setlocal
if "%MSYS2_ROOT%"=="" set "MSYS2_ROOT=%USERPROFILE%\tools\msys64"
set "VCVARS="
for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do set "VCVARS=%%i\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (echo Visual Studio's C++ build tools were not found & exit /b 1)
call "%VCVARS%" >nul
set MSYS2_PATH_TYPE=inherit
set CHERE_INVOKING=1
"%MSYS2_ROOT%\usr\bin\bash.exe" -lc "tools/build-ffmpeg.sh"

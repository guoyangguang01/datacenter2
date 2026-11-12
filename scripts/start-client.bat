@echo off
setlocal
set ROOT=%~dp0..
if exist "%ROOT%\.dotnet\dotnet.exe" (set DOTNET=%ROOT%\.dotnet\dotnet.exe) else (set DOTNET=dotnet)
set DOTNET_CLI_HOME=%ROOT%\.dotnet-home
set NUGET_PACKAGES=%ROOT%\.nuget
set DOTNET_CLI_TELEMETRY_OPTOUT=1
set AVALONIA_TELEMETRY_OPTOUT=1
pushd "%ROOT%"
"%DOTNET%" run --project src\SdnCustom.Client.App\SdnCustom.Client.App.csproj
set EXITCODE=%errorlevel%
popd
exit /b %EXITCODE%

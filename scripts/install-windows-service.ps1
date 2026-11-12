param(
    [string]$Executable = "$(Resolve-Path "$PSScriptRoot\..\artifacts\publish\server-win-x64\SdnCustom.Server.App.exe")"
)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "请以管理员身份运行此脚本。"
}

$resolved = (Resolve-Path -LiteralPath $Executable).Path
$binaryPath = "`"$resolved`" --headless"
& sc.exe query SDNCustomServer *> $null
if ($LASTEXITCODE -eq 0) {
    & sc.exe config SDNCustomServer binPath= $binaryPath start= auto
} else {
    & sc.exe create SDNCustomServer binPath= $binaryPath start= auto DisplayName= "SDNCustom Server"
}
if ($LASTEXITCODE -ne 0) {
    throw "服务注册失败，sc.exe 退出码：$LASTEXITCODE"
}

& sc.exe description SDNCustomServer "SDNCustom IoT data hub server"
Write-Host "服务已注册。使用 sc.exe start SDNCustomServer 启动。"

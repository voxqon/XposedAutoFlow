param(
    [string]$Apk = "",
    [string]$SdkRoot = "",
    [string]$BaselineApk = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (!$Apk) { $Apk = Join-Path $repo "app/build/outputs/apk/release/app-release-unsigned.apk" }
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (!$SdkRoot) {
    $local = Join-Path $repo "local.properties"
    if (Test-Path -LiteralPath $local) {
        $setting = Get-Content -LiteralPath $local | Select-String "^sdk\.dir=(.+)$" | Select-Object -First 1
        if ($setting) { $SdkRoot = $setting.Matches[0].Groups[1].Value.Replace("\\", "\").Replace("\:", ":") }
    }
}
if (!$SdkRoot) { throw "Set ANDROID_HOME or pass -SdkRoot." }
$analyzer = Join-Path $SdkRoot "cmdline-tools/latest/bin/apkanalyzer.bat"
if (!(Test-Path -LiteralPath $analyzer)) { throw "Android SDK apkanalyzer is required: $analyzer" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $init = $archive.GetEntry("assets/xposed_init")
    if (!$init) { throw "Missing assets/xposed_init." }
    $reader = [IO.StreamReader]::new($init.Open())
    try { $entryPoint = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
    if ($entryPoint -ne "com.github.voxqon.xposed.autoflow.XposedEntry") { throw "Unexpected Xposed entry: $entryPoint" }
    foreach ($required in @(
        "assets/holiday_cn/2025.json",
        "assets/holiday_cn/2026.json",
        "assets/holiday_cn/2027.json",
        "okhttp3/internal/publicsuffix/publicsuffixes.gz"
    )) {
        if (!$archive.GetEntry($required)) { throw "Missing runtime asset: $required" }
    }
    $dexCount = @($archive.Entries | Where-Object { $_.FullName -match "^classes(\d+)?\.dex$" }).Count
} finally {
    $archive.Dispose()
}

$definitions = & $analyzer dex packages --defined-only $apkPath
if ($LASTEXITCODE -ne 0) { throw "Could not inspect DEX classes." }
foreach ($required in @($entryPoint, "org.osmdroid.views.MapView")) {
    if (!($definitions -match ("^C\s+d\s+.*\s" + [regex]::Escape($required) + "$"))) {
        throw "Required class was removed or renamed: $required"
    }
}
if ($definitions -match "^C\s+d\s+.*\sde\.robv\.android\.xposed\.") {
    throw "Xposed API must remain compileOnly."
}
$entryCode = (& $analyzer dex code --class $entryPoint $apkPath) -join "`n"
if ($LASTEXITCODE -ne 0 -or !$entryCode.Contains("constructor <init>()V") -or
    !$entryCode.Contains("handleLoadPackage(Lde/robv/android/xposed/callbacks/XC_LoadPackage")) {
    throw "Xposed entry constructor or callback is missing."
}
$debuggable = & $analyzer manifest debuggable $apkPath
if ($LASTEXITCODE -ne 0 -or ($debuggable -join "").Trim() -ne "false") {
    throw "Expected a non-debuggable release APK."
}
$summary = [ordered]@{
    Apk = $apkPath
    Bytes = (Get-Item -LiteralPath $apkPath).Length
    MiB = [math]::Round((Get-Item -LiteralPath $apkPath).Length / 1MB, 3)
    DexFiles = $dexCount
    XposedEntry = "preserved"
    MapView = "preserved"
    RuntimeAssets = "preserved"
    BundledXposedApi = $false
    Debuggable = $false
}
if ($BaselineApk) {
    $baseline = (Get-Item -LiteralPath $BaselineApk).Length
    $summary.BaselineBytes = $baseline
    $summary.ReductionPercent = [math]::Round((1 - $summary.Bytes / $baseline) * 100, 2)
}
[pscustomobject]$summary

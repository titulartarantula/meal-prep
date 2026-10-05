# Runs on the Windows build host (Windows PowerShell 5.1), invoked by tools/remote.sh. Do not run by hand.
# remote.sh copies it next to upload.tar in BUILD_DIR; that directory is $Root.
# 1. Syncs $Root\android with upload.tar (extract, then delete files not in the
#    archive; build/, .gradle/, .kotlin/ and local.properties are never touched).
# 2. Writes local.properties, sets JAVA_HOME/ANDROID_HOME (+ MEALPREP_KEYSTORE_PROPS if the signing file
#    exists) for this process only, runs gradlew.bat with the decoded args and exits with Gradle's code.
param([string]$ArgsB64 = '')

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSCommandPath
$Mirror = Join-Path $Root 'android'
$Tar = Join-Path $Root 'upload.tar'
$Sdk = 'C:\Program Files (x86)\Android\android-sdk'
$Jdk = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$KeystoreProps = Join-Path $Root 'android-signing\keystore.properties'
$TarExe = Join-Path $env:SystemRoot 'System32\tar.exe'

$code = 1
try {
    $gradleArgs = @()
    if ($ArgsB64) {
        $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ArgsB64))
        $gradleArgs = @($text.TrimEnd("`n") -split "`n")
    }

    New-Item -ItemType Directory -Force -Path $Mirror | Out-Null

    # Archive listing -> set of relative paths (case-insensitive, backslashes).
    $listing = & $TarExe -tf $Tar
    if ($LASTEXITCODE -ne 0) { throw "tar -tf failed ($LASTEXITCODE)" }
    $keep = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($e in $listing) { if ($e -and -not $e.EndsWith('/')) { [void]$keep.Add($e.Replace('/', '\')) } }

    # Delete stale files, skipping build outputs/caches at any depth and local.properties.
    $prefix = $Mirror.TrimEnd('\') + '\'
    $skipDir = '(^|\\)(build|\.gradle|\.kotlin)(\\|$)'
    Get-ChildItem -LiteralPath $Mirror -Recurse -Force -File | ForEach-Object {
        $rel = $_.FullName.Substring($prefix.Length)
        $dirPart = Split-Path -Parent $rel
        if ($rel -ieq 'local.properties') { return }
        if ($dirPart -and ($dirPart -match $skipDir)) { return }
        if (-not $keep.Contains($rel)) { Remove-Item -LiteralPath $_.FullName -Force }
    }
    # Remove now-empty source directories (deepest first), again never inside skipped dirs.
    Get-ChildItem -LiteralPath $Mirror -Recurse -Force -Directory |
        Sort-Object { $_.FullName.Length } -Descending | ForEach-Object {
            $rel = $_.FullName.Substring($prefix.Length)
            if ($rel -match $skipDir) { return }
            if (-not (Get-ChildItem -LiteralPath $_.FullName -Force | Select-Object -First 1)) {
                Remove-Item -LiteralPath $_.FullName -Force
            }
        }

    & $TarExe -xf $Tar -C $Mirror
    if ($LASTEXITCODE -ne 0) { throw "tar -xf failed ($LASTEXITCODE)" }

    $sdkEscaped = $Sdk.Replace('\', '\\').Replace(':', '\:')
    [IO.File]::WriteAllText((Join-Path $Mirror 'local.properties'), "sdk.dir=$sdkEscaped`r`n", (New-Object Text.ASCIIEncoding))

    $env:JAVA_HOME = $Jdk
    $env:ANDROID_HOME = $Sdk
    if (Test-Path -LiteralPath $KeystoreProps) { $env:MEALPREP_KEYSTORE_PROPS = $KeystoreProps }
    else { Remove-Item Env:\MEALPREP_KEYSTORE_PROPS -ErrorAction SilentlyContinue }

    Set-Location -LiteralPath $Mirror
    $ErrorActionPreference = 'Continue'
    # Windows OpenSSH kills every process of the session at disconnect, so a Gradle daemon never
    # survives to the next run: --no-daemon (single-use daemon per build) is the honest setting.
    & .\gradlew.bat --console=plain --no-daemon @gradleArgs
    $code = $LASTEXITCODE
}
catch {
    [Console]::Error.WriteLine("windows-build.ps1: $_")
    $code = 1
}
finally {
    Remove-Item -LiteralPath $Tar -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
}
exit $code

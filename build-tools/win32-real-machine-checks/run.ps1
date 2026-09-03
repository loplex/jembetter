<#
.SYNOPSIS
  Runs the Win32 real-machine checks (see this directory's README.md) on an
  actual Windows machine or CI runner — real user32.dll/kernel32.dll, real
  explorer.exe/dwm.exe, no Wine. These cover the things the reactor's
  @Tag("windows") suite can't: behaviour under contention, cross-process,
  and under a burst of injected input.

  FOCUS, FOCUSWATCH, REPARENT, CLICK/hook-survival, SOCKETCLIENT and
  CLICKFOCUS print an automatic PASS/FAIL and gate this script's exit code.
  FG-LOCK, CLICK/latency and CLICK/UIPI are observational — read their
  printed lines yourself.

.PARAMETER RepoRoot
  Path to the repo root. Defaults to two levels up from this script.

.PARAMETER JavaHome
  JAVA_HOME to use. Defaults to $env:JAVA_HOME, falling back to whatever
  `java`/`javac` are already on PATH.
#>
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

function Resolve-JavaExe([string]$name) {
    if ($JavaHome) {
        return (Join-Path $JavaHome "bin\$name.exe")
    }
    return $name
}

$javaExe = Resolve-JavaExe "java"
$javacExe = Resolve-JavaExe "javac"

Write-Host "win32-real-machine-checks: compiling jembetter-host + jembetter-client and their deps (main code only)..."
# host + client -am pulls in core-common / core-win32 / core-x11 too; the
# SOCKETCLIENT / CLICKFOCUS checks drive EmbedSocketWin32 / EmbedClientWin32 /
# EmbedHost, not just the core-win32 primitives the others use.
& mvn -q -f "$RepoRoot\pom.xml" -pl jembetter-host,jembetter-client -am compile
if ($LASTEXITCODE -ne 0) { throw "mvn compile failed" }

$m2 = Join-Path $env:USERPROFILE ".m2\repository"
$jnaJar = Get-ChildItem -Recurse -Path (Join-Path $m2 "net\java\dev\jna\jna") -Filter "jna-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } | Sort-Object Name | Select-Object -Last 1
$jnaPlatformJar = Get-ChildItem -Recurse -Path (Join-Path $m2 "net\java\dev\jna\jna-platform") -Filter "jna-platform-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } | Sort-Object Name | Select-Object -Last 1
$slf4jJar = Get-ChildItem -Recurse -Path (Join-Path $m2 "org\slf4j\slf4j-api") -Filter "slf4j-api-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } | Sort-Object Name | Select-Object -Last 1
if (-not $jnaJar -or -not $jnaPlatformJar -or -not $slf4jJar) {
    throw "jna/jna-platform/slf4j-api jars not found under $m2 - run 'mvn install' first"
}

$classpath = @(
    "$RepoRoot\jembetter-core-common\target\classes",
    "$RepoRoot\jembetter-core-win32\target\classes",
    "$RepoRoot\jembetter-core-x11\target\classes",
    "$RepoRoot\jembetter-host\target\classes",
    "$RepoRoot\jembetter-client\target\classes",
    $jnaJar.FullName,
    $jnaPlatformJar.FullName,
    $slf4jJar.FullName
) -join ";"

$checkOut = Join-Path $PSScriptRoot "target"
if (Test-Path $checkOut) { Remove-Item -Recurse -Force $checkOut }
New-Item -ItemType Directory -Path $checkOut | Out-Null

Write-Host "win32-real-machine-checks: compiling check classes..."
$sources = Get-ChildItem -Path $PSScriptRoot -Filter "*.java" | ForEach-Object { $_.FullName }
& $javacExe -cp $classpath -d $checkOut @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed compiling check classes" }

$fullClasspath = "$checkOut;$classpath"
$results = @{}

$awtOpens = @(
    "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED"
)

function Run-Check([string]$label, [string]$mainClass, [string[]]$extraJvmArgs) {
    Write-Host ""
    Write-Host "=== $label ($mainClass) ==="
    $jvmArgs = @("-cp", $fullClasspath) + $extraJvmArgs + @($mainClass, $javaExe, $fullClasspath)
    & $javaExe @jvmArgs
    $results[$label] = $LASTEXITCODE
    Write-Host "=== $label exit code: $LASTEXITCODE ==="
}

Run-Check "FG-LOCK"      "cz.loplex.jembetter.win32check.ForegroundLockCheck"       $awtOpens
Run-Check "FOCUS"        "cz.loplex.jembetter.win32check.FocusFallbackCheck"        $awtOpens
Run-Check "FOCUSWATCH"   "cz.loplex.jembetter.win32check.FocusWatcherCheck"         @()
Run-Check "REPARENT"     "cz.loplex.jembetter.win32check.ReparentWatcherCheck"      $awtOpens
Run-Check "CLICK"        "cz.loplex.jembetter.win32check.ClickWatcherCaveatsCheck"  @()
Run-Check "SOCKETCLIENT" "cz.loplex.jembetter.win32check.SocketClientWin32Check"    $awtOpens
Run-Check "CLICKFOCUS"   "cz.loplex.jembetter.win32check.ClickToFocusWin32Check"    $awtOpens

Write-Host ""
Write-Host "=== Summary ==="
foreach ($key in $results.Keys | Sort-Object) {
    $status = if ($results[$key] -eq 0) { "PASS/OK" } else { "FAIL (exit $($results[$key]))" }
    Write-Host "$key : $status"
}
Write-Host "FG-LOCK has no automatic verdict - read its printed lines above (or the log file) yourself."
Write-Host "CLICK's latency and UIPI lines are observational too - only hook-survival gates CLICK's exit code."

$anyHardFailure = ($results["FOCUS"] -ne 0) -or ($results["FOCUSWATCH"] -ne 0) `
    -or ($results["REPARENT"] -ne 0) -or ($results["CLICK"] -ne 0) `
    -or ($results["SOCKETCLIENT"] -ne 0) -or ($results["CLICKFOCUS"] -ne 0)
if ($anyHardFailure) {
    exit 1
}
exit 0

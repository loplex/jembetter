# Repeats a Maven test run and tallies what failed in which iteration. The
# real-Windows half of the Win32 flake-rate measurement; probe.sh is the
# Linux/Wine half and is kept deliberately symmetrical with it.
#
# Usage: probe.ps1 <-Dtest selector, or '' for the whole suite> <iterations>
#
# Environment:
#   FORK_TIMEOUT_SECONDS  -Dsurefire.timeout, caps a fork that stops making
#                         progress (default 120)
#   RUN_TIMEOUT_SECONDS   kills Maven itself (default 600). Not redundant with
#                         the fork cap: observed 2026-09-13 on windows-latest,
#                         a wedged EmbedClientWin32Test fork outlived
#                         -Dsurefire.timeout=120 and the job sat on one
#                         iteration until its own timeout-minutes. Whatever the
#                         fork cap misses, this ends.
#   REUSE_FORKS           "false" runs one JVM per test class (default "true")
#
# A failing or hung iteration is the measurement, not an error: the loop never
# stops early and the script exits 0 unless it could not run at all. Read the
# tables it writes, not its exit code.

param(
    [Parameter(Mandatory = $false)][string]$TestSelector = '',
    [Parameter(Mandatory = $true)][int]$Iterations
)

# GitHub's pwsh wrapper sets $ErrorActionPreference='Stop' and appends
# `exit $LASTEXITCODE`. Both are wrong for a tallying loop: a failing
# iteration is the data, not an error, and on pwsh 7.3+ merging a native
# command's stderr under Stop can abort the step outright.
$ErrorActionPreference = 'Continue'
if (Test-Path variable:\PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$forkTimeout = if ($env:FORK_TIMEOUT_SECONDS) { $env:FORK_TIMEOUT_SECONDS } else { '120' }
$runTimeout = if ($env:RUN_TIMEOUT_SECONDS) { [int]$env:RUN_TIMEOUT_SECONDS } else { 600 }
$reuseForks = if ($env:REUSE_FORKS) { $env:REUSE_FORKS } else { 'true' }

# Splatted from an array, not written inline: passed as a bare token,
# PowerShell splits -Dsurefire.timeout=120 at the dot and Maven receives
# "-Dsurefire" plus ".timeout=120", which it rejects as an unknown lifecycle
# phase - a whole measurement can fail that way without a single test running.
# Array elements are passed through verbatim.
$mvnArgs = @('-B', 'test', "-Dsurefire.timeout=$forkTimeout")
if ($TestSelector) {
    $mvnArgs += "-Dtest=$TestSelector"
    $mvnArgs += '-Dsurefire.failIfNoSpecifiedTests=false'
}
if ($reuseForks -eq 'false') {
    $mvnArgs += '-DreuseForks=false'
}

New-Item -ItemType Directory -Force -Path logs | Out-Null

$rows = @()
$failed = 0
$hung = 0
$tally = @{}

for ($i = 1; $i -le $Iterations; $i++) {
    $log = "logs/iteration-$i.log"
    Write-Host "::group::iteration $i of $Iterations"

    $started = Get-Date
    # Run through a real Process handle rather than calling mvn inline: only
    # that can be waited on with a timeout and then killed. Inline, a fork the
    # Surefire cap fails to reach takes the whole job down with it - one
    # iteration of ten, and the other nine never happen.
    $proc = Start-Process -FilePath 'mvn.cmd' -ArgumentList $mvnArgs `
        -PassThru -NoNewWindow -RedirectStandardOutput $log `
        -RedirectStandardError "$log.err"
    if ($proc.WaitForExit($runTimeout * 1000)) {
        $code = $proc.ExitCode
        $killed = $false
    } else {
        # Kill the tree: the wedged fork is a child of mvn, and leaving it
        # running would poison every iteration that follows.
        $proc.Kill($true)
        $proc.WaitForExit()
        $code = 124
        $killed = $true
    }
    Get-Content $log -ErrorAction SilentlyContinue | Write-Host
    $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds)
    if ($code -ne 0) { $failed++ }

    # Surefire's end-of-run summary lists each failure as
    # "[ERROR]   SomeTest.someMethod:123 message". Unanchored on purpose:
    # anchoring at line start would silently report "no failures" if anything
    # ever prefixes the captured output, and a false clean result is the worst
    # failure mode a flake tally can have.
    $names = Select-String -Path $log -Pattern '\[ERROR\]\s+(\w+Test\.\w+)' |
               ForEach-Object { $_.Matches[0].Groups[1].Value } |
               Sort-Object -Unique

    # A capped fork reports "There was a timeout in the fork" and names no
    # test, so without this a hung iteration would show a failing exit code
    # beside an empty failure list - precisely the blank the cap exists to
    # capture.
    if (Select-String -Path $log -Pattern 'There was a timeout in the fork' -Quiet) {
        $names = @($names) + '<fork timeout>' | Where-Object { $_ }
        $hung++
    }
    # The outer cap fired: Maven was killed before reporting anything.
    if ($killed) {
        $names = @($names) + "<killed at ${runTimeout}s>" | Where-Object { $_ }
        $hung++
    }
    # A crashed fork names no test either, and unlike a fork timeout it says
    # so in Maven's own words rather than Surefire's.
    if (Select-String -Path $log -Pattern 'The forked VM terminated without properly saying goodbye' -Quiet) {
        $names = @($names) + '<fork crashed>' | Where-Object { $_ }
    }

    # A crashing JVM writes hs_err_pid<n>.log into its own working directory,
    # which for a Surefire fork is the module that launched it - not into the
    # captured output, and not into logs/. Nothing collected them, so the one
    # fork crash measured so far is a dead end: the artifact has the iteration
    # log, which only says the fork went away. Move whatever is there under
    # the iteration's name so the next crash arrives with its own report.
    # Collected unconditionally: the crash file is the better evidence that a
    # fork died, and Maven does not always phrase it the way matched above.
    Get-ChildItem -Path . -Recurse -Depth 2 -File -ErrorAction SilentlyContinue `
            -Include 'hs_err_pid*.log', 'replay_pid*.log' |
        Where-Object { $_.DirectoryName -ne (Join-Path $PWD 'logs') } |
        ForEach-Object {
            Move-Item -LiteralPath $_.FullName -Force `
                -Destination "logs/iteration-$i-$($_.Name)"
            Write-Host "collected $($_.Name) from $($_.DirectoryName)"
        }
    # A failing iteration in which no test ran at all means Maven itself did
    # not get going - a bad argument, a broken workspace. Saying so separates
    # it from "tests ran and passed", which is what an empty list reads as.
    if ($code -ne 0 -and -not (Select-String -Path $log -Pattern 'Tests run:' -Quiet)) {
        $names = @($names) + '<no tests ran>' | Where-Object { $_ }
    }
    # Last resort. A failing iteration that named nothing would otherwise be
    # tallied as a failure with no cause, which reads as though the run was
    # clean - the one reading a flake tally must never produce.
    if ($code -ne 0 -and -not $names) {
        $names = @('<failed, no test named - see the artifact>')
    }

    foreach ($n in $names) {
        $tally[$n] = 1 + $(if ($tally.ContainsKey($n)) { $tally[$n] } else { 0 })
    }
    $joined = $(if ($names) { $names -join ', ' } else { '-' })
    $rows += [pscustomobject][ordered]@{
        iteration = $i; exit = $code; seconds = "${seconds}s"; failedTests = $joined
    }

    # The failing test's name alone does not say why it failed, and chasing
    # that into the uploaded artifact is a slow round trip when the answer is
    # usually one assertion line. Print just those.
    if ($code -ne 0) {
        Select-String -Path $log -Pattern '^\[ERROR\]   ', 'AssertionFailedError', 'There was a timeout in the fork' |
            ForEach-Object { $_.Line } |
            Sort-Object -Unique |
            Select-Object -First 10 |
            ForEach-Object { Write-Host $_ }
    }

    Remove-Item "$log.err" -ErrorAction SilentlyContinue

    Write-Host "::endgroup::"
    Write-Host "iteration $i => exit $code, ${seconds}s, failed: $joined"
}

$summary = @()
$summary += "## On real Windows: $failed of $Iterations iterations failed"
$summary += ''
if ($tally.Count -gt 0) {
    $summary += '### Flake rate per test'
    $summary += ''
    $summary += '| test | failed in |'
    $summary += '| --- | --- |'
    foreach ($k in ($tally.Keys | Sort-Object { -$tally[$_] })) {
        $summary += "| $k | $($tally[$k]) of $Iterations |"
    }
    $summary += ''
}
$summary += '### Per iteration'
$summary += ''
$cols = $rows[0].PSObject.Properties.Name
$summary += '| ' + ($cols -join ' | ') + ' |'
$summary += '| ' + (($cols | ForEach-Object { '---' }) -join ' | ') + ' |'
foreach ($r in $rows) {
    $summary += '| ' + (($cols | ForEach-Object { $r.$_ }) -join ' | ') + ' |'
}

$summary | ForEach-Object { Write-Host $_ }
if ($env:GITHUB_STEP_SUMMARY) { $summary | Out-File -FilePath $env:GITHUB_STEP_SUMMARY -Append }

# The step exits 0 whatever happened, because the tally is the result and a
# red iteration must not redden the measurement. That leaves a green check
# above a log full of failures, which reads as "nothing wrong" to anyone not
# looking closely - so say it in an annotation, which shows on the run's
# summary page without changing the job's outcome.
if ($failed -gt 0) {
    Write-Host "::warning title=Windows: $failed of $Iterations iterations failed::This job is green by design - the rate is the result. See its tables, and the Verdict job for what it means."
}

if ($env:GITHUB_OUTPUT) {
    "iterations=$Iterations", "failed=$failed", "hung=$hung" |
        Out-File -FilePath $env:GITHUB_OUTPUT -Append
}

# The tally IS the result, so a red iteration must not redden the step.
exit 0

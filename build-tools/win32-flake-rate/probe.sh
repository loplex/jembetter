#!/usr/bin/env bash
#
# Repeats a Maven test run and tallies what failed in which iteration. The
# Linux/Wine half of the Win32 flake-rate measurement; probe.ps1 is the
# real-Windows half and is kept deliberately symmetrical with it.
#
# Usage: probe.sh <-Dtest selector, or empty for the whole suite> <iterations>
#
# Environment:
#   FORK_TIMEOUT_SECONDS  -Dsurefire.timeout, caps a fork that stops making
#                         progress (default 120). Measured not to reach a
#                         wedged Wine-hosted fork: Surefire launches it through
#                         test-jvm-wrapper/bin/java, which spawns an X server
#                         and then wine, so the process Surefire can signal is
#                         not the one that is stuck. Kept for the cases it does
#                         catch; RUN_TIMEOUT_SECONDS is the real cap here.
#   RUN_TIMEOUT_SECONDS   kills Maven itself (default 600) - on this platform
#                         the mechanism that actually ends a hung iteration
#   REUSE_FORKS           "false" runs one JVM per test class (default "true")
#
# A failing or hung iteration is the measurement, not an error: the loop never
# stops early and the script exits 0 unless it could not run at all. Read the
# tables it writes, not its exit code.

set -u

TEST_SELECTOR="${1-}"
ITERATIONS="${2:?usage: probe.sh <-Dtest selector or empty> <iterations>}"
FORK_TIMEOUT="${FORK_TIMEOUT_SECONDS:-120}"
RUN_TIMEOUT="${RUN_TIMEOUT_SECONDS:-600}"
REUSE_FORKS="${REUSE_FORKS:-true}"

mkdir -p logs

mvn_args=(-B test "-Dsurefire.timeout=${FORK_TIMEOUT}")
if [ -n "$TEST_SELECTOR" ]; then
    mvn_args+=("-Dtest=${TEST_SELECTOR}" -Dsurefire.failIfNoSpecifiedTests=false)
fi
if [ "$REUSE_FORKS" = "false" ]; then
    mvn_args+=(-DreuseForks=false)
fi

failed=0
hung=0
total_tests=0
barren=0
declare -A tally=()
rows=()

for i in $(seq 1 "$ITERATIONS"); do
    log="logs/iteration-$i.log"
    echo "::group::iteration $i of $ITERATIONS"

    started=$(date +%s)
    timeout -k 10 "$RUN_TIMEOUT" mvn "${mvn_args[@]}" > "$log" 2>&1
    code=$?
    seconds=$(( $(date +%s) - started ))

    names=""
    # Surefire's end-of-run summary lists each failure as
    # "[ERROR]   SomeTest.someMethod:123 message". Unanchored on purpose:
    # anchoring at line start would silently report "no failures" if anything
    # ever prefixes the captured output, and a false clean result is the worst
    # failure mode a flake tally can have.
    names=$(grep -oE '\[ERROR\][[:space:]]+([A-Za-z0-9_]+Test\.[A-Za-z0-9_]+)' "$log" \
            | sed -E 's/.*[[:space:]]//' | sort -u || true)

    # Either cap ending an iteration counts as one hang, and an iteration that
    # hits both counts once. The count has to mean the same thing as the one
    # probe.ps1 produces, because the verdict job compares the two platforms and
    # a number that counts different endings on each is not comparable. Keep
    # this rule and probe.ps1's in step.
    hung_this_iter=0

    # A capped fork reports "There was a timeout in the fork" and names no
    # test, so without this a hung iteration would show a failing exit code
    # beside an empty failure list - precisely the blank the cap exists to
    # capture.
    if grep -q "There was a timeout in the fork" "$log"; then
        names=$(printf '%s\n<fork timeout>' "$names")
        hung_this_iter=1
    fi
    # The outer cap fired: Maven was killed before reporting anything. Under
    # Wine this is the usual way a hung iteration ends, not an exceptional one.
    if [ "$code" -eq 124 ]; then
        names=$(printf '%s\n<killed at %ss>' "$names" "$RUN_TIMEOUT")
        hung_this_iter=1
    fi
    if [ "$hung_this_iter" -eq 1 ]; then
        hung=$((hung + 1))
    fi
    # A crashed fork names no test either, and unlike a fork timeout it says
    # so in Maven's own words rather than Surefire's.
    if grep -q "The forked VM terminated without properly saying goodbye" "$log"; then
        names=$(printf '%s\n<fork crashed>' "$names")
    fi

    # A crashing JVM writes hs_err_pid<n>.log into its own working directory,
    # which for a Surefire fork is the module that launched it - not into the
    # captured output, and not into logs/. Nothing collected them, so the one
    # fork crash measured so far is a dead end: the artifact has the iteration
    # log, which only says the fork went away. Move whatever is there under
    # the iteration's name so the next crash arrives with its own report.
    # Collected unconditionally: the crash file is the better evidence that a
    # fork died, and Maven does not always phrase it the way matched above.
    while IFS= read -r crash; do
        [ -z "$crash" ] && continue
        mv "$crash" "logs/iteration-$i-$(basename "$crash")"
        echo "collected $(basename "$crash") from ${crash%/*}"
    done < <(find . -maxdepth 3 \( -name 'hs_err_pid*.log' -o -name 'replay_pid*.log' \) \
             -not -path './logs/*' 2>/dev/null)
    # How many tests actually ran, summed over Surefire's per-class summary
    # lines. Carried into the table below because "did this measure anything"
    # is otherwise invisible: a selector matching no class exits 0 under
    # -Dsurefire.failIfNoSpecifiedTests=false, so every iteration passes, the
    # verdict reads "clean on both", and nothing on the page says that nothing
    # ran. It has happened, and what gave it away was the job's duration.
    tests_run=$(grep -E "Tests run: .* -- in " "$log" \
                | sed -E 's/.*Tests run: ([0-9]+),.*/\1/' \
                | awk '{ s += $1 } END { print s + 0 }')

    # An iteration that ran no test is a failed measurement whatever Maven's
    # exit code says - a bad selector and a broken workspace are both worth
    # hearing about, and only one of them reddens the exit code.
    total_tests=$(( total_tests + tests_run ))
    if [ "$tests_run" -eq 0 ]; then
        barren=$(( barren + 1 ))
        names=$(printf '%s\n<no tests ran>' "$names")
    fi

    names=$(echo "$names" | sed '/^$/d')
    # Last resort. A failing iteration that named nothing would otherwise be
    # tallied as a failure with no cause, which reads as though the run was
    # clean - the one reading a flake tally must never produce. It happened:
    # a crashed fork went in as "failed: -" and cost a round trip through the
    # uploaded artifact to identify.
    if [ "$code" -ne 0 ] && [ -z "$names" ]; then
        names="<failed, no test named - see the artifact>"
    fi
    if [ "$code" -ne 0 ]; then
        failed=$((failed + 1))
    fi

    while IFS= read -r n; do
        [ -z "$n" ] && continue
        tally["$n"]=$(( ${tally["$n"]:-0} + 1 ))
    done <<< "$names"

    joined=$(echo "$names" | paste -sd ', ' -)
    [ -z "$joined" ] && joined="-"
    rows+=("| $i | $code | ${seconds}s | $tests_run | $joined |")

    # The failing test's name alone does not say why it failed, and chasing
    # that into the uploaded artifact is a slow round trip when the answer is
    # usually one assertion line. Print just those.
    if [ "$code" -ne 0 ]; then
        grep -E "^\[ERROR\]   |AssertionFailedError|There was a timeout in the fork" "$log" \
            | sort -u | head -10
    fi

    # Which classes ran, inside the iteration's own collapsed group. The
    # Windows half echoes Maven's whole output and so answers this for free;
    # this half sends it to a file, which left "what actually ran" readable
    # only by downloading the artifact. These are the lines worth having.
    grep -E "Tests run: .* -- in " "$log" | sed -E 's/.*-- in /  /' \
        | sort -u || true

    echo "::endgroup::"
    echo "iteration $i => exit $code, ${seconds}s, $tests_run tests, failed: $joined"
done

emit_report() {
    echo "## Under Wine: $failed of $ITERATIONS iterations failed"
    echo
    if [ "${#tally[@]}" -gt 0 ]; then
        echo "### Flake rate per test"
        echo
        echo '| test | failed in |'
        echo '| --- | --- |'
        for k in "${!tally[@]}"; do
            echo "| $k | ${tally[$k]} of $ITERATIONS |"
        done | sort -t'|' -k3 -rn
        echo
    fi
    echo "### Per iteration"
    echo
    echo '| iteration | exit | seconds | tests | failed tests |'
    echo '| --- | --- | --- | ---: | --- |'
    printf '%s\n' "${rows[@]}"
}

emit_report
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    emit_report >> "$GITHUB_STEP_SUMMARY"
fi

# The step exits 0 whatever happened, because the tally is the result and a
# red iteration must not redden the measurement. That leaves a green check
# above a log full of failures, which reads as "nothing wrong" to anyone not
# looking closely - so say it in an annotation, which shows on the run's
# summary page without changing the job's outcome.
if [ "$failed" -gt 0 ]; then
    echo "::warning title=Wine: $failed of $ITERATIONS iterations failed::This job is green by design - the rate is the result. See its tables, and the Verdict job for what it means."
fi

# Louder than a table row, because this one invalidates the whole measurement
# rather than describing it: iterations that ran no test pass, so they leave
# the failure count at zero and the verdict reads clean.
if [ "$barren" -gt 0 ]; then
    echo "::error title=Wine: $barren of $ITERATIONS iterations ran no tests::Nothing was measured. Check the -Dtest selector before reading anything else on this page."
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
        echo "iterations=$ITERATIONS"
        echo "failed=$failed"
        echo "hung=$hung"
        echo "tests=$total_tests"
    } >> "$GITHUB_OUTPUT"
fi

# The tally IS the result, so a red iteration must not redden the step.
exit 0

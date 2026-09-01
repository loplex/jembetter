#!/usr/bin/env bash
set -eu

# Spawns given X Server and `openbox` X Window Manager processes, runs the
# given command with the given arguments, then ensures both are properly
# terminated and cleaned up.

# input env. variables:
#   X_SERVER:
#     a) "" (empty) - the caller's own DISPLAY
#     b) <xserver_command> - e.g. '/usr/bin/Xvfb' or 'Xephyr'

# output (modified) env. variables:
#   DISPLAY: X11 display address (e.g. :99)

# This script is run by the java executable wrapper (`./bin/java`) used as
# fake `java` command for `maven-surefire-plugin`'s <jvm> configuration
# property (see root `pom.xml` and `./bin/java`).


declare -ri TERM_TO_KILL_SECONDS=5
declare -ri X_DISPLAY_NUMBER_TIMEOUT_SECONDS=10

declare -g xserver_pid='' wm_pid=''
declare -ag child_pids=()
declare -xg DISPLAY

# Xvfb and openbox emit their own boot/shutdown chatter (keysym warnings,
# amdgpu probing, ObRender messages, ...) on every single fork, success or
# failure - collected here instead of relaying it straight to this script's
# own stderr (which surefire's ForkStarter relays straight to the console),
# so a normal edit-test loop isn't re-paying for the same boilerplate on
# every fork. Dumped to stderr only if the wrapped command ends up failing
# (see bottom of this script) and removed by cleanup() either way.
declare -g xserver_log=''


function run_debug() {
  echo 'spawn-xserver.sh: +' "${@@Q}" >&2
  "$@" >&2
}


# check if support child process is still running and if not then unset pid variable value
function refresh_pid() {
  local pidRef=$1
  local -n pid=${pidRef}

  if [[ -z "${pid}" ]]; then
    return 0
  elif kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  else
    printf 'spawn-xserver.sh: Child process %s exited\n' "${pid}" >&2
    pid=''
    return 0
  fi
}

# terminate a child process, giving it TERM_TO_KILL_SECONDS to exit on its own
# (SIGTERM) before force-killing it (SIGKILL)
function kill_cleanly() {
  local pidRef=$1
  local -n pid=${pidRef}

  if refresh_pid "${pidRef}"; then return; fi
  printf 'spawn-xserver.sh: Terminating: %s\n' "${pid}" >&2
  kill "${pid}" >&2
  sleep "${TERM_TO_KILL_SECONDS}" >&2 &
  wait -fn "${pid}" "$!"

  if refresh_pid "${pidRef}"; then return; fi
  printf 'spawn-xserver.sh: Killing: %s\n' "${pid}" >&2
  kill -KILL "${pid}" >&2

  refresh_pid "${pidRef}" || true
}

# check if support child processes (X server and X window manager) are still running
function refresh_pids() {
  if [[ -n "${xserver_pid}" ]] && ! kill -0 "${xserver_pid}" >/dev/null 2>&1; then
    printf 'spawn-xserver.sh: Child %s process (pid %s) exited\n' 'X Server' "${xserver_pid}" >&2
    xserver_pid=''
  fi
  if [[ -n "${wm_pid}" ]] && ! kill -0 "${wm_pid}" >/dev/null 2>&1; then
    printf 'spawn-xserver.sh: Child %s process (pid %s) exited\n' 'X Window Manager' "${wm_pid}" >&2
    wm_pid=''
  fi

  child_pids=( ${xserver_pid:+"${xserver_pid}"} ${wm_pid:+"${wm_pid}"} )
  return $(( ${#child_pids[@]} ))
}

# kill support child processes (X server and X window manager) after this script exits
function cleanup() {
  { set +x +e; } 2>/dev/null

  if [[ -n "${xserver_log}" ]]; then
    rm -f "${xserver_log}"
  fi

  if refresh_pids; then
    echo 'spawn-xserver.sh: Nothing to be cleaned up - no child process running.' >&2
    return
  fi

  declare to_kill_str; printf -v to_kill_str '%s' \
      "${xserver_pid:+"X server (pid ${xserver_pid})", }" \
      "${wm_pid:+"openbox X window manager (pid ${wm_pid}), "}"

  printf 'spawn-xserver.sh: Cleaning up %s leftover running child processes: %s\n' \
    "${#child_pids[@]}" "${to_kill_str%", "}" >&2

  kill_cleanly wm_pid
  kill_cleanly xserver_pid
}


# check prerequisite commands - selected X server and openbox as X window manager
function pre_check() {
  if ! command -v "${X_SERVER}" >/dev/null 2>&1; then
    echo "spawn-xserver.sh: X_SERVER=${X_SERVER} but such command not found" \
      "(install it, provide full path or set X_SERVER='' to run on your host X server)" >&2
    return 1
  elif ! command -v 'openbox' >/dev/null 2>&1; then
    echo "spawn-xserver.sh: X_SERVER=${X_SERVER} but 'openbox' command not found" \
      "(install it or set X_SERVER='' to run on your host X server without needing it)" >&2
    return 2
  fi
}

# spawn selected X server on background, wait until it reports display number and set DISPLAY env. variable (or timeout)
function spawn_x_server() {
  xserver_log=$( mktemp "${TMPDIR:-/tmp}/jembetter-xserver-boot.XXXXXX" )

  # spawn selected X server on background using Bash coprocess
  local -a X_SRV; local X_SRV_PID
  coproc X_SRV {
    # handshake_fd keeps a dup of this coprocess's real stdout (read by the
    # parent below to learn the display number) - fd 1 and fd 2 are then
    # both pointed at xserver_log so Xvfb's own output (boot chatter now,
    # shutdown chatter later - same process, same fds throughout its life)
    # lands there instead of on this script's own stderr.
    exec {handshake_fd}>&1 2>>"${xserver_log}" 1>&2
    run_debug exec "${X_SERVER}" -displayfd "${handshake_fd}"
  }
  xserver_pid=${X_SRV_PID}

  # wait as much as X_DISPLAY_NUMBER_TIMEOUT_SECONDS for X server to report it's display number
  local display_num
  IFS= read -r -t "${X_DISPLAY_NUMBER_TIMEOUT_SECONDS}" display_num <&"${X_SRV[0]}" || true

  # check we got a valid display number
  if [[ "${display_num}" =~ ^[0-9]+$ ]]; then
    echo "spawn-xserver.sh: ${X_SERVER} reported display number: ${display_num}" >&2
    DISPLAY=":${display_num}"
  else
    printf "spawn-xserver.sh: ERROR - %s didn't report valid display number %s- cannot continue!\n" \
      "${X_SERVER}" "${display_num:+"(${display_num}) "}" >&2
    return 1
  fi

  # check if X server still running
  if ! kill -0 "${xserver_pid}" >/dev/null 2>&1; then
    echo "spawn-xserver.sh: ERROR - ${X_SERVER} crashed - cannot continue!" >&2
    return 2
  fi
}

# spawn 'openbox' as X window manager for X server
function spawn_x_window_manager() {
  echo 'spawn-xserver.sh: + openbox --sm-disable' >&2
  openbox --sm-disable >>"${xserver_log}" 2>&1 &
  wm_pid=$!

  # check if it is running
  if ! kill -0 "${wm_pid}" >/dev/null 2>&1; then
    echo "spawn-xserver.sh: ERROR - 'openbox' did not start successfully - cannot continue!" >&2
    return 2
  fi
}


# exit with the given code, surfacing the collected Xvfb/openbox boot/shutdown
# chatter first if that exit is a failure one - on success it's just discarded
# (by cleanup()'s EXIT trap) unread. Used for every exit path below so a
# failure during X server/window manager setup itself stays as debuggable as
# a failure in the wrapped command.
function finish() {
  local -ri rc=$1

  if (( rc != 0 )) && [[ -n "${xserver_log}" && -s "${xserver_log}" ]]; then
    printf 'spawn-xserver.sh: exiting %s - X server/window manager output follows:\n' "${rc}" >&2
    cat "${xserver_log}" >&2
  fi

  exit "${rc}"
}


### MAIN ###

# ensure no child processes leave running when this script exits
trap cleanup EXIT

# see provide-windows-java-using-wine.sh's run_java for why SIGPIPE is ignored here too
trap '' PIPE

# based on X_SERVER env. variable spawn new X server and X window manager and set up DISPLAY
if [[ -n "${X_SERVER-}" ]]; then

  echo "spawn-xserver.sh: X_SERVER=${X_SERVER} will be used" >&2

  pre_check              || finish 1
  spawn_x_server         || finish 2
  spawn_x_window_manager || finish 3

else

  echo "spawn-xserver.sh: X_SERVER not set or empty => your host X server (DISPLAY=${DISPLAY}) will be used" >&2
fi


# start passed command with arguments, propagating its exit code as this
# script's own - surefire's ForkStarter treats any nonzero exit from the
# forked process's own executable as "the fork failed to start" (see
# ForkStarter.fork()'s awaitExit() check), regardless of whether tests
# actually ran fine over the fork channel, so falling off the end here
# without forwarding "$@"'s real exit code would misreport success/failure.
#
# "$@" is deliberately the non-last element of an OR-list here (rather than
# a bare command followed by `rc=$?`) - under `set -e`, a bare failing
# command aborts the script immediately, which would skip straight past
# `finish` (and its failure-dump logic below) to the EXIT trap, silently
# losing the exit-code-aware log dump on exactly the runs that need it most.
declare -i rc=0
"$@" || rc=$?
finish "${rc}"

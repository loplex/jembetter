#!/usr/bin/env bash

# Spawns given X Server and `openbox` X Window Manager processes.
# Also ensures they are properly terminated and cleaned up when finished (on exit).

# input env. variables:
#   X_SERVER:
#     a) "" (empty) - the caller's own DISPLAY
#     b) <xserver_command> - e.g. '/usr/bin/Xvfb' or 'Xephyr'

# output (modified) env. variables:
#   DISPLAY: X11 display address (e.g. :99)

# It is sourced by java executable wrapper (`./bin/java`) used as fake `java`
# command for `maven-surefire-plugin`'s <jvm> configuration property
# (see root `pom.xml` and `./bin/java`)

xserver_pid=""
wm_pid=""
cleanup() {
  if [[ -n "$wm_pid" ]]; then kill "$wm_pid" 2>/dev/null || true; fi
  if [[ -n "$xserver_pid" ]]; then kill "$xserver_pid" 2>/dev/null || true; fi
  wait 2>/dev/null || true
}
trap cleanup EXIT

if [[ -n "${X_SERVER:-}" ]]; then

  for comm in "${X_SERVER}" openbox; do
    if ! command -v "$comm" >/dev/null 2>&1; then
      echo "spawn-xserver.sh: X_SERVER=${X_SERVER} but '$comm' isn't on PATH" \
        "(install it, or set X_SERVER='' to skip the disposable X server and run headless)" >&2
      exit 1
    fi
  done

  # -displayfd has the X server itself pick a free display number (and
  # claim its lock/socket) atomically, instead of us scanning
  # /tmp/.X##-lock ourselves and racing another instance of this script.
  displayfd_pipe=$(mktemp -u)
  mkfifo -m 600 "$displayfd_pipe"

  "${X_SERVER}" -displayfd 9 9>"$displayfd_pipe" >/dev/null 2>&1 &

  xserver_pid=$!

  IFS= read -r -t 5 display_num <"${displayfd_pipe}" || true
  rm -f "$displayfd_pipe"

  if [[ "$display_num" =~ ^[0-9]+$ ]] && kill -0 "$xserver_pid" 2>/dev/null; then
    chosen_display=":${display_num}"

    up=false
    for _ in $(seq 1 50); do
      DISPLAY="$chosen_display" xdpyinfo >/dev/null 2>&1 && { up=true; break; }
      sleep 0.1
    done

    if [[ "$up" == true ]]; then
      DISPLAY="$chosen_display" openbox --sm-disable >/dev/null 2>&1 &
      wm_pid=$!
      sleep 0.5
      DISPLAY="$chosen_display"
      export DISPLAY
    else
      kill "$xserver_pid" 2>/dev/null || true
      xserver_pid=""
      echo "spawn-xserver.sh: ${X_SERVER} on $chosen_display never came up," \
        "leaving DISPLAY unset" >&2
    fi
  else
    kill "$xserver_pid" 2>/dev/null || true
    xserver_pid=""
    echo "spawn-xserver.sh: ${X_SERVER} -displayfd never reported a display number," \
      "leaving DISPLAY unset" >&2
  fi
fi

#!/usr/bin/env bash
# One-off (not wired into `mvn test`) verification that jembetter-core-win32's
# JNA calls actually reach real user32.dll/kernel32.dll entry points, using
# a real Windows PE JVM (a downloaded Windows JDK, NOT the host Linux JDK)
# run under Wine - this is a smoke test, not a substitute for verifying the
# Win32 backend on a real Windows machine (see "What this actually proves"
# below).
#
# What this actually proves on a pass: the JNA bindings link against real
# user32/kernel32 (not just type-check against JNA's Java-side declarations)
# and that CreateWindowEx/SetParent/MoveWindow/EnumWindows/SetFocus mechanics
# plausibly work, since Wine's winex11.drv renders Win32 windows as real X11
# windows on Linux. It does NOT prove anything about Windows' foreground-lock
# restriction on SetFocus/SetForegroundWindow (Wine doesn't faithfully
# replicate that policy) or any real-Windows-version-specific behavior - see
# Win32Focus's Javadoc.
#
# Win32ClickWatcherTest is the exception: its click-to-focus mechanism
# (a WH_MOUSE_LL low-level mouse hook) was driven out entirely against this
# harness, with real clicks injected via SendInput, and it *does* prove the
# hook installs, the LowLevelMouseProc/MSLLHOOKSTRUCT marshaling works, the
# message pump dispatches, the hit-test is correct, and close() unhooks
# cleanly - see Win32ClickWatcher's Javadoc for what's still unconfirmed
# (system-wide mouse latency, UIPI) beyond what Wine can tell you.
#
# It runs the real JUnit test classes in jembetter-core-win32 (the same ones
# @EnabledOnOs(OS.WINDOWS)-gated out of this repo's normal `mvn test` on
# Linux) via junit-platform-console-standalone, a self-contained jar that
# bundles the JUnit engine so no dependency resolution has to happen inside
# the Windows-JDK-under-Wine JVM itself. Everything downloaded is cached in
# ./.cache (gitignored) next to this script; delete that directory to force
# a re-download.
#
# Usage: .mvn/win32-wine-smoketest/run.sh [console-launcher args...]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE_DIR="${WIN32_SMOKETEST_CACHE:-$SCRIPT_DIR/.cache}"

WIN_JDK_URL="${WIN_JDK_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse}"
JUNIT_CONSOLE_VERSION="1.11.3"
JUNIT_CONSOLE_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_CONSOLE_VERSION}/junit-platform-console-standalone-${JUNIT_CONSOLE_VERSION}.jar"

for cmd in wine winepath curl unzip mvn Xvfb openbox; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "win32-wine-smoketest: '$cmd' not found on PATH" >&2
    exit 1
  }
done

mkdir -p "$CACHE_DIR"

jdk_zip="$CACHE_DIR/windows-jdk.zip"
if [[ ! -s "$jdk_zip" ]]; then
  echo "win32-wine-smoketest: downloading Windows JDK..." >&2
  curl -fL -o "${jdk_zip}.part" "$WIN_JDK_URL"
  mv "${jdk_zip}.part" "$jdk_zip"
fi

jdk_dir="$CACHE_DIR/jdk"
if [[ ! -d "$jdk_dir" ]]; then
  echo "win32-wine-smoketest: extracting Windows JDK..." >&2
  mkdir -p "$jdk_dir"
  unzip -q "$jdk_zip" -d "$jdk_dir"
fi
jdk_home="$(find "$jdk_dir" -mindepth 1 -maxdepth 1 -type d -name 'jdk-*' | head -1)"
if [[ -z "$jdk_home" || ! -f "$jdk_home/bin/java.exe" ]]; then
  echo "win32-wine-smoketest: no bin/java.exe found under $jdk_dir - bad/incomplete zip?" >&2
  exit 1
fi

junit_console_jar="$CACHE_DIR/junit-platform-console-standalone-${JUNIT_CONSOLE_VERSION}.jar"
if [[ ! -s "$junit_console_jar" ]]; then
  echo "win32-wine-smoketest: downloading junit-platform-console-standalone..." >&2
  curl -fL -o "${junit_console_jar}.part" "$JUNIT_CONSOLE_URL"
  mv "${junit_console_jar}.part" "$junit_console_jar"
fi

echo "win32-wine-smoketest: compiling jembetter-core-win32 (+ its main-code deps)..." >&2
mvn -q -f "$REPO_ROOT/pom.xml" -pl jembetter-core-common,jembetter-core-win32 -am test-compile

jna_jar="$(find "$HOME/.m2/repository/net/java/dev/jna/jna" -name 'jna-*.jar' \
  ! -name '*sources*' ! -name '*javadoc*' | sort -V | tail -1)"
jna_platform_jar="$(find "$HOME/.m2/repository/net/java/dev/jna/jna-platform" -name 'jna-platform-*.jar' \
  ! -name '*sources*' ! -name '*javadoc*' | sort -V | tail -1)"
if [[ -z "$jna_jar" || -z "$jna_platform_jar" ]]; then
  echo "win32-wine-smoketest: jna/jna-platform jars not found in ~/.m2 - run 'mvn install' first" >&2
  exit 1
fi

cp_unix=(
  "$REPO_ROOT/jembetter-core-win32/target/classes"
  "$REPO_ROOT/jembetter-core-win32/target/test-classes"
  "$REPO_ROOT/jembetter-core-common/target/classes"
  "$jna_jar"
  "$jna_platform_jar"
  "$junit_console_jar"
)
cp_win=""
for p in "${cp_unix[@]}"; do
  w="$(winepath -w "$p" 2>/dev/null)"
  cp_win="${cp_win:+$cp_win;}$w"
done

# Private, disposable Xvfb + openbox pair, same reasoning as
# .mvn/xserver-jvm-wrapper/bin/java on the X11 side: Wine's winex11.drv
# needs a real X11 DISPLAY to create windows against, and this must never be
# the caller's own live desktop session.
xserver_pid=""
wm_pid=""
cleanup() {
  [[ -n "$wm_pid" ]] && kill "$wm_pid" 2>/dev/null || true
  [[ -n "$xserver_pid" ]] && kill "$xserver_pid" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT

displayfd_pipe=$(mktemp -u)
mkfifo -m 600 "$displayfd_pipe"
Xvfb -displayfd 9 -screen 0 1280x1024x24 9>"$displayfd_pipe" >/dev/null 2>&1 &
xserver_pid=$!
display_num=$(timeout 5 cat "$displayfd_pipe" || true)
rm -f "$displayfd_pipe"

if ! [[ "$display_num" =~ ^[0-9]+$ ]] || ! kill -0 "$xserver_pid" 2>/dev/null; then
  echo "win32-wine-smoketest: Xvfb never reported a display number" >&2
  exit 1
fi
export DISPLAY=":${display_num}"

up=false
for _ in $(seq 1 50); do
  xdpyinfo >/dev/null 2>&1 && { up=true; break; }
  sleep 0.1
done
[[ "$up" == true ]] || { echo "win32-wine-smoketest: Xvfb on $DISPLAY never came up" >&2; exit 1; }

openbox --sm-disable >/dev/null 2>&1 &
wm_pid=$!
sleep 0.5

echo "win32-wine-smoketest: running jembetter-core-win32's JUnit tests under Wine on $DISPLAY..." >&2
export WINEDEBUG=-all
wine "$jdk_home/bin/java.exe" -cp "$cp_win" org.junit.platform.console.ConsoleLauncher \
  --select-package cz.loplex.jembetter.core.win32 --details=tree "$@"

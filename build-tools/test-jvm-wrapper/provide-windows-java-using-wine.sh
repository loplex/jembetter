#!/usr/bin/env bash
set -eu

# Provide Windows Java environment (`java.exe` command) on Linux using Wine

# input env. variables:
#   WINDOWS_JDK_HOME: Windows JDK install directory (defaults to the one
#     download-windows-java.sh caches under this wrapper dir)

# output functions:
#   run_java:
#     Runs the Windows `java.exe` under Wine, passing all arguments through
#     (after rewriting the Linux paths it's given into Windows form - see
#     winify_properties_file_paths below).

# This script is sourced by provide-platform-java.sh, which is in turn run
# by the java executable wrapper (`./bin/java`) used as fake `java` command
# for `maven-surefire-plugin`'s <jvm> configuration property (see root
# `pom.xml` and `./bin/java`)


wrapper_dir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

windows_jdk_home=${WINDOWS_JDK_HOME-}
if [[ -z "${windows_jdk_home}" ]]; then
  jdk_cache_dir=${WINDOWS_JDK_CACHE:-${wrapper_dir}/.cache}
  windows_jdk_home="${jdk_cache_dir}/extract/jdk"
fi

windows_jdk_java_exe="${windows_jdk_home}/bin/java.exe"

# Only download-windows-java.sh's own default cache location is safe to
# provision automatically here - an explicit WINDOWS_JDK_HOME/_CACHE means
# the caller is pointing at a JDK install of their own choosing, and a
# missing java.exe there is a configuration error, not a first-use.
if [[ -z "${WINDOWS_JDK_HOME-}" && -z "${WINDOWS_JDK_CACHE-}" ]] && ! [[ -s "${windows_jdk_java_exe}" ]]; then
  "${wrapper_dir}/download-windows-java.sh" >&2
fi

if ! [[ -s "${windows_jdk_java_exe}" ]]; then
  echo "provide-windows-java-using-wine.sh: ERROR - cannot find 'java.exe' at ${windows_jdk_java_exe}" >&2
  exit 1
fi

# Isolates this harness's Wine state from the user's own default WINEPREFIX
# (~/.wine) - sharing it would mean e.g. cleanup_wineserver below killing
# any unrelated Wine program the user had running outside the test run, and
# this workload (a plain java.exe) has no business touching the user's own
# Wine install anyway. Cached (gitignored, like the JDK download above) so
# it's only created once.
wine_prefix_cache=${WINE_PREFIX_CACHE:-${wrapper_dir}/.cache/wineprefix}
export WINEPREFIX=${wine_prefix_cache}

# A *new* WINEPREFIX's wineboot otherwise hangs indefinitely trying to
# install Wine Mono/Gecko (a GUI installer prompt that needs network access
# and user interaction, fatal in a headless/CI run) - neither is needed to
# run a plain java.exe, so disable both up front.
export WINEDLLOVERRIDES=${WINEDLLOVERRIDES:-"mscoree=;mshtml="}


function run_debug() {
  echo 'provide-windows-java-using-wine.sh: +' "${@@Q}" >&2
  "$@"
}

# Chains a new EXIT-trap handler onto whatever handler is already
# registered, rather than clobbering it the way a plain `trap ... EXIT`
# would - replay-wine-fork.sh sets its own EXIT trap (cleaning up its
# socat/tail bridge processes and temp property files) before sourcing this
# script, and a naive `trap ... EXIT` here would silently drop it.
function add_exit_trap() {
  local new_handler=$1

  # `trap -p EXIT` prints `trap -- '<handler>' EXIT` (empty if none is
  # registered yet) - strip that wrapping to get the bare handler back out.
  local existing; existing=$( trap -p EXIT )
  existing=${existing#"trap -- '"}
  existing=${existing%"' EXIT"}

  trap "${existing:+${existing}; }${new_handler}" EXIT
}

function ensure_wineprefix() {
  if [[ -s "${wine_prefix_cache}/system.reg" ]]; then
    return
  fi
  echo "provide-windows-java-using-wine.sh: initializing isolated WINEPREFIX at ${wine_prefix_cache}..." >&2
  mkdir -p "${wine_prefix_cache}"
  WINEDEBUG=-all run_debug wineboot --init >&2
}

# Tears down the wineserver instance (and the guest-OS processes under it -
# services.exe, explorer.exe, plugplay.exe, ... - Wine rewrites their argv[0]
# to the literal Windows path, so they don't show up in a plain `pgrep wine`)
# that `wine java.exe` implicitly started against WINEPREFIX above, so a
# finished test run doesn't leave them running afterward. `wineserver -k`
# only tears down the instance for the *currently exported* WINEPREFIX (each
# prefix gets its own, addressed via a prefix-derived socket) - so this only
# ever affects this harness's own isolated prefix, never the user's shared
# default one (see the WINEPREFIX comment above for why that distinction
# matters).
function cleanup_wineserver() {
  run_debug wineserver -k || true
}

# Runs cleanup_wineserver on every exit from this script - normal
# completion, a `set -e` failure, or this fork being killed by a signal
# (e.g. Surefire's forkedProcessTimeoutInSeconds, or `mvn` interrupted)
# while `wine java.exe` is still running. A plain call at the end of
# run_java, after that command returns, would miss exactly that last case -
# nothing past the point of being killed would ever run.
add_exit_trap cleanup_wineserver

# Undoes a properties file's escaping of a value (the only special character
# in a properties file *value* is the backslash - see encode_properties_value
# below), so it can be handed to `winepath` as a plain string.
function decode_properties_value() {
  local encoded=$1

  local rest=${encoded}
  while [[ "${rest}" =~ ^([^\\]*)"\\\\"([^u]|u[0-9a-fA-F]{4,4})(.*)$ ]]; do
    local noOpPrefix=${BASH_REMATCH[1]} escapedChar=${BASH_REMATCH[2]} rest=${BASH_REMATCH[3]}
    local decodedChar; printf -v decodedChar '%b' "\\${escapedChar}"
    printf '%s' "${noOpPrefix}" "${decodedChar}"
  done
  printf '%s' "${rest}"
}

# Escapes a raw value for safe use as a properties file value: the only
# special character there is the backslash, so replacing "\" with "\\" is
# the only transformation needed. `winepath -w`'s Windows paths come back
# using backslash separators (e.g. Z:\home\...), which this doubles up so
# the forked JVM's own properties parser decodes them back to a single
# literal backslash rather than corrupting the path.
function encode_properties_value() {
  local raw_value=$1
  printf '%s' "${raw_value//"\\"/"\\\\"}"
}

# merge lines which was split into multiple lines using backslash (\) at end of line
function merge_multi_lines() {
  sed -z 's|\\\n||g'
}

# Rewrites absolute Unix path values (lines like `key=/some/path`) in a
# surefire booter properties file to Windows paths.
#
# Surefire's parent (Maven) process writes these properties files itself,
# in Linux path format, then passes only their bare filenames (resolved
# against the dump directory) as arguments to the forked test JVM. Only
# the dump directory and the booter jar path are translated to Windows
# form by `run_java` below (they're passed as actual command-line
# arguments); the properties file *contents* (classPathUrl.N,
# reportsDirectory, testClassesDirectory, basedir, ...) are opaque to it
# and stay as Linux paths, which the wine-hosted JVM can't resolve. This
# translates them too, so the forked process gets a consistent, fully
# Windows-style view of the filesystem.
#
# TODO: known issue: will not work if some path contains a newline character (which is valid in a linux file name)
function winify_properties_file_paths() {
  local file=$1 output_file=$2

  # read all lines of the properties file
  local -a lines; readarray -t lines < <(
    # merge lines which was split to multiple using backslash (\) at end of line
    merge_multi_lines < "${file}"
  )

  # extract only unique values which are in form of linux absolute path
  local -a in_paths_encoded; readarray -t in_paths_encoded < <(
    printf '%s\n' "${lines[@]}" | sed -En 's|^([^=:#][^=:]*[=:])(/.*)$|\2|p' | sort -u
  )

  # build map of possible encoded linux path input values into corresponding encoded windows path output values
  local -a in_paths_decoded=()
  local in_path_encoded; for in_path_encoded in "${in_paths_encoded[@]}"; do
    in_paths_decoded+=( "$( decode_properties_value "${in_path_encoded}" )" )
  done
  local -a out_raw_windows_paths; readarray -t out_raw_windows_paths < <(
    winepath -w "${in_paths_decoded[@]}"
  )
  if (( ${#in_paths_encoded[@]} != ${#out_raw_windows_paths[@]} )); then
    echo 'provide-windows-java-using-wine.sh: Internal error while converting file paths from Linux to Windows form' >&2
    exit 1
  fi
  local -A paths_map=()
  local -i ind; for ind in "${!in_paths_encoded[@]}"; do
    local out_encoded_windows_path; out_encoded_windows_path=$( encode_properties_value "${out_raw_windows_paths[$ind]}" )
    paths_map+=( [${in_paths_encoded[$ind]}]="${out_encoded_windows_path}" )
  done

  # loop over all properties file lines
  local line; for line in "${lines[@]}"; do
    # rewrite those lines matching form of property (key=value) having values in form of a linux absolute file path
    if [[ "${line}" =~ ^([^=:#][^=:]*[=:])(/.*)$ ]]; then
      local key_and_separator=${BASH_REMATCH[1]} linux_path_value=${BASH_REMATCH[2]}
      local windows_path_value=${paths_map[$linux_path_value]}
      printf '%s%s\n' "${key_and_separator}" "${windows_path_value}"

    # the other lines just pass as they are
    else
      printf '%s\n' "${line}"
    fi
  done \
    > "${output_file}"
}

# arguments:
#   <java_args...>                              - e.g. --add-opens java.desktop/java.awt=ALL-UNNAMED
#   "-jar"
#   <jar_path>               |PATH_WINIFIED|    - e.g. /home/usr/project/target/surefire/surefirebooter-20260828212301299_26.jar
#     <surefire_dir_path>    |PATH_WINIFIED|    - e.g. /home/usr/project/target/surefire
#     <surefire_fork_id>                        - e.g. 2026-08-29T12-42-33_782-jvmRun1
#     <properties_files...>  |CONTENT_WINIFIED| - e.g. /home/usr/project/target/surefire
function run_java() {
  local -a args=()

  # pass all arguments before '-jar' argument inclusive
  while (( $# )); do
    args+=( "$1" )
    if [[ "$1" == "-jar" ]]; then break; fi
    shift
  done
  shift

  if (( $# < 3 )); then
    echo "provide-windows-java-using-wine.sh: ERROR: Unexpected arguments. Can't find '-jar' argument." >&2
    exit 1
  fi

  local jar_path=${1?} surefire_dir_path=${2?}
  local jar_path_relative; jar_path_relative=$( realpath --relative-to="${surefire_dir_path}" "${jar_path}" )

  # pass winified paths of <jar_path> and <surefire_dir_path>
  readarray -O ${#args[@]} -t args < <(
    winepath -w "${surefire_dir_path}/${jar_path_relative}" "${surefire_dir_path}"
  )
  shift 2

  # pass 'dump_file_name' argument (unknown purpose for me)
  local dump_file_name=${1?}
  args+=( "${dump_file_name[@]}" )
  shift 1

  # pass the rest of arguments (file names of properties files under <surefire_dir_path>)
  local -a prop_file_names=( "$@" )
  args+=( "${prop_file_names[@]/%/".win"}" )

  # winify values in properties files contents and save winified versions in new files with '.win' suffix appended
  local prop_file_name; for prop_file_name in "${prop_file_names[@]}"; do
    local out_file_name="${prop_file_name}.win"
    winify_properties_file_paths "${surefire_dir_path}/${prop_file_name}" "${surefire_dir_path}/${out_file_name}"
  done

  # copy the whole '<surefire_dir_path>' to '<surefire_dir_path>_win' which is not deleted and can be investigated
  cp -aTb "${surefire_dir_path}" "${surefire_dir_path}_win"

  # run `java.exe` with collected arguments
  echo "provide-windows-java-using-wine.sh: Running target 'java.exe' using wine:" >&2
  # Once surefire's ForkStarter (running SurefireForkNodeFactory, a TCP fork channel -
  # see root pom.xml's windows-tests-on-linux execution) is satisfied with what it read
  # from that channel, it closes its end of this process's stderr-relay pipe
  # (ForkStarter.bindErrorStream()) - independently of whether the OS process it
  # launched (this script, and everything it in turn spawned) has actually exited yet.
  # Under Wine, `wine java.exe`'s own shutdown is measurably slower than on a native
  # JVM, so a subsequent trace line here would routinely try to write to that
  # now-closed pipe while still waiting on `wine`. Bash's default action for the
  # resulting SIGPIPE is to terminate the whole script immediately, well before `wine`
  # exits on its own - surefire then reads this script's signal-terminated exit status
  # as "the fork failed to start" even though the tests behind it already ran fine and
  # reported their results over the (separate) TCP channel. Ignoring SIGPIPE keeps
  # trace output useful for local debugging; any other write to the same pipe hits the
  # same already-closed-pipe condition and simply becomes a no-op EPIPE instead of fatal.
  trap '' PIPE
  export WINEDEBUG='-all'
  run_debug wine "${windows_jdk_java_exe}" "${args[@]}" >&2
  # Propagate wine's own exit code as this script's exit code: surefire's ForkStarter
  # treats *any* nonzero exit from the forked process's own executable as "the fork
  # failed to start" (see ForkStarter.fork()'s awaitExit() check), regardless of
  # whether tests actually ran fine over the fork channel. Falling off the end of this
  # function without an explicit exit would instead report bash's own exit status,
  # which is wrong here.
  exit $?
}


# Needed before any winepath/wine call below - both the autorun (run_java)
# and no-autorun (replay-wine-fork.sh, using the winification helpers above
# directly) paths use them.
ensure_wineprefix

# Setting PROVIDE_WINDOWS_JAVA_NO_AUTORUN=1 before sourcing this script (as
# replay-wine-fork.sh does) reuses its winification helpers above without
# immediately invoking run_java on the passed-through arguments, letting the
# caller rewrite the properties files first (e.g. to point the fork at a
# standalone bridge instead of a live Maven build).
if [[ "${PROVIDE_WINDOWS_JAVA_NO_AUTORUN-}" != 1 ]]; then
  run_java "$@"
fi

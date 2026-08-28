#!/usr/bin/env bash

# Provide proper Java environment (`java` command) for the given platform

# input env. variables:
#   ORIG_JAVA_HOME: the caller Java application should set it to directory of JRE which it is running on

# output (modified) env. variables:
#   java_: X11 display address (e.g. :99)


# Target java executable is runed using `exec` with all arguments passed (unmodified).

# It is sourced by java executable wrapper (`./bin/java`) used as fake `java`
# command for `maven-surefire-plugin`'s <jvm> configuration property
# (see root `pom.xml` and `./bin/java`)


#tmp_out='/tmp/provide_platform_java_out.txt'
#
#echo >>"${tmp_out}"
#echo "===========================" >>"${tmp_out}"
#date >>"${tmp_out}"
#echo >>"${tmp_out}"
#echo "ARGS:" >>"${tmp_out}"
#printf '%s\n' "$0" "$@" >>"${tmp_out}"
#echo >>"${tmp_out}"
#echo "ENV:" >>"${tmp_out}"
#env >>"${tmp_out}"
#echo >>"${tmp_out}"


#{ echo; date; env; } | tee -a



java_bin="${ORIG_JAVA_HOME-}/bin/java"

if ! [[ -x "$java_bin" ]]; then
  echo "provide_platform_java.sh: ORIG_JAVA_HOME (${ORIG_JAVA_HOME-<unset>}) doesn't look like a JDK install, no bin/java there" >&2
  exit 1
fi



if [[ "${TEST_GROUP-}" == "windows" ]]; then

  for cmd in wine winepath curl unzip; do
    command -v "${cmd}" >/dev/null 2>&1 || {
      echo "provide_platform_java.sh: '${cmd}' not found on PATH - needed to run the @Tag(\"windows\") suite under Wine" >&2
      exit 1
    }
  done

  wrapper_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  # A real Windows PE JVM to run the forked tests as, under Wine (a Windows
  # JDK self-reports os.name as Windows, so @Tag("windows") tests execute).
  # Cached (gitignored) under the wrapper dir; delete .cache to re-download.
  WIN_JDK_URL="${WIN_JDK_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse}"
  jdk_cache_dir="${WINDOWS_JDK_CACHE:-${wrapper_dir}/.cache}"

  # Sets WINDOWS_JDK_HOME, downloading + extracting the JDK on first use. The
  # flock'd subshell keeps parallel surefire forks (forkCount > 1) from racing
  # the same download/unzip.
  function ensure_windows_jdk() {
    mkdir -p "${jdk_cache_dir}"
    (
      flock 9
      if [[ ! -s "${jdk_cache_dir}/windows-jdk.zip" ]]; then
        echo "provide_platform_java.sh: downloading Windows JDK (${WIN_JDK_URL})..." >&2
        curl -fL -o "${jdk_cache_dir}/windows-jdk.zip.part" "${WIN_JDK_URL}"
        mv "${jdk_cache_dir}/windows-jdk.zip.part" "${jdk_cache_dir}/windows-jdk.zip"
      fi
      if [[ ! -d "${jdk_cache_dir}/jdk" ]]; then
        echo "provide_platform_java.sh: extracting Windows JDK..." >&2
        mkdir -p "${jdk_cache_dir}/jdk"
        unzip -q "${jdk_cache_dir}/windows-jdk.zip" -d "${jdk_cache_dir}/jdk"
      fi
    ) 9>"${jdk_cache_dir}/.provision.lock"

    WINDOWS_JDK_HOME="$( find "${jdk_cache_dir}/jdk" -mindepth 1 -maxdepth 1 -type d -name 'jdk-*' | head -1 )"
    if [[ -z "${WINDOWS_JDK_HOME}" || ! -f "${WINDOWS_JDK_HOME}/bin/java.exe" ]]; then
      echo "provide_platform_java.sh: no bin/java.exe under ${jdk_cache_dir}/jdk - incomplete download? remove ${jdk_cache_dir} and retry" >&2
      exit 1
    fi
  }

  # Rewrites absolute Unix path values (lines like `key=/some/path`) in a
  # surefire booter properties file to Windows paths, in place.
  #
  # Surefire's parent (Maven) process writes these properties files itself,
  # in Linux path format, then passes only their bare filenames (resolved
  # against the dump directory) as arguments to the forked test JVM. Only
  # the dump directory and the booter jar path are translated to Windows
  # form by `runJava` below (they're passed as actual command-line
  # arguments); the properties file *contents* (classPathUrl.N,
  # reportsDirectory, testClassesDirectory, basedir, ...) are opaque to it
  # and stay as Linux paths, which the wine-hosted JVM can't resolve. This
  # translates them too, so the forked process gets a consistent, fully
  # Windows-style view of the filesystem.
  #
  # Forward slashes are used for the rewritten values (rather than
  # `winepath -w`'s native backslashes) because a literal backslash in a
  # Java `.properties` file is an escape character - writing one back
  # unescaped would corrupt the value when the forked JVM re-parses it.
  # Windows (and wine) accept forward slashes as a path separator, so this
  # sidesteps the escaping problem entirely.
  function winify_properties_file_paths() {
    local file=${1} line key value winvalue
    local tmp_file="${file}.tmp"
    while IFS= read -r line || [[ -n "${line}" ]]; do
      if [[ "${line}" =~ ^([^=:#][^=]*)=(/.*)$ ]]; then
        key=${BASH_REMATCH[1]}
        value=${BASH_REMATCH[2]}
        winvalue=$( winepath -w "${value}" )
        line="${key}=${winvalue//\\//}"
      fi
      printf '%s\n' "${line}"
    done <"${file}" >"${tmp_file}"
    mv "${tmp_file}" "${file}"
  }

  function runJava() {
    export WINEDEBUG=-all
    ensure_windows_jdk
    declare -a args=() dump_files=()
    declare dump_dir_linux=""
    while (( $# )); do
      case "$1" in
        "-jar")
          shift
          #cp "$1" '/tmp/'
          #( cd "$2"; tar czf '/tmp/sure.tgz' -- . )
          dump_dir_linux="$2"
          args+=( "-jar" "$( winepath -w "$1" )" "$( winepath -w "$2" )")
          shift
        ;;
        *)
          args+=( "$1" )
          dump_files+=( "$1" )
        ;;
      esac
      shift
    done
    if [[ -n "$dump_dir_linux" ]]; then
      for f in "${dump_files[@]}"; do
        if [[ -f "${dump_dir_linux}/${f}" ]]; then
          winify_properties_file_paths "${dump_dir_linux}/${f}"
        fi
      done
    fi
    # Once surefire's ForkStarter (running SurefireForkNodeFactory, a TCP fork channel -
    # see root pom.xml's windows-tests-on-linux execution) is satisfied with what it read
    # from that channel, it closes its end of this process's stderr-relay pipe
    # (ForkStarter.bindErrorStream()) - independently of whether the OS process it
    # launched (this script) has actually exited yet. Under Wine, `wine java.exe`'s own
    # shutdown is measurably slower than on a native JVM, so `set -x` below routinely
    # tries to write its next trace line to that now-closed pipe while still waiting on
    # `wine`. Bash's default action for the resulting SIGPIPE is to terminate the whole
    # script immediately, well before `wine` exits on its own - surefire then reads this
    # script's signal-terminated exit status as "the fork failed to start" even though
    # the tests behind it already ran fine and reported their results over the (separate)
    # TCP channel. Confirmed via a real `target/surefire` dump directory getting deleted
    # only *after* this process had already died, not concurrently with it - i.e. not a
    # filesystem race, just this process dying mid-run and Maven cleaning up afterwards
    # as it does for any failed fork. Ignoring SIGPIPE (rather than dropping `set -x`)
    # keeps the trace output useful for local debugging; any other write to the same pipe
    # hits the same already-closed-pipe condition and simply becomes a no-op EPIPE
    # instead of fatal.
    trap '' PIPE
    set -x
    wine "${WINDOWS_JDK_HOME}/bin/java.exe" "${args[@]}"
    rc=$?
    # Propagate wine's own exit code as this script's exit code: surefire's ForkStarter
    # treats *any* nonzero exit from the forked process's own executable as "the fork
    # failed to start" (see ForkStarter.fork()'s awaitExit() check), regardless of
    # whether tests actually ran fine over the fork channel. Falling off the end of this
    # function without an explicit exit would instead report bash's own exit status,
    # which is wrong here.
    exit "$rc"
  }

else

  function runJava() {
    set -x
    "${java_bin}" "$@"
  }

fi

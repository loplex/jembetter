#!/usr/bin/env bash
set -eu

# Replays a single Windows-under-Wine surefire fork invocation by hand,
# outside of a live Maven build, against a lightweight standalone bridge
# (wine-fork-bridge-decode.sh) that stands in for Maven's own TCP fork
# channel - decoding the fork's event stream to a readable log and acking
# its final "bye" so the JVM doesn't have to wait out
# forkedProcessExitTimeoutInSeconds (30s by default) to exit on its own.
#
# Useful when a windows-tests-on-linux surefire execution failed (or you
# just want to watch its wire traffic) and `target/surefire` from that run
# - or a copy of it - is still around, with its ORIGINAL (Linux-path,
# non-".win") properties files intact. Maven deletes that directory once a
# fork is declared failed, so grab a copy first if you need one (e.g.
# `cp -a target/surefire /tmp/surefire-capture` right after the failure).
#
# Usage:
#   replay-wine-fork.sh <java_args...> -jar <jar_path> <surefire_dir_path> <dump_file_name> <propfile_name>...
#
# These are exactly the arguments after 'bin/java' in Maven's own
# "Forking command line: ..." debug trace (`mvn -X`) for the
# windows-tests-on-linux execution you want to replay - copy everything
# from the first '--add-opens'/'-jar' argument onward.

if (( $# < 3 )); then
  echo "usage: $0 <java_args...> -jar <jar_path> <surefire_dir_path> <dump_file_name> <propfile_name>..." >&2
  exit 1
fi

wrapper_dir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

# Find '-jar' and the positional arguments after it, the same way
# provide-windows-java-using-wine.sh's run_java does - needed here so the
# properties files can be rewritten (below) before that function's own
# winification pass reads them.
java_args=() rest=( "$@" )
while (( ${#rest[@]} )); do
  java_args+=( "${rest[0]}" )
  [[ "${rest[0]}" == "-jar" ]] && { rest=( "${rest[@]:1}" ); break; }
  rest=( "${rest[@]:1}" )
done

if (( ${#rest[@]} < 3 )); then
  echo "replay-wine-fork.sh: ERROR: couldn't find '-jar' followed by <jar_path> <surefire_dir_path> <dump_file_name> in the given arguments." >&2
  exit 1
fi

jar_path=${rest[0]} surefire_dir_path=${rest[1]} dump_file_name=${rest[2]}
prop_file_names=( "${rest[@]:3}" )

if (( ${#prop_file_names[@]} == 0 )); then
  echo "replay-wine-fork.sh: ERROR: no properties file names given after <dump_file_name>." >&2
  exit 1
fi

session_id="replay-$( date +%s )-$$"

# Rewrite each propfile's forkNodeConnectionString to point at our own
# bridge instead of the (long gone) Maven process that originally forked
# this run, saving the result under a distinct name so the originals are
# left untouched - provide-windows-java-using-wine.sh's own winification
# pass (further below) then reads *these* files, exactly as it would the
# originals.
replay_prop_file_names=()
for prop_file_name in "${prop_file_names[@]}"; do
  src="${surefire_dir_path}/${prop_file_name}"
  if [[ ! -s "${src}" ]]; then
    echo "replay-wine-fork.sh: ERROR - no such properties file: ${src}" >&2
    exit 1
  fi
  dst="${prop_file_name}.replay"
  sed -E 's|^forkNodeConnectionString=.*|forkNodeConnectionString=PLACEHOLDER|' "${src}" > "${surefire_dir_path}/${dst}"
  replay_prop_file_names+=( "${dst}" )
done

# Start the bridge: socat accepts one connection on a free local port and
# hands the raw socket bytes to wine-fork-bridge-decode.sh via its own
# stdin/stdout. socat's own diagnostics and the decoder's stderr (the
# EXEC'd child inherits socat's fds) both land in socat_log, which is
# `tail -f`'d to our own stderr below so they still show up live, since
# writing socat's own output straight to our stderr would make it
# impossible to grep the "listening on" port line out of the same stream.
socat_log=$( mktemp )
socat -d -d TCP-LISTEN:0,reuseaddr,bind=127.0.0.1 \
  EXEC:"${wrapper_dir}/wine-fork-bridge-decode.sh '${session_id}'" \
  >"${socat_log}" 2>&1 &
socat_pid=$!
tail -n +1 -f "${socat_log}" >&2 &
tail_pid=$!

function cleanup() {
  sleep 0.3 # give tail -f a moment to catch up with the decoder's last lines
  kill "${tail_pid}" "${socat_pid}" 2>/dev/null || true
  rm -f "${socat_log}"
  for dst in "${replay_prop_file_names[@]}"; do
    rm -f "${surefire_dir_path}/${dst}" "${surefire_dir_path}/${dst}.win"
  done
}
trap cleanup EXIT

port=''
for _ in $( seq 1 50 ); do
  port=$( grep -oP 'listening on AF=\d+ \S+:\K\d+' "${socat_log}" 2>/dev/null || true )
  [[ -n "${port}" ]] && break
  sleep 0.1
done
if [[ -z "${port}" ]]; then
  echo "replay-wine-fork.sh: ERROR - socat never reported its listening port - see ${socat_log}" >&2
  cat "${socat_log}" >&2
  exit 1
fi
echo "replay-wine-fork.sh: bridge listening on 127.0.0.1:${port}, session '${session_id}'" >&2

# Fill in the placeholder with the real port/session, escaped the same way
# surefire's own dumped forkNodeConnectionString is (':' and '=' are
# properties-file meta-characters).
connection_string="tcp\\://127.0.0.1\\:${port}?sessionId\\=${session_id}"
for dst in "${replay_prop_file_names[@]}"; do
  sed -i "s|^forkNodeConnectionString=PLACEHOLDER\$|forkNodeConnectionString=${connection_string}|" "${surefire_dir_path}/${dst}"
done

export PROVIDE_WINDOWS_JAVA_NO_AUTORUN=1
# shellcheck source=./provide-windows-java-using-wine.sh
source "${wrapper_dir}/provide-windows-java-using-wine.sh"

run_java "${java_args[@]}" "${jar_path}" "${surefire_dir_path}" "${dump_file_name}" "${replay_prop_file_names[@]}"

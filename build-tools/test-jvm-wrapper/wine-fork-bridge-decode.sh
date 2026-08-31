#!/usr/bin/env bash
set -eu

# Stands in for Maven's TCP fork channel (SurefireForkNodeFactory /
# EventChannelEncoder in surefire-booter 3.5.2) for exactly one forked
# booter connection, so `wine java.exe ...` can be replayed by hand outside
# of a live Maven build - see replay-wine-fork.sh, which spawns this script
# via `socat ... EXEC:this-script` for each accepted connection.
#
# Reads the fork's raw framed event stream from stdin (the accepted TCP
# socket, via socat), prints a human-readable line per event to stderr, and
# writes the "bye-ack" command back to stdout (relayed by socat back over
# the same socket) as soon as the fork says "bye" - without that ack the
# forked JVM just sits there for `forkedProcessExitTimeoutInSeconds`
# (30s by default) before giving up and killing itself.
#
# Wire format reverse-engineered from the surefire-api/surefire-booter
# 3.5.2 sources (org.apache.maven.surefire.api.stream.AbstractStreamEncoder,
# org.apache.maven.surefire.booter.spi.EventChannelEncoder,
# org.apache.maven.surefire.api.booter.ForkedProcessEventType,
# org.apache.maven.surefire.booter.stream.CommandDecoder). Every segment is
# either a fixed/length-prefixed byte string or a raw binary int/long -
# never text scanned for delimiters - so this decodes it the same way: by
# byte position, not by searching for the literal ':' characters (some of
# which are also legal content of a length-prefix byte).

# input: $1 = expected session id (the fork writes this many raw ASCII
#             bytes as the very first thing on the socket, before any
#             framed event - see SurefireMasterProcessChannelProcessorFactory.connect())

session_id=${1?"usage: $0 <session-id>"}

readonly MAGIC_EVENT='maven-surefire-event'
readonly MAGIC_COMMAND='maven-surefire-command'

# Reads exactly $1 raw bytes from stdin and prints them as a hex string
# (never empty-string-vs-NUL-ambiguous, unlike capturing raw bytes in a bash
# variable directly). iflag=fullblock is required: a plain `dd bs=N count=1`
# only does one read() call and would silently return fewer than N bytes on
# a socket where the sender's write arrived in smaller TCP segments.
function read_n() {
  local n=$1
  (( n > 0 )) || return 0
  dd bs="${n}" count=1 iflag=fullblock status=none
}

function hex_to_text() {
  # A legitimate (non-null) zero-length string is common - explicit `return 0`
  # rather than `[[ -n "$1" ]] && ...` because the latter, as a function's
  # own exit status, would make read_string's `$(...)` callers trip `set -e`
  # on every empty string.
  [[ -n "$1" ]] || return 0
  printf '%s' "$1" | xxd -r -p
}

function hex_to_int() {
  printf '%d' "$(( 16#$1 ))"
}

# Reads one length-prefixed string field: 4-byte length + ':' + <bytes> + ':'
# A Java `null` is itself encoded as a 1-byte string containing a single NUL
# character (AbstractStreamEncoder.nonNull()'s sentinel) - decoded as empty
# here rather than piping a raw NUL through `xxd`/command substitution,
# which bash can't hold in a variable without warning.
function read_string() {
  local len_hex; len_hex=$(read_n 4 | xxd -p -c 999999)
  read_n 1 >/dev/null # ':'
  local len=$(( 16#${len_hex} ))
  local content_hex=''
  (( len > 0 )) && content_hex=$(read_n "${len}" | xxd -p -c 999999)
  read_n 1 >/dev/null # ':'
  [[ "${content_hex}" == '00' ]] || hex_to_text "${content_hex}"
}

# Reads one optional integer field: 1-byte null-flag [+ 4-byte int] + ':'
function read_integer() {
  local flag_hex; flag_hex=$(read_n 1 | xxd -p -c 999999)
  local value=''
  if [[ "${flag_hex}" == 'ff' ]]; then
    local int_hex; int_hex=$(read_n 4 | xxd -p -c 999999)
    value=$(hex_to_int "${int_hex}")
  fi
  read_n 1 >/dev/null # ':'
  printf '%s' "${value}"
}

# Reads the "RunMode:testRunId:" header segment shared by sys-prop, the
# std-{out,err}-stream* events, and every test(set)-* event.
function read_run_mode_and_test_run_id() {
  local rm_len_hex; rm_len_hex=$(read_n 1 | xxd -p -c 999999)
  read_n 1 >/dev/null # ':'
  local rm_len=$(( 16#${rm_len_hex} ))
  local run_mode=''
  (( rm_len > 0 )) && run_mode=$(hex_to_text "$(read_n "${rm_len}" | xxd -p -c 999999)")
  read_n 1 >/dev/null # ':'
  local has_id_hex; has_id_hex=$(read_n 1 | xxd -p -c 999999)
  if [[ "${has_id_hex}" == '01' ]]; then
    read_n 8 >/dev/null # testRunId (8-byte long) - not decoded, only display-irrelevant here
  fi
  read_n 1 >/dev/null # ':'
  printf '%s' "${run_mode}"
}

# Reads the "charset name" segment (":UTF-8:", length-prefixed like a string
# but with a 1-byte rather than 4-byte length) that precedes every event's
# actual string/integer data - present on every opcode except the bare
# control ones (bye / stop-on-next-test / next-test).
function skip_charset() {
  local cs_len_hex; cs_len_hex=$(read_n 1 | xxd -p -c 999999)
  read_n 1 >/dev/null # ':'
  local cs_len=$(( 16#${cs_len_hex} ))
  read_n "${cs_len}" >/dev/null
  read_n 1 >/dev/null # ':'
}

# Reads the 6-string + 1-int + 3-string body shared by testset-starting,
# testset-completed and every test-* event (see EventChannelEncoder.encode
# (ForkedProcessEventType, ReportEntry, boolean)).
function read_test_report_entry() {
  local source_name source_text name name_text group message elapsed
  source_name=$(read_string); source_text=$(read_string)
  name=$(read_string); name_text=$(read_string)
  group=$(read_string); message=$(read_string)
  elapsed=$(read_integer)
  local exception_message; exception_message=$(read_string)
  read_string >/dev/null # smart-trimmed stack trace
  local stack_trace; stack_trace=$(read_string)
  printf '%s' "${name_text:-"${name:-"${source_name}"}"}"
  [[ -n "${elapsed}" ]] && printf ' (%sms)' "${elapsed}"
  [[ -n "${message}" ]] && printf ' - %s' "${message}"
  [[ -n "${exception_message}" ]] && printf ' - %s' "${exception_message}"
  [[ -z "${stack_trace}" ]] || printf '\n%s' "${stack_trace}"
}

# Sends the "bye-ack" command back to the fork (over stdout, relayed by
# socat) - see MasterProcessCommand.BYE_ACK / CommandDecoder.
function send_bye_ack() {
  local opcode='bye-ack'
  printf ':%s:' "${MAGIC_COMMAND}"
  printf '%b' "$( printf '\\x%02x' "${#opcode}" )"
  printf ':%s:' "${opcode}"
}

echo "wine-fork-bridge-decode.sh: waiting for session id (${#session_id} bytes)..." >&2
actual_session_id=$(read_n "${#session_id}")
if [[ "${actual_session_id}" != "${session_id}" ]]; then
  echo "wine-fork-bridge-decode.sh: ERROR - session id mismatch (got '${actual_session_id}', expected '${session_id}') - wrong fork connected?" >&2
  exit 1
fi
echo "wine-fork-bridge-decode.sh: session id matched - decoding events:" >&2

while true; do
  colon=$(read_n 1 2>/dev/null) || break
  [[ -n "${colon}" ]] || break # EOF: fork closed the socket

  magic_hex=$(read_n "${#MAGIC_EVENT}" | xxd -p -c 999999)
  magic=$(hex_to_text "${magic_hex}")
  if [[ "${magic}" != "${MAGIC_EVENT}" ]]; then
    echo "wine-fork-bridge-decode.sh: ERROR - expected magic '${MAGIC_EVENT}', got '${magic}' - desynced, giving up" >&2
    exit 1
  fi
  read_n 1 >/dev/null # ':'

  op_len_hex=$(read_n 1 | xxd -p -c 999999)
  read_n 1 >/dev/null # ':'
  opcode=$(hex_to_text "$(read_n "$(( 16#${op_len_hex} ))" | xxd -p -c 999999)")
  read_n 1 >/dev/null # ':'

  case "${opcode}" in
    bye | stop-on-next-test | next-test)
      # no charset/data section at all for these three
      ;;
    console-info-log | console-debug-log | console-warning-log)
      skip_charset
      opcode="${opcode} $( read_string )"
      ;;
    console-error-log | jvm-exit-error)
      skip_charset
      msg=$(read_string); read_string >/dev/null; trace=$(read_string)
      opcode="${opcode} ${msg}"
      [[ -z "${trace}" ]] || opcode="${opcode}"$'\n'"${trace}"
      ;;
    sys-prop)
      read_run_mode_and_test_run_id >/dev/null
      skip_charset
      key=$(read_string); value=$(read_string)
      opcode="${opcode} ${key}=${value}"
      ;;
    std-out-stream | std-out-stream-new-line | std-err-stream | std-err-stream-new-line)
      read_run_mode_and_test_run_id >/dev/null
      skip_charset
      opcode="${opcode} $( read_string )"
      ;;
    testset-starting | testset-completed | test-starting | test-succeeded \
      | test-failed | test-skipped | test-error | test-assumption-failure)
      run_mode=$(read_run_mode_and_test_run_id)
      skip_charset
      opcode="${opcode} [${run_mode}] $( read_test_report_entry )"
      ;;
    *)
      echo "wine-fork-bridge-decode.sh: WARNING - unknown opcode '${opcode}', can't decode its body - giving up" >&2
      exit 1
      ;;
  esac

  echo "wine-fork-bridge-decode.sh: ${opcode}" >&2

  if [[ "${opcode}" == bye* ]]; then
    echo "wine-fork-bridge-decode.sh: sending bye-ack" >&2
    send_bye_ack
  fi
done

echo "wine-fork-bridge-decode.sh: connection closed." >&2

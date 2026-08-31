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

# Every byte-to-text/text-to-byte conversion below relies on the C locale's
# 1 byte == 1 "character" mapping - both for `read -n1` to pull exactly one
# raw byte off the socket regardless of its value, and for the `printf "'c"`
# numeric-value trick further down.
LC_ALL=C

# Reads exactly one raw byte from stdin into REPLY_HEX as two lowercase hex
# digits. Returns 1 only on genuine EOF - a NUL byte on the wire is read
# just fine (as "00"), which is why plain `read -n1` (no `-d ''`) doesn't
# work here: with the default newline delimiter, `read` cannot represent a
# NUL byte at all - it silently drops it and keeps reading until it finds a
# real character, desyncing every length-prefixed field that follows.
# `-d ''` switches the delimiter to NUL itself, so hitting one is a normal,
# successful 1-byte read (empty value) rather than an invisible skip.
function read_byte() {
  local byte
  IFS= read -r -n1 -d '' byte || return 1
  # The zero-padding in `%02x` and the trailing 2-char slice are not
  # cosmetic: bash's numeric value of a quoted character goes through the
  # C library's stateful multibyte-conversion routines, which are not
  # guaranteed deterministic call-to-call - some byte values otherwise
  # decode to a different (and sometimes multi-digit) value depending on
  # what was read just before them. Always keep exactly the low byte.
  local wide; printf -v wide '%02x' "'${byte}"
  REPLY_HEX=${wide: -2}
}

# Reads exactly $1 raw bytes from stdin, concatenating their hex encoding
# into REPLY_HEX (2*N lowercase hex chars). Returns 1 on EOF before N bytes
# were read (the stream ended mid-field - nothing sane to do but give up,
# same as the old dd iflag=fullblock behaviour this replaces).
function read_n_hex() {
  local n=$1 i hex=''
  for (( i = 0; i < n; i++ )); do
    read_byte || return 1
    hex+=${REPLY_HEX}
  done
  REPLY_HEX=${hex}
}

# Decodes a hex string (as produced by read_n_hex) back to text into REPLY.
# Only safe for content that doesn't itself contain a raw NUL byte (real
# string payloads here are human-readable identifiers/messages) - the
# single-NUL "null" sentinel is special-cased by read_string below instead
# of ever reaching this function, same as the previous xxd-based version.
function hex_to_text() {
  local hex=$1 text='' i ch
  for (( i = 0; i < ${#hex}; i += 2 )); do
    printf -v ch '\x'"${hex:i:2}"
    text+=${ch}
  done
  REPLY=${text}
}

# Encodes a (plain-ASCII) bash string constant to lowercase hex into REPLY -
# used once at startup to precompute MAGIC_EVENT_HEX below, so the hot path
# compares incoming hex directly instead of decoding it back to text first.
function text_to_hex() {
  local text=$1 hex='' i wide
  for (( i = 0; i < ${#text}; i++ )); do
    printf -v wide '%02x' "'${text:i:1}"
    hex+=${wide: -2}
  done
  REPLY=${hex}
}

text_to_hex "${MAGIC_EVENT}"; readonly MAGIC_EVENT_HEX=${REPLY}
text_to_hex "${session_id}"; readonly SESSION_ID_HEX=${REPLY}

# Reads one length-prefixed string field: 4-byte length + ':' + <bytes> + ':'
# A Java `null` is itself encoded as a 1-byte string containing a single NUL
# character (AbstractStreamEncoder.nonNull()'s sentinel) - decoded as empty
# here rather than running it through hex_to_text, which (like the xxd-based
# version before it) can't represent an embedded raw NUL in a bash variable.
function read_string() {
  read_n_hex 4; local len_hex=${REPLY_HEX}
  read_byte # ':'
  local len=$(( 16#${len_hex} ))
  local content=''
  if (( len > 0 )); then
    read_n_hex "${len}"
    if [[ "${REPLY_HEX}" != '00' ]]; then
      hex_to_text "${REPLY_HEX}"
      content=${REPLY}
    fi
  fi
  read_byte # ':'
  REPLY=${content}
}

# Reads one optional integer field: 1-byte null-flag [+ 4-byte int] + ':'
function read_integer() {
  read_byte; local flag_hex=${REPLY_HEX}
  local value=''
  if [[ "${flag_hex}" == 'ff' ]]; then
    read_n_hex 4
    value=$(( 16#${REPLY_HEX} ))
  fi
  read_byte # ':'
  REPLY=${value}
}

# Reads the "RunMode:testRunId:" header segment shared by sys-prop, the
# std-{out,err}-stream* events, and every test(set)-* event.
function read_run_mode_and_test_run_id() {
  read_byte; local rm_len=$(( 16#${REPLY_HEX} ))
  read_byte # ':'
  local run_mode=''
  if (( rm_len > 0 )); then
    read_n_hex "${rm_len}"
    hex_to_text "${REPLY_HEX}"
    run_mode=${REPLY}
  fi
  read_byte # ':'
  read_byte; local has_id_hex=${REPLY_HEX}
  if [[ "${has_id_hex}" == '01' ]]; then
    read_n_hex 8 # testRunId (8-byte long) - not decoded, only display-irrelevant here
  fi
  read_byte # ':'
  REPLY=${run_mode}
}

# Reads the "charset name" segment (":UTF-8:", length-prefixed like a string
# but with a 1-byte rather than 4-byte length) that precedes every event's
# actual string/integer data - present on every opcode except the bare
# control ones (bye / stop-on-next-test / next-test).
function skip_charset() {
  read_byte; local cs_len=$(( 16#${REPLY_HEX} ))
  read_byte # ':'
  (( cs_len > 0 )) && read_n_hex "${cs_len}"
  read_byte # ':'
}

# Reads the 6-string + 1-int + 3-string body shared by testset-starting,
# testset-completed and every test-* event (see EventChannelEncoder.encode
# (ForkedProcessEventType, ReportEntry, boolean)).
function read_test_report_entry() {
  read_string; local source_name=${REPLY}
  read_string; local source_text=${REPLY}
  read_string; local name=${REPLY}
  read_string; local name_text=${REPLY}
  read_string; local group=${REPLY}
  read_string; local message=${REPLY}
  read_integer; local elapsed=${REPLY}
  read_string; local exception_message=${REPLY}
  read_string # smart-trimmed stack trace
  read_string; local stack_trace=${REPLY}

  local out; out=${name_text:-"${name:-"${source_name}"}"}
  [[ -n "${elapsed}" ]] && out+=" (${elapsed}ms)"
  [[ -n "${message}" ]] && out+=" - ${message}"
  [[ -n "${exception_message}" ]] && out+=" - ${exception_message}"
  [[ -z "${stack_trace}" ]] || out+=$'\n'"${stack_trace}"
  REPLY=${out}
}

# Sends the "bye-ack" command back to the fork (over stdout, relayed by
# socat) - see MasterProcessCommand.BYE_ACK / CommandDecoder.
function send_bye_ack() {
  local opcode='bye-ack'
  local len_hex; printf -v len_hex '%02x' "${#opcode}"
  printf ':%s:' "${MAGIC_COMMAND}"
  printf '%b' '\x'"${len_hex}"
  printf ':%s:' "${opcode}"
}

echo "wine-fork-bridge-decode.sh: waiting for session id (${#session_id} bytes)..." >&2
read_n_hex "${#session_id}" || { echo "wine-fork-bridge-decode.sh: ERROR - connection closed before session id was fully read" >&2; exit 1; }
if [[ "${REPLY_HEX}" != "${SESSION_ID_HEX}" ]]; then
  hex_to_text "${REPLY_HEX}"
  echo "wine-fork-bridge-decode.sh: ERROR - session id mismatch (got '${REPLY}', expected '${session_id}') - wrong fork connected?" >&2
  exit 1
fi
echo "wine-fork-bridge-decode.sh: session id matched - decoding events:" >&2

while true; do
  read_byte || break # EOF: fork closed the socket

  read_n_hex "${#MAGIC_EVENT}"
  if [[ "${REPLY_HEX}" != "${MAGIC_EVENT_HEX}" ]]; then
    hex_to_text "${REPLY_HEX}"
    echo "wine-fork-bridge-decode.sh: ERROR - expected magic '${MAGIC_EVENT}', got '${REPLY}' - desynced, giving up" >&2
    exit 1
  fi
  read_byte # ':'

  read_byte; op_len=$(( 16#${REPLY_HEX} ))
  read_byte # ':'
  read_n_hex "${op_len}"
  hex_to_text "${REPLY_HEX}"; opcode=${REPLY}
  read_byte # ':'

  case "${opcode}" in
    bye | stop-on-next-test | next-test)
      # no charset/data section at all for these three
      ;;
    console-info-log | console-debug-log | console-warning-log)
      skip_charset
      read_string
      opcode="${opcode} ${REPLY}"
      ;;
    console-error-log | jvm-exit-error)
      skip_charset
      read_string; msg=${REPLY}
      read_string # smart-trimmed stack trace, discarded (same as read_test_report_entry's)
      read_string; trace=${REPLY}
      opcode="${opcode} ${msg}"
      [[ -z "${trace}" ]] || opcode="${opcode}"$'\n'"${trace}"
      ;;
    sys-prop)
      read_run_mode_and_test_run_id # run mode/testRunId not shown for this opcode
      skip_charset
      read_string; key=${REPLY}
      read_string; value=${REPLY}
      opcode="${opcode} ${key}=${value}"
      ;;
    std-out-stream | std-out-stream-new-line | std-err-stream | std-err-stream-new-line)
      read_run_mode_and_test_run_id # run mode/testRunId not shown for this opcode
      skip_charset
      read_string
      opcode="${opcode} ${REPLY}"
      ;;
    testset-starting | testset-completed | test-starting | test-succeeded \
      | test-failed | test-skipped | test-error | test-assumption-failure)
      read_run_mode_and_test_run_id; run_mode=${REPLY}
      skip_charset
      read_test_report_entry
      opcode="${opcode} [${run_mode}] ${REPLY}"
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

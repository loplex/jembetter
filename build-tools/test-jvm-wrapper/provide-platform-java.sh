#!/usr/bin/env bash
set -eu

# Provide proper Java environment (`java` command) for the given platform

# input env. variables:
#   ORIG_JAVA_HOME: the caller Java application should set it to directory of JRE which it is running on

# output functions:
#   run_java:
#     Runs `java` command passing all arguments.
#     The `java` command is based on TEST_GROUP env. variable:
#       "windows" => `java.exe` runed using Wine
#       otherwise => `java` native linux executable binary
#   runs java command using native executable or Wine on Windows

# This script is sourced by java executable wrapper (`./bin/java`) used as fake `java`
# command for `maven-surefire-plugin`'s <jvm> configuration property
# (see root `pom.xml` and `./bin/java`)


# see provide-windows-java-using-wine.sh's run_java for why SIGPIPE is ignored here too
trap '' PIPE


function run_debug() {
  echo 'provide-platform-java.sh: +' "${@@Q}" >&2
  "$@"
}


if [[ "${TEST_GROUP-}" == "windows" ]]; then

  printf 'provide-platform-java.sh: TEST_GROUP=%s => tests will be executed on Java for Windows using Wine\n' \
    "${TEST_GROUP-"<unset>"}" >&2

  for cmd in wine winepath; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
      echo "provide-platform-java.sh: '${cmd}' not found on PATH" \
        '- needed to run the @Tag("windows") suite on Linux under Wine' >&2
      exit 1
    fi
  done

  wrapper_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  # shellcheck source=./provide-windows-java-using-wine.sh
  source "${wrapper_dir}/provide-windows-java-using-wine.sh" "$@"

else

  printf 'provide-platform-java.sh: TEST_GROUP=%s => tests will be executed on linux native Java\n' \
    "${TEST_GROUP-"<unset>"}" >&2

  declare java_bin="${ORIG_JAVA_HOME-}/bin/java"

  if ! [[ -x "${java_bin}" ]]; then
    printf "provide-platform-java.sh: ORIG_JAVA_HOME=%s doesn't look like a JDK install, no bin/java there\n" \
      "${ORIG_JAVA_HOME-"<unset>"}" >&2
    exit 2
  fi

  run_debug exec "${java_bin}" "$@"

fi

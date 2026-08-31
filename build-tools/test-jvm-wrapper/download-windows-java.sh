#!/usr/bin/env bash
set -euo pipefail

# A real Windows PE JVM to run the forked tests as, under Wine (a Windows
# JDK self-reports os.name as Windows, so @Tag("windows") tests execute).
# Cached (gitignored) under the wrapper dir; delete .cache to re-download.
WIN_JDK_URL=${WIN_JDK_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse}

wrapper_dir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
jdk_cache_dir=${WINDOWS_JDK_CACHE:-${wrapper_dir}/.cache}


function ensureJdkDownloaded() {
  if [[ -s "${jdk_cache_dir}/download/windows-jdk.zip" ]]; then
    echo "download-windows-java.sh: Windows JDK already downloaded (${jdk_cache_dir}/download/windows-jdk.zip)." >&2
    return
  fi

  rm -rf "${jdk_cache_dir}/download.part" 2>/dev/null ||:
  mkdir -p "${jdk_cache_dir}/download.part"
  echo "download-windows-java.sh: downloading Windows JDK (${WIN_JDK_URL})..." >&2
  (
    cd "${jdk_cache_dir}/download.part"
    curl -fJL -O "${WIN_JDK_URL}"
    ln -s ./* 'windows-jdk.zip'
  )
  (
    cd "${jdk_cache_dir}"
    if [[ -e "./download" ]]; then
      old_download="download.old.${RANDOM}"
      mv 'download' "${old_download}"
      mv 'download.part' 'download'
      rm -rf "./${old_download}"
    else
      mv 'download.part' 'download'
    fi
  )
}

function ensureJdkDir() {
  if [[ -d "${jdk_cache_dir}/extract/jdk" ]] && [[ -s "${jdk_cache_dir}/extract/jdk/bin/java.exe" ]]; then
    echo "download-windows-java.sh: Windows JDK already extracted (${jdk_cache_dir}/extract/jdk/)."
    return
  fi

  ensureJdkDownloaded

  rm -rf "${jdk_cache_dir}/extract.part" 2>/dev/null ||:
  mkdir -p "${jdk_cache_dir}/extract.part"
  echo "download-windows-java.sh: extracting Windows JDK..."
  (
    cd "${jdk_cache_dir}/extract.part"
    unzip -q "${jdk_cache_dir}/download/windows-jdk.zip"
    ln -s ./* 'jdk'
  )
  (
    cd "${jdk_cache_dir}"
    if [[ -e "./extract" ]]; then
      old_extract="extract.old.${RANDOM}"
      mv 'extract' "${old_extract}"
      mv 'extract.part' 'extract'
      rm -rf "./${old_extract}"
    else
      mv 'extract.part' 'extract'
    fi
  )
}


mkdir -p "${jdk_cache_dir}"

ensureJdkDir

if [[ -d "${jdk_cache_dir}/extract/jdk" ]] && [[ -s "${jdk_cache_dir}/extract/jdk/bin/java.exe" ]]; then
  echo "download-windows-java.sh: java.exe is at ${jdk_cache_dir}/extract/jdk/bin/java.exe"
else
  echo "download-windows-java.sh: ERROR - no java.exe at ${jdk_cache_dir}/extract/jdk/bin/java.exe" >&2
  echo "download-windows-java.sh: incomplete download? remove ${jdk_cache_dir} and retry" >&2
  exit 1
fi

#!/usr/bin/env bash
# Installs a SQL Assist plugin jar into a DBeaver installation, or removes it.
#
#   ./install.sh com.sqlassist.dbeaver_0.1.0.<qualifier>.jar
#   ./install.sh --uninstall
#
# DBeaver ships the Eclipse dropins scanner disabled, so the jar is copied to
# plugins/ and registered in configuration/.../bundles.info instead. The original
# bundles.info is kept as bundles.info.sqlassist-backup. Restart DBeaver afterwards.
#
# Set DBEAVER_HOME to the folder that contains plugins/ and configuration/ if
# DBeaver is not in a default location.
set -euo pipefail

find_dbeaver() {
  local candidates=(
    "/Applications/DBeaver.app/Contents/Eclipse"   # macOS
    "/usr/share/dbeaver-ce"                         # Linux .deb / .rpm
    "/opt/dbeaver"                                  # Linux tarball
    "/c/Program Files/DBeaver"                      # Windows (Git Bash)
  )
  for dir in "${candidates[@]}"; do
    if [[ -d "$dir/plugins" && -d "$dir/configuration" ]]; then
      echo "$dir"
      return
    fi
  done
}

DBEAVER_HOME="${DBEAVER_HOME:-$(find_dbeaver)}"
if [[ -z "$DBEAVER_HOME" || ! -d "$DBEAVER_HOME/plugins" ]]; then
  echo "DBeaver not found. Set DBEAVER_HOME to the folder that contains plugins/ and configuration/." >&2
  exit 1
fi
PLUGINS="$DBEAVER_HOME/plugins"
INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
[[ -f "$INFO" ]] || { echo "Not found: $INFO" >&2; exit 1; }
[[ -w "$PLUGINS" && -w "$INFO" ]] || { echo "No write access to $DBEAVER_HOME (try sudo)." >&2; exit 1; }

remove_existing() {
  rm -f "$DBEAVER_HOME"/dropins/com.sqlassist.dbeaver_*.jar "$PLUGINS"/com.sqlassist.dbeaver_*.jar
  grep -v '^com\.sqlassist\.dbeaver,' "$INFO" > "$INFO.tmp" || true
  mv "$INFO.tmp" "$INFO"
}

if [[ "${1:-}" == "--uninstall" ]]; then
  remove_existing
  echo "SQL Assist removed from $DBEAVER_HOME. Restart DBeaver."
  exit 0
fi

JAR="${1:-}"
NAME="$(basename "$JAR")"
if [[ ! -f "$JAR" || ! "$NAME" =~ ^com\.sqlassist\.dbeaver_(.+)\.jar$ ]]; then
  echo "Usage: $0 com.sqlassist.dbeaver_<version>.jar | --uninstall" >&2
  exit 1
fi
VERSION="${BASH_REMATCH[1]}"

[[ -f "$INFO.sqlassist-backup" ]] || cp "$INFO" "$INFO.sqlassist-backup"
remove_existing
cp "$JAR" "$PLUGINS/"
echo "com.sqlassist.dbeaver,$VERSION,plugins/$NAME,4,true" >> "$INFO"
echo "SQL Assist $VERSION installed into $DBEAVER_HOME."
echo "Quit DBeaver completely and start it again."

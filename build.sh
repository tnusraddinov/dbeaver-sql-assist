#!/usr/bin/env bash
# Compiles the plugin against the jars of a local DBeaver installation and
# packages an OSGi bundle into dist/. No Maven/Tycho needed for local work.
#
#   ./build.sh            build + run parser tests
#   ./build.sh install    ...and register the bundle in the DBeaver installation
#   ./build.sh uninstall  remove it again
set -euo pipefail
cd "$(dirname "$0")"

DBEAVER_HOME="${DBEAVER_HOME:-/Applications/DBeaver.app/Contents/Eclipse}"
PLUGINS="$DBEAVER_HOME/plugins"
[[ -d "$PLUGINS" ]] || { echo "DBeaver plugins not found at $PLUGINS (set DBEAVER_HOME)"; exit 1; }

VERSION="0.1.0.$(date +%Y%m%d%H%M)"
BUNDLE="com.sqlassist.dbeaver_${VERSION}.jar"
CP=$(find "$PLUGINS" -maxdepth 1 -name '*.jar' | tr '\n' ':')

rm -rf build dist && mkdir -p build/classes build/test-classes dist

echo "==> Compiling plugin"
javac --release 21 -nowarn -cp "$CP" -d build/classes $(find src -name '*.java')

echo "==> Running tests"
javac --release 21 -cp build/classes -d build/test-classes $(find test -name '*.java')
for t in $(cd test && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g'); do
  java -cp build/classes:build/test-classes "$t"
done

echo "==> Packaging $BUNDLE"
sed "s/0\.1\.0\.qualifier/$VERSION/" META-INF/MANIFEST.MF > build/MANIFEST.MF
cp plugin.xml LICENSE build/classes/
jar --create --file "dist/$BUNDLE" --manifest build/MANIFEST.MF -C build/classes .

if [[ "${1:-}" == "install" ]]; then
  # DBeaver ships the p2 dropins reconciler disabled, so dropins/ is ignored.
  # Register the bundle directly with simpleconfigurator instead.
  INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
  [[ -f "$INFO.sqlassist-backup" ]] || cp "$INFO" "$INFO.sqlassist-backup"
  rm -f "$DBEAVER_HOME"/dropins/com.sqlassist.dbeaver_*.jar "$PLUGINS"/com.sqlassist.dbeaver_*.jar
  cp "dist/$BUNDLE" "$PLUGINS/"
  grep -v '^com\.sqlassist\.dbeaver,' "$INFO" > "$INFO.tmp"
  echo "com.sqlassist.dbeaver,$VERSION,plugins/$BUNDLE,4,true" >> "$INFO.tmp"
  mv "$INFO.tmp" "$INFO"
  echo "==> Installed into $PLUGINS and registered in bundles.info. Restart DBeaver (Cmd+Q, then reopen)."
fi

if [[ "${1:-}" == "uninstall" ]]; then
  INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
  rm -f "$DBEAVER_HOME"/dropins/com.sqlassist.dbeaver_*.jar "$PLUGINS"/com.sqlassist.dbeaver_*.jar
  grep -v '^com\.sqlassist\.dbeaver,' "$INFO" > "$INFO.tmp" && mv "$INFO.tmp" "$INFO"
  echo "==> Uninstalled. Restart DBeaver."
  exit 0
fi
echo "Done: dist/$BUNDLE"

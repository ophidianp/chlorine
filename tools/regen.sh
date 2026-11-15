#!/usr/bin/env bash
# Regenerates every redirect table and hierarchy file from Mojang's client jars.
#
#   tools/regen.sh /path/to/dir-with-client-jars
#
# The directory must hold <version>-client.jar for each version below (see
# tools/fetch-jars.sh). Mojang's jars are never committed to this repo.
set -euo pipefail
JARS="${1:?usage: tools/regen.sh <dir with <version>-client.jar files>}"
VERSIONS=(26.1.2 26.2 26.3)

cd "$(dirname "$0")/.."
./gradlew -q compileJava printDiffgenClasspath > /dev/null
CP="$(./gradlew -q printDiffgenClasspath | tail -1)"
OUT=src/main/resources/isotope/redirects

for ((i = 0; i + 1 < ${#VERSIONS[@]}; i++)); do
	a="${VERSIONS[$i]}"; b="${VERSIONS[$((i + 1))]}"
	java -Xmx3g -cp "$CP" com.isotope.tools.DiffGen "$a" "$b" "$JARS/$a-client.jar" "$JARS/$b-client.jar" "$OUT/${a}_to_${b}.redirects"
	java -Xmx3g -cp "$CP" com.isotope.tools.DiffGen "$b" "$a" "$JARS/$b-client.jar" "$JARS/$a-client.jar" "$OUT/${b}_to_${a}.redirects"
done

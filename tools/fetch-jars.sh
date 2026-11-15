#!/usr/bin/env bash
# Downloads Mojang's client jars for the versions Isotope has tables for.
#
#   tools/fetch-jars.sh /path/to/output-dir
#
# Needs curl and python3. The jars are Mojang's property: keep them out of git.
set -euo pipefail
OUT="${1:?usage: tools/fetch-jars.sh <output dir>}"
mkdir -p "$OUT"
MANIFEST="$(curl -fsS https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)"

for v in 26.1.2 26.2 26.3; do
	url="$(printf '%s' "$MANIFEST" | python3 -c "import sys,json; print(next(x['url'] for x in json.load(sys.stdin)['versions'] if x['id']=='$v'))")"
	jar="$(curl -fsS "$url" | python3 -c "import sys,json; print(json.load(sys.stdin)['downloads']['client']['url'])")"
	echo "$v <- $jar"
	curl -fsS -o "$OUT/$v-client.jar" "$jar"
done

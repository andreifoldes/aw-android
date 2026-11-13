#!/bin/sh
# Mirrors the signed F-Droid repo that .github/workflows/fdroid.yml publishes on
# the `fdroid-repo` release into $DEST, which Caddy serves at /aw/fdroid/.
# Run by the workflow's deploy job; does nothing unless the version changed.
#
#   DEST/releases/<versionCode>/repo/   one unpacked bundle per version
#   DEST/current -> releases/<versionCode>   swapped atomically
set -eu

GH_REPO=${GH_REPO:-andreifoldes/aw-android}
TAG=${TAG:-fdroid-repo}
DEST=${DEST:-/srv/aw-fdroid}
KEEP=${KEEP:-2}
BASE="https://github.com/$GH_REPO/releases/download/$TAG"

version=$(curl -fsSL --max-time 30 "$BASE/fdroid-repo.version") || exit 0
code=${version%% *}
case $code in ''|*[!0-9]*) echo "bad version line: $version" >&2; exit 1 ;; esac
if [ -f "$DEST/current/VERSION" ] && [ "$(cat "$DEST/current/VERSION")" = "$version" ]; then
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --max-time 600 -o "$tmp/bundle.tar.gz" "$BASE/fdroid-repo.tar.gz"
want=$(curl -fsSL --max-time 30 "$BASE/fdroid-repo.sha256")
got=$(sha256sum "$tmp/bundle.tar.gz" | cut -d' ' -f1)
if [ "$want" != "$got" ]; then
    # The bundle and its checksum are uploaded separately; retry on the next run.
    echo "checksum mismatch (want $want, got $got)" >&2
    exit 1
fi

target="$DEST/releases/$code"
rm -rf "$target.partial"
mkdir -p "$target.partial"
tar -xzf "$tmp/bundle.tar.gz" -C "$target.partial"
test -s "$target.partial/repo/index-v1.jar"
test -s "$target.partial/repo/entry.jar"
echo "$version" > "$target.partial/VERSION"
chmod -R a+rX "$target.partial"
rm -rf "$target"
mv "$target.partial" "$target"

ln -sfn "releases/$code" "$DEST/current.new"
mv -T "$DEST/current.new" "$DEST/current"
echo "$(date -u +%FT%TZ) published $version"

ls -1 "$DEST/releases" | grep -E '^[0-9]+$' | sort -n | head -n -"$KEEP" |
    while read -r old; do rm -rf "$DEST/releases/$old"; done

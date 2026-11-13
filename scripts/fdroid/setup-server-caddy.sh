#!/bin/sh
# One-time root setup on iemabot for the ActivityWatch F-Droid repo:
# creates /srv/aw-fdroid (owned by the cron user) and adds a Caddy route that
# serves it at /aw/fdroid/. Run as: sudo sh setup-server-caddy.sh <user>
set -eu

OWNER=${1:?usage: sudo sh setup-server-caddy.sh <user that runs the cron job>}
CADDYFILE=/etc/caddy/Caddyfile
DEST=/srv/aw-fdroid
ANCHOR='# --- iEMAbot (being retired)'

install -d -o "$OWNER" -g "$OWNER" -m 755 "$DEST" "$DEST/releases"

if grep -q 'handle_path /aw/fdroid/\*' "$CADDYFILE"; then
    echo "Caddy route already present"
else
    grep -qF "$ANCHOR" "$CADDYFILE" || { echo "anchor '$ANCHOR' not found in $CADDYFILE; aborting" >&2; exit 1; }
    backup="$CADDYFILE.bak.$(date +%Y%m%d-%H%M%S)"
    cp -p "$CADDYFILE" "$backup"
    awk -v anchor="$ANCHOR" '
        index($0, anchor) && !done {
            print "\t# --- ActivityWatch Android (andreifoldes fork) F-Droid repo, published"
            print "\t#     by ~/aw-fdroid/update-server-repo.sh (cron) into /srv/aw-fdroid ---"
            print "\thandle_path /aw/fdroid/* {"
            print "\t\troot * /srv/aw-fdroid/current"
            print "\t\tfile_server"
            print "\t}"
            print ""
            done = 1
        }
        { print }
    ' "$backup" > "$CADDYFILE"
    if ! caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1; then
        cp -p "$backup" "$CADDYFILE"
        echo "caddy validate failed; restored $backup" >&2
        exit 1
    fi
    systemctl reload caddy
    echo "Caddy route added and reloaded (backup: $backup)"
fi

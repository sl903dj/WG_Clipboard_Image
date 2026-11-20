#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
source "$HOME/.config/wg-clipboard/config"

APP="com.example.wgclipboard/.ClipboardActivity"
DIR="$HOME/storage/pictures/wg-clipboard"
mkdir -p "$DIR"
FILE="$DIR/from-linux-$(date +%s).img"
RESULT="$DIR/result.txt"
rm -f "$FILE" "$RESULT"

curl -fsS --connect-timeout 3 --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  "$SERVER/clipboard/image" -o "$FILE" || { rm -f "$FILE"; exit 1; }
MIME=$(file --brief --mime-type "$FILE")
case "$MIME" in image/png|image/jpeg|image/webp|image/gif) ;; *) rm -f "$FILE"; echo "Unsupported image: $MIME" >&2; exit 1;; esac

am start -n "$APP" --es action set_image --es path "$FILE" --es result "$RESULT" >/dev/null
for _ in $(seq 1 600); do
    [[ -f "$RESULT" ]] && break
    sleep 0.25
done
[[ -f "$RESULT" ]] || { echo "Clipboard helper timed out" >&2; exit 1; }
STATUS=$(cat "$RESULT"); rm -f "$RESULT" "$FILE"
[[ "$STATUS" == OK ]] || { echo "Android could not set image clipboard" >&2; exit 1; }
echo "Linux image -> Android: OK"

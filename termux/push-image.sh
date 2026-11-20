#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
source "$HOME/.config/wg-clipboard/config"

APP="com.example.wgclipboard/.ClipboardActivity"
DIR="$HOME/storage/pictures/wg-clipboard"
mkdir -p "$DIR"
OUT="$DIR/from-clipboard-$(date +%s).img"
RESULT="$DIR/result.txt"
rm -f "$OUT" "$RESULT"

am start -n "$APP" --es action get_image --es path "$OUT" --es result "$RESULT" >/dev/null
for _ in $(seq 1 600); do
    [[ -f "$RESULT" ]] && break
    sleep 0.25
done
[[ -f "$RESULT" ]] || { echo "Clipboard helper timed out" >&2; exit 1; }
STATUS=$(cat "$RESULT"); rm -f "$RESULT"
[[ "$STATUS" == OK && -s "$OUT" ]] || { rm -f "$OUT"; echo "No image in Android clipboard" >&2; exit 1; }
MIME=$(file --brief --mime-type "$OUT")
case "$MIME" in image/png|image/jpeg|image/webp|image/gif) ;; *) rm -f "$OUT"; echo "Unsupported image: $MIME" >&2; exit 1;; esac
curl -fsS --connect-timeout 3 --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: $MIME" \
  --data-binary "@$OUT" "$SERVER/clipboard/image"
echo
echo "Android image -> Linux: OK"
rm -f "$OUT"

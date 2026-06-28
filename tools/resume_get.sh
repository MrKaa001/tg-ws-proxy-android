#!/usr/bin/env bash
# Resume-until-complete downloader: tolerates active RST/reset by repeatedly
# resuming (curl -C -) from the current byte offset until the file is whole.
# Usage: resume_get.sh <url> <outfile>
URL="$1"; OUT="$2"
LEN=$(curl -sS -I --max-time 30 "$URL" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-length"{print $2}')
[ -n "$LEN" ] || { echo "no content-length for $URL"; exit 1; }
echo "target $OUT  len=$LEN"
for i in $(seq 1 400); do
  cur=0; [ -f "$OUT" ] && cur=$(wc -c < "$OUT")
  if [ "$cur" -ge "$LEN" ]; then break; fi
  curl -sS -L -C - --connect-timeout 15 --max-time 30 -o "$OUT" "$URL" >/dev/null 2>&1 || true
  cur=0; [ -f "$OUT" ] && cur=$(wc -c < "$OUT")
  printf "\r  iter %3d  %8d / %d" "$i" "$cur" "$LEN"
done
echo ""
cur=0; [ -f "$OUT" ] && cur=$(wc -c < "$OUT")
if [ "$cur" -eq "$LEN" ]; then echo "OK $OUT ($cur)"; else echo "INCOMPLETE $OUT ($cur/$LEN)"; exit 1; fi

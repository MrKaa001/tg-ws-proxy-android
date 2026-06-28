#!/usr/bin/env bash
# Range-chunked downloader to defeat per-connection throttling on chaquo.com.
# Usage: chunked_get.sh <url> <outfile>
set -euo pipefail
URL="$1"; OUT="$2"
CHUNK=$((128*1024))
LEN=$(curl -sS -I --max-time 30 "$URL" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-length"{print $2}')
[ -n "$LEN" ] || { echo "no content-length"; exit 1; }
echo "len=$LEN chunk=$CHUNK"
: > "$OUT"
start=0
while [ "$start" -lt "$LEN" ]; do
  end=$((start+CHUNK-1)); [ "$end" -ge "$LEN" ] && end=$((LEN-1))
  curl -sS -L --retry 15 --retry-delay 2 --retry-all-errors \
       --connect-timeout 15 --max-time 60 \
       -r "$start-$end" "$URL" >> "$OUT"
  got=$(wc -c < "$OUT")
  printf "\r%d / %d bytes" "$got" "$LEN"
  start=$((end+1))
done
echo ""
got=$(wc -c < "$OUT")
[ "$got" -eq "$LEN" ] && echo "OK $OUT ($got bytes)" || { echo "SIZE MISMATCH got=$got want=$LEN"; exit 1; }

#!/usr/bin/env bash
# Grinding downloader for hosts that blackhole sustained connections (chaquo.com under DPI):
# tiny ranges, per-chunk retry-until-success, resumable. Built because 128KB chunks time out
# but 16KB chunks slip through between RST windows.
# Usage: grind_get.sh <url> <outfile> [chunk_bytes]
URL="$1"; OUT="$2"; CHUNK="${3:-16384}"
LEN=$(curl -sS -I --max-time 30 "$URL" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-length"{print $2}')
[ -n "$LEN" ] || { echo "no content-length for $URL"; exit 1; }
echo "target $OUT  len=$LEN  chunk=$CHUNK"
# resume from existing size (truncate to chunk boundary to avoid a half-written tail)
cur=0; [ -f "$OUT" ] && cur=$(wc -c < "$OUT")
cur=$(( (cur / CHUNK) * CHUNK ))
if [ "$cur" -gt 0 ]; then
  head -c "$cur" "$OUT" > "$OUT.trunc" && mv "$OUT.trunc" "$OUT"
else
  : > "$OUT"
fi
start=$cur
while [ "$start" -lt "$LEN" ]; do
  end=$((start+CHUNK-1)); [ "$end" -ge "$LEN" ] && end=$((LEN-1))
  ok=0
  for attempt in $(seq 1 200); do
    if curl -sS -f -o "$OUT.chunk" -w '' --connect-timeout 8 --max-time 20 \
         -r "$start-$end" "$URL" 2>/dev/null; then
      sz=$(wc -c < "$OUT.chunk")
      want=$((end-start+1))
      if [ "$sz" -eq "$want" ]; then cat "$OUT.chunk" >> "$OUT"; ok=1; break; fi
    fi
    sleep 1
  done
  rm -f "$OUT.chunk"
  [ "$ok" -eq 1 ] || { echo "FAILED chunk $start-$end after retries"; exit 1; }
  got=$(wc -c < "$OUT")
  printf "\r  %d / %d bytes (%d%%)" "$got" "$LEN" $(( got*100/LEN ))
  start=$((end+1))
done
echo ""
got=$(wc -c < "$OUT")
[ "$got" -eq "$LEN" ] && echo "OK $OUT ($got bytes)" || { echo "SIZE MISMATCH got=$got want=$LEN"; exit 1; }

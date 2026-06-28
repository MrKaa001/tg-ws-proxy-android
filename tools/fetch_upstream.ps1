# Fetches the upstream proxy/ directory from Flowseal/tg-ws-proxy at a pinned commit
# into app/src/main/python/proxy. NOT vendored in git — mirrors the HA add-on model.
# Run once before building (and again after bumping $Ref to a new upstream release).
$ErrorActionPreference = "Stop"

# Flowseal/tg-ws-proxy — pinned to release v1.8.1 (keep in sync with the HA add-on Dockerfile)
$Ref = "4f9edf3072454dcb18831e65e7cfdb68f7046a61"

$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "app\src\main\python\proxy"
$Tmp  = Join-Path $env:TEMP ("tgws-" + [System.IO.Path]::GetRandomFileName())

git clone --no-checkout https://github.com/Flowseal/tg-ws-proxy.git $Tmp
git -C $Tmp checkout $Ref -- proxy LICENSE

if (Test-Path $Dest) { Remove-Item -Recurse -Force $Dest }
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Copy-Item -Recurse -Force (Join-Path $Tmp "proxy\*") $Dest
Copy-Item -Force (Join-Path $Tmp "LICENSE") (Join-Path $Dest "LICENSE-UPSTREAM")

Remove-Item -Recurse -Force $Tmp
Write-Host "Fetched proxy/ from Flowseal/tg-ws-proxy@$Ref -> $Dest"

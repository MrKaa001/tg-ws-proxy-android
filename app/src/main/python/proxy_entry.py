"""
Bridge between the Android service and the upstream proxy.

We keep the "don't fork, just invoke upstream" model: the proxy/ package is fetched
verbatim (tools/fetch_upstream.*) and imported as a top-level package — Chaquopy makes
src/main/python/ importable. We replicate exactly what the HA add-on's run.sh does:
set sys.argv and call the upstream main(), which parses args, fills proxy_config
(including default DC IPs) and runs the asyncio loop.

NOTE on stopping: upstream also exposes `proxy.tg_ws_proxy.run_proxy(stop_event)` and an
`_run(stop_event)` coroutine with a clean shutdown path. A graceful in-loop stop is a
later milestone; for now the Android service tears the process down on Stop.
"""
import sys


def start(host, port, secret, dc_ips):
    """Called from Kotlin (Chaquopy). Blocks on the proxy's asyncio loop."""
    from proxy import tg_ws_proxy  # imported lazily so import errors surface in logs

    argv = ["tg_ws_proxy", "--host", str(host), "--port", str(port)]

    if dc_ips:
        # dc_ips: iterable of "<dc_id>:<ip>" strings. If empty/None, upstream
        # main() falls back to its built-in defaults.
        for dc in dc_ips:
            argv += ["--dc-ip", str(dc)]

    if secret:
        argv += ["--secret", str(secret)]

    sys.argv = argv
    tg_ws_proxy.main()

"""
Bridge between the Android service and the upstream proxy.

We keep the "don't fork, just invoke upstream" model: the proxy/ package is fetched
verbatim (tools/fetch_upstream.*) and imported as a top-level package — Chaquopy makes
src/main/python/ importable. We replicate exactly what the HA add-on's run.sh does:
set sys.argv and call the upstream main(), which parses args, fills proxy_config
(including default DC IPs) and runs the asyncio loop.

GRACEFUL STOP: upstream's `_run(stop_event)` already has a clean shutdown path — when
the event is set it cancels in-flight client tasks, closes the listener and unwinds the
loop. The catch is main() owns the loop (`asyncio.run(_run())`) and never passes a stop
event. Rather than fork upstream, we let main() do all its config/logging setup but
intercept its single `asyncio.run(...)` call, driving the loop ourselves with a stop
event we hold a handle to. stop() then signals it threadsafely from the Android thread.
"""
import sys
import asyncio
import logging
import threading

# Handles the Android service uses to ask the running proxy to stop gracefully,
# instead of killing the whole process. Guarded by _state_lock because start() runs
# on the worker thread while stop() is called from another.
_loop = None            # the event loop _run() is driving
_stop_event = None      # asyncio.Event the upstream _run() watches
_state_lock = threading.Lock()


def start(host, port, secret, dc_ips, cf_worker_domains=None):
    """Called from Kotlin (Chaquopy). Blocks on the proxy's asyncio loop until stop()."""
    from proxy import tg_ws_proxy

    argv = ["tg_ws_proxy", "--host", str(host), "--port", str(port)]
    if dc_ips:
        # dc_ips: iterable of "<dc_id>:<ip>" strings. If empty/None, upstream
        # main() falls back to its built-in defaults.
        for dc in dc_ips:
            argv += ["--dc-ip", str(dc)]
    if secret:
        argv += ["--secret", str(secret)]
    if cf_worker_domains:
        # cf_worker_domains: iterable of user's Cloudflare Worker domains
        # (e.g. "name.username.workers.dev"). Repeatable flag; upstream tries
        # these before other WS fallback methods.
        for domain in cf_worker_domains:
            argv += ["--cfproxy-worker-domain", str(domain)]
    sys.argv = argv

    # Graceful stop keeps the process alive, so main() may run again in the same
    # process on a later Start — clear handlers first so logs don't get duplicated.
    logging.getLogger().handlers.clear()

    real_run = asyncio.run

    def _run_with_stop(coro, **kwargs):
        # Discard main()'s stop-event-less coroutine and rebuild _run with one. Closing
        # an un-started coroutine is clean; proxy_config is already populated by main().
        global _loop, _stop_event
        coro.close()
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        stop_event = asyncio.Event()
        with _state_lock:
            _loop, _stop_event = loop, stop_event
        try:
            loop.run_until_complete(tg_ws_proxy._run(stop_event))
        finally:
            try:
                loop.close()
            finally:
                with _state_lock:
                    _loop, _stop_event = None, None

    asyncio.run = _run_with_stop
    try:
        tg_ws_proxy.main()
    finally:
        asyncio.run = real_run


def stop():
    """Ask the running proxy to shut down gracefully. Threadsafe; returns immediately.

    Returns True if a running loop was signalled, False if nothing was running."""
    with _state_lock:
        loop, ev = _loop, _stop_event
    if loop is None or ev is None:
        return False
    # set() isn't threadsafe; hop onto the loop thread to flip it.
    loop.call_soon_threadsafe(ev.set)
    return True

#!/usr/bin/env python3
"""Minimal local Nostr relay (NIP-01) for emulator tests, so tests never touch public relays.

Stores events in memory, answers REQ with stored matches then EOSE, and pushes new events to
matching subscriptions. Emulators reach it at ws://10.0.2.2:<port>. Stop the process to simulate a
relay outage; events stored before the stop are lost, like a relay that dropped them.

    python3 tools/release_gate/local_relay.py --port 7777
"""
import argparse
import asyncio
import json

import websockets

EVENTS = []          # stored events, oldest first
SUBS = {}            # (websocket, sub_id) -> list of filters


def matches(ev, f):
    if "ids" in f and ev["id"] not in f["ids"]:
        return False
    if "authors" in f and ev["pubkey"] not in f["authors"]:
        return False
    if "kinds" in f and ev["kind"] not in f["kinds"]:
        return False
    if "since" in f and ev["created_at"] < f["since"]:
        return False
    if "until" in f and ev["created_at"] > f["until"]:
        return False
    for key, values in f.items():
        if key.startswith("#") and len(key) == 2:
            tag_values = [t[1] for t in ev.get("tags", []) if len(t) > 1 and t[0] == key[1]]
            if not any(v in values for v in tag_values):
                return False
    return True


async def handle(ws):
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            kind = msg[0] if msg else None
            if kind == "EVENT" and len(msg) >= 2:
                ev = msg[1]
                if not any(e["id"] == ev["id"] for e in EVENTS):
                    EVENTS.append(ev)
                    for (sock, sub_id), filters in list(SUBS.items()):
                        if any(matches(ev, f) for f in filters):
                            try:
                                await sock.send(json.dumps(["EVENT", sub_id, ev]))
                            except websockets.ConnectionClosed:
                                pass
                await ws.send(json.dumps(["OK", ev["id"], True, ""]))
                print(f"event kind={ev.get('kind')} stored={len(EVENTS)}", flush=True)
            elif kind == "REQ" and len(msg) >= 3:
                sub_id, filters = msg[1], msg[2:]
                SUBS[(ws, sub_id)] = filters
                for ev in EVENTS:
                    if any(matches(ev, f) for f in filters):
                        await ws.send(json.dumps(["EVENT", sub_id, ev]))
                await ws.send(json.dumps(["EOSE", sub_id]))
            elif kind == "CLOSE" and len(msg) >= 2:
                SUBS.pop((ws, msg[1]), None)
    except websockets.ConnectionClosed:
        pass
    finally:
        for key in [k for k in SUBS if k[0] is ws]:
            SUBS.pop(key, None)


async def main(port):
    async with websockets.serve(handle, "127.0.0.1", port, max_size=2**20):
        print(f"local relay on ws://127.0.0.1:{port} (emulators: ws://10.0.2.2:{port})", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7777)
    asyncio.run(main(parser.parse_args().port))

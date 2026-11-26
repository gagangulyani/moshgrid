#!/usr/bin/env python3
"""MoshGrid end-to-end scenarios on two emulators or phones (debug builds).

Covers what Mesh Lab does not: the internet path (against a local relay, never public ones),
network switching, notifications, the real UI (Help, Find, Friends, banners, back button), and
destructive flows (remove friend, panic wipe). Synthetic data only.

Prerequisites: both devices paired with `mesh_lab.py setup` (debug APK installed), unlocked and
awake. The script starts its own relay on 127.0.0.1 (emulators reach it as 10.0.2.2); on physical
phones use `adb reverse tcp:<port> tcp:<port>` and `--relay-url ws://127.0.0.1:<port>`.

    python3 tools/release_gate/mosh_e2e.py --serial-a emulator-5554 --serial-b emulator-5556 all
    python3 tools/release_gate/mosh_e2e.py ... --list

Destructive scenarios (remove_friend, wipe) run last; run `mesh_lab.py setup` again afterwards.
Results are printed and written to --out as JSON. Keep that output local.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mesh_lab import Device, run_adb  # noqa: E402

PKG = "com.moshgrid.android"
ACTIVITY = f"{PKG}/com.bitchat.android.MainActivity"
REPO = Path(__file__).resolve().parents[2]


# --- device helpers ----------------------------------------------------------------------------

class DeviceLost(RuntimeError):
    """An emulator or phone disappeared mid-run; nothing after it can be trusted."""


class Phone:
    def __init__(self, serial: str, alias: str, name: str):
        self.serial, self.alias, self.name = serial, alias, name
        self.dev = Device(serial, alias)
        self.peer_id = ""

    def sh(self, cmd: str, timeout: int = 60) -> str:
        r = subprocess.run(["adb", "-s", self.serial, "shell", cmd], capture_output=True, text=True, timeout=timeout)
        if r.returncode != 0 and ("not found" in r.stderr or "offline" in r.stderr or "no devices" in r.stderr):
            # A vanished device makes "absence" checks (no notification, app not in front) pass by accident.
            raise DeviceLost(f"{self.alias} ({self.serial}) is gone: {r.stderr.strip()[:120]}")
        return r.stdout

    def alive(self) -> bool:
        r = subprocess.run(["adb", "-s", self.serial, "get-state"], capture_output=True, text=True, timeout=20)
        return r.stdout.strip() == "device"

    def hook(self, cmd: str, **extras) -> dict:
        try:
            return self.dev.cmd(cmd, timeout_ms=30_000, **extras)
        except Exception as e:  # noqa: BLE001 - report, do not crash the run
            return {"status": "error", "error": str(e)[:200]}

    def launch(self, wait: float = 4.0):
        self.sh(f"am start -n {ACTIVITY}")
        time.sleep(wait)

    def stop(self):
        self.sh(f"am force-stop {PKG}")

    def nodes(self) -> list[tuple[str, list[int], str]]:
        self.sh("uiautomator dump /sdcard/mosh_e2e.xml")
        xml = self.sh("cat /sdcard/mosh_e2e.xml")
        out = []
        for n in re.findall(r"<node [^>]*>", xml):
            t = (re.search(r' text="([^"]*)"', n) or [0, ""])[1] or (re.search(r'content-desc="([^"]*)"', n) or [0, ""])[1]
            b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', n).group(1))))
            out.append((t, b, n))
        return out

    def texts(self) -> list[str]:
        return [t for t, _, _ in self.nodes() if t]

    def tap_bounds(self, b: list[int], wait: float = 2.0):
        self.sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")
        time.sleep(wait)

    def tap(self, label: str, last: bool = False, starts: bool = False) -> bool:
        hits = [b for t, b, _ in self.nodes() if (t.startswith(label) if starts else t == label)]
        if not hits:
            return False
        self.tap_bounds(hits[-1] if last else hits[0])
        return True

    def tab(self, label: str):
        """Bottom navigation item (the lowest node with that label)."""
        if "Find" not in self.texts():
            self.launch()
        # Friends is a page under the You tab, not a tab of its own.
        if label == "Friends":
            self.tab("You")
            time.sleep(1)
        hits = [b for t, b, _ in self.nodes() if t == label]
        if hits:
            self.tap_bounds(max(hits, key=lambda b: b[1]))

    def top_activity(self) -> str:
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity").strip()

    def help_notification(self) -> bool:
        return "channel=mosh_help" in self.sh("dumpsys notification --noredact")

    def crashes(self) -> int:
        return run_adb(self.serial, ["logcat", "-b", "crash", "-d"]).count("FATAL EXCEPTION")

    def network(self, on: bool):
        for s in ("wifi", "data"):
            self.sh(f"svc {s} {'enable' if on else 'disable'}")

    def bluetooth(self, on: bool):
        self.sh(f"cmd bluetooth_manager {'enable' if on else 'disable'}")
        time.sleep(4)

    def location(self, on: bool):
        self.sh(f"cmd location set-location-enabled {'true' if on else 'false'}")
        time.sleep(2)

    def online(self) -> bool:
        return self.hook("net").get("online") is True

    def state_of(self, other: "Phone") -> dict:
        return self.hook("mosh_state", peer=other.peer_id)


def wait(fn, timeout: float, step: float = 5.0):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(step)
    return last


# --- relay -------------------------------------------------------------------------------------

class Relay:
    def __init__(self, port: int, log: Path):
        self.port, self.log, self.proc = port, log, None

    def start(self):
        if self.proc and self.proc.poll() is None:
            return
        self.proc = subprocess.Popen([sys.executable, str(REPO / "tools/release_gate/local_relay.py"), "--port", str(self.port)],
                                     stdout=open(self.log, "a"), stderr=subprocess.STDOUT)
        time.sleep(2)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait(10)
        time.sleep(2)


# --- run context -------------------------------------------------------------------------------

class Run:
    def __init__(self, a: Phone, b: Phone, relay: Relay, relay_url: str):
        self.a, self.b, self.relay, self.relay_url = a, b, relay, relay_url
        self.results: list[dict] = []
        self.scenario = ""

    def check(self, name: str, ok, detail="") -> bool:
        ok = bool(ok)
        self.results.append({"scenario": self.scenario, "check": name, "ok": ok, "detail": str(detail)[:300]})
        print(f"  {'PASS' if ok else 'FAIL'} {name}" + (f"  [{str(detail)[:160]}]" if detail and not ok else ""), flush=True)
        return ok


def onboard(p: Phone):
    """Walk first-run screens, entering the name once, until the MoshGrid tabs show."""
    p.launch(6)
    typed = False
    for _ in range(18):
        tx = p.texts()
        if "Chats" in tx and "Find" in tx:
            return True
        if "Set it up" in tx:
            p.tap("Set it up")
            continue
        if "Your name" in tx and not typed:
            p.tap("Your name")
            p.sh("input keyevent 123")
            for _ in range(40):
                p.sh("input keyevent 67")
            p.sh(f"input text {p.name}")
            time.sleep(1)
            p.sh("input keyevent 4")
            time.sleep(1)
            typed = True
            continue
        if "Start MoshGrid" in tx and typed:
            p.tap("Start MoshGrid")
            continue
        if "Next" in tx and typed:
            p.tap("Next")
            continue
        for label in ("Wait", "Grant Permissions", "Skip for Now", "Allow", "While using the app"):
            if label in tx:
                p.tap(label)
                break
        else:
            p.sh("input swipe 540 1800 540 400 300")
            time.sleep(1.5)
    return "Chats" in p.texts()


def prepare(r: Run):
    """Local relay only (network off while switching), names, friends with internet keys."""
    r.relay.start()
    for p in (r.a, r.b):
        p.network(False)
        p.bluetooth(True)
        p.location(True)
        p.hook("test_relay", url=r.relay_url)
        p.stop()
        time.sleep(1)
        p.launch(6)
        relays = [x.get("url") for x in p.hook("net").get("relays", [])]
        if relays != [r.relay_url]:
            raise SystemExit(f"[{p.alias}] refusing to go online: relays are {relays}, not the local test relay")
    for p in (r.a, r.b):
        p.network(True)
        r.check(f"{p.alias} onboarded as {p.name}", onboard(p))
    r.check("both online via the local relay", wait(lambda: r.a.online() and r.b.online(), 120))
    r.a.peer_id = r.a.hook("whoami").get("peer_id", "")
    r.b.peer_id = r.b.hook("whoami").get("peer_id", "")
    befriend(r)


def befriend(r: Run):
    r.a.hook("handshake", peer=r.b.peer_id)
    r.b.hook("handshake", peer=r.a.peer_id)
    time.sleep(5)
    fa = r.a.hook("mosh_befriend", peer=r.b.peer_id, name=r.b.name, exchange_key="true").get("is_friend")
    fb = r.b.hook("mosh_befriend", peer=r.a.peer_id, name=r.a.name, exchange_key="true").get("is_friend")
    r.check("friends both ways", fa and fb)
    time.sleep(10)


def reset_screens(r: Run):
    """Each scenario starts on Find with nothing open (sheets, dialogs and the shade closed)."""
    for p in (r.a, r.b):
        p.sh("cmd statusbar collapse")
        for _ in range(3):
            p.sh("input keyevent 4")
            time.sleep(0.4)
        p.launch(3)
        p.tab("Find")


def cancel_all(r: Run):
    for p in (r.a, r.b):
        p.hook("mosh_help", action="cancel")


def help_seen(r: Run, receiver: Phone, sender: Phone):
    return receiver.state_of(sender).get("help_active")


def open_help(p: Phone):
    """Find tab, then the Help bar ('Ask for help', or 'Your help alert is on' while it is on)."""
    p.launch()
    p.tab("Find")
    time.sleep(1)
    return p.tap("Your help alert is on", starts=True) or p.tap("Ask for help")


def open_chat(p: Phone, friend: Phone):
    p.launch()
    p.tab("Chats")
    time.sleep(1)
    p.tap(friend.name)
    time.sleep(2)


# --- scenarios ---------------------------------------------------------------------------------

def sc_internet_only(r: Run):
    """Sender's Bluetooth off: Help, cancel and meeting point go over the internet."""
    a, b = r.a, r.b
    a.bluetooth(False)
    routes = a.hook("mosh_help", action="raise").get("routes", {})
    r.check("route is internet", routes.get(b.peer_id) == "NOSTR", routes)
    r.check("B gets Help over internet", wait(lambda: help_seen(r, b, a), 90))
    r.check("B's phone rings (Help notification)", wait(b.help_notification, 60))
    a.hook("mosh_help", action="cancel")
    r.check("B sees cancel over internet", wait(lambda: not help_seen(r, b, a), 90))
    r.check("B's notification removed", wait(lambda: not b.help_notification(), 60))
    a.hook("mosh_send", peer=b.peer_id, kind="meeting", token="e2e1")
    r.check("meeting point over internet", wait(lambda: b.state_of(a).get("meeting_label") == "test e2e1", 90))
    a.bluetooth(True)


def sc_no_signal_queues(r: Run):
    """No Bluetooth and no signal: Help says 'waiting for signal', then goes when signal returns."""
    a, b = r.a, r.b
    a.bluetooth(False)
    a.network(False)
    r.check("A notices it is offline", wait(lambda: not a.online(), 120))
    routes = a.hook("mosh_help", action="raise").get("routes", {})
    r.check("route is QUEUED, not sent", routes.get(b.peer_id) == "QUEUED", routes)
    open_help(a)
    tx = a.texts()
    r.check("Help screen says waiting for signal", any("Waiting for signal" in t for t in tx) and "Help sent" not in tx,
            [t for t in tx if "sent" in t.lower() or "Waiting" in t])
    a.tap("Close")
    time.sleep(15)
    r.check("nothing reached B while offline", not help_seen(r, b, a))
    a.network(True)
    r.check("queued Help arrives once signal returns", wait(lambda: help_seen(r, b, a), 180))
    a.hook("mosh_help", action="cancel")
    wait(lambda: not help_seen(r, b, a), 120)
    a.bluetooth(True)

def sc_receiver_offline(r: Run):
    """Receiver has no signal and no Bluetooth link: the relay holds Help until it is back."""
    a, b = r.a, r.b
    a.bluetooth(False)
    b.network(False)
    wait(lambda: not b.online(), 120)
    a.hook("mosh_help", action="raise")
    time.sleep(20)
    r.check("offline receiver gets nothing yet", not help_seen(r, b, a))
    b.network(True)
    r.check("receiver gets Help after coming online", wait(lambda: help_seen(r, b, a), 180))
    a.hook("mosh_help", action="cancel")
    wait(lambda: not help_seen(r, b, a), 120)
    a.bluetooth(True)


def sc_relay_outage(r: Run):
    """Relay down while sending: queued, then delivered when it is back."""
    a, b = r.a, r.b
    a.bluetooth(False)
    r.relay.stop()
    r.check("A sees the relay gone", wait(lambda: not a.online(), 120))
    routes = a.hook("mosh_help", action="raise").get("routes", {})
    r.check("route is QUEUED while relay is down", routes.get(b.peer_id) == "QUEUED", routes)
    r.relay.start()
    r.check("delivered after the relay returns", wait(lambda: help_seen(r, b, a), 240))
    a.hook("mosh_help", action="cancel")
    wait(lambda: not help_seen(r, b, a), 120)
    a.bluetooth(True)


def sc_back_to_bluetooth(r: Run):
    """Bluetooth comes back: sends go over Bluetooth again."""
    a, b = r.a, r.b

    def mesh():
        rt = a.hook("mosh_help", action="raise").get("routes", {}).get(b.peer_id)
        return rt if rt in ("MESH", "MESH_AND_NOSTR") else None
    r.check("sends go over Bluetooth again", wait(mesh, 240, 30))
    a.hook("mosh_help", action="cancel")
    wait(lambda: not help_seen(r, b, a), 120)


def sc_no_false_alarm_after_replay(r: Run):
    """Help raised then cancelled; receiver restarts, the relay replays everything: no alarm."""
    a, b = r.a, r.b
    a.hook("mosh_help", action="raise")
    wait(lambda: help_seen(r, b, a), 90)
    a.hook("mosh_help", action="cancel")
    r.check("cancel reached B", wait(lambda: not help_seen(r, b, a), 120))
    b.stop()
    time.sleep(2)
    b.launch(6)
    r.check("B online again", wait(b.online, 90))
    time.sleep(30)
    r.check("no Help notification after replay", not b.help_notification())
    r.check("no active Help after replay", not help_seen(r, b, a))


def sc_friend_phone_dies(r: Run):
    """A friend's phone goes dark (app killed, radios off): their last Help and location stay; it catches up when back."""
    a, b = r.a, r.b
    b.hook("mosh_send", peer=a.peer_id, kind="location")
    r.check("A has B's location before", wait(lambda: a.state_of(b).get("has_location"), 60))
    b.hook("mosh_help", action="raise")
    r.check("A has B's Help before", wait(lambda: help_seen(r, a, b), 90))
    # B's phone "dies": no radios, app killed. adb still sees the emulator, so the test can watch.
    b.stop()
    b.sh("cmd connectivity airplane-mode enable")
    b.bluetooth(False)
    time.sleep(60)
    r.check("A keeps B's Help while B is dark", help_seen(r, a, b))
    r.check("A keeps B's last location while B is dark", a.state_of(b).get("has_location"))
    a.stop()
    a.launch(6)
    r.check("A still has both after A restarts", help_seen(r, a, b) and a.state_of(b).get("has_location"))
    routes = a.hook("mosh_help", action="raise").get("routes", {})
    r.check("A's Help to dark B is not reported as sent", routes.get(b.peer_id) not in ("MESH", "MESH_AND_NOSTR"), routes)
    # B comes back.
    b.sh("cmd connectivity airplane-mode disable")
    b.bluetooth(True)
    b.launch(6)
    r.check("B gets A's Help after coming back", wait(lambda: help_seen(r, b, a), 240))
    b.hook("mosh_help", action="cancel")
    r.check("A sees B's cancel", wait(lambda: not help_seen(r, a, b), 180))


def sc_own_help_banner(r: Run):
    """Own Help shows a 'Your Help is on' banner and Help bar; cancelling in the UI clears it for the friend."""
    a, b = r.a, r.b
    a.hook("mosh_help", action="raise")
    a.launch()
    a.tab("Find")
    time.sleep(1)
    tx = a.texts()
    r.check("Find shows 'Your help alert is on' in the Help bar", any(t.startswith("Your help alert is on") for t in tx),
            [t for t in tx if "help" in t.lower()][:4])
    a.tab("Chats")
    time.sleep(1)
    tx = a.texts()
    r.check("other tabs show the 'Your Help is on' banner", any(t.startswith("Your Help is on") for t in tx),
            [t for t in tx if "help" in t.lower()][:4])
    r.check("receiver banner names the friend", wait(lambda: (b.launch(2) or True) and any(t.startswith(f"{a.name} asked for help") for t in b.texts()), 90),
            [t for t in b.texts() if "help" in t.lower()][:3])
    open_help(a)
    a.tap("I'm OK now, cancel")
    time.sleep(2)
    r.check("'I'm OK now, cancel' stops Help", a.hook("mosh_help", action="status").get("alert_id") is None)
    r.check("receiver Help cleared", wait(lambda: not help_seen(r, b, a), 120))

def sc_notification_opens_find(r: Run):
    """Tapping 'X needs help' opens Find on that friend, from any screen."""
    a, b = r.a, r.b
    b.hook("mosh_help", action="raise")
    r.check("A's phone rings", wait(a.help_notification, 120))
    for start in ("You", "Share", "Chats"):
        a.launch()
        a.tab(start)
        a.sh("cmd statusbar expand-notifications")
        time.sleep(2)
        hits = [bb for t, bb, _ in a.nodes() if t.endswith("needs help")]
        if hits:
            a.tap_bounds(hits[0], 3)
        tx = a.texts()
        r.check(f"from {start}: opens Find on {b.name}", any(t.startswith(f"Finding {b.name}") for t in tx), tx[:6])
    b.hook("mosh_help", action="cancel")
    wait(lambda: not help_seen(r, a, b), 120)


def sc_back_navigation(r: Run):
    """Back closes Help, returns other tabs to Chats, and only then leaves the app."""
    a = r.a
    open_help(a)
    a.sh("input keyevent 4")
    time.sleep(2)
    r.check("back closes Help screen, app stays", PKG in a.top_activity() and "Hold for 3 seconds. Let go to stop." not in a.texts())
    for t in ("Find", "Share", "You"):
        a.launch()
        a.tab(t)
        time.sleep(1)
        a.sh("input keyevent 4")
        time.sleep(2)
        r.check(f"back on {t} returns to Chats", PKG in a.top_activity() and "Search chats" in a.texts())
    a.sh("input keyevent 4")
    time.sleep(2)
    r.check("back on Chats leaves the app", PKG not in a.top_activity())

def sc_radios_off_never_block(r: Run):
    """Bluetooth or Location off never blocks the app or the Help screen; banners offer to turn them on."""
    a = r.a
    a.bluetooth(False)
    a.stop()
    a.launch(7)
    tx = a.texts()
    r.check("Bluetooth off: app opens on its tabs", "Chats" in tx and "Find" in tx and "Bluetooth Recommended" not in tx, tx[:4])
    r.check("Bluetooth-off banner", wait(lambda: any(t.startswith("Bluetooth is off") for t in (a.launch(2) or a.texts())), 30, 3))
    r.check("Help screen opens with Bluetooth off", open_help(a) and any("Hold for 3 seconds" in t for t in a.texts()))
    a.location(False)
    a.sh("input keyevent 3")
    time.sleep(2)
    a.launch(5)
    r.check("Location off on resume: still on Help screen", any("Hold for 3 seconds" in t for t in a.texts()))
    a.tap("Cancel")
    r.check("Location-off banner", any(t.startswith("Location is off") for t in a.texts()))
    a.location(True)
    a.bluetooth(True)
    time.sleep(6)
    a.launch(2)
    r.check("banners go away", not any(t.startswith(("Bluetooth is off", "Location is off")) for t in a.texts()))

def sc_messages_both_ways(r: Run):
    """Private messages through the UI both ways; a message to a backgrounded app notifies without crashing."""
    a, b = r.a, r.b

    def send(p: Phone, to: Phone, text: str):
        open_chat(p, to)
        p.tap("Message")
        p.sh(f"input text {text}")
        time.sleep(1)
        p.tap("Send")
        time.sleep(1)
        p.sh("input keyevent 4")
        time.sleep(1)

    def has(p: Phone, frm: Phone, text: str):
        open_chat(p, frm)
        ok = any(text in t for t in p.texts())
        p.sh("input keyevent 4")
        return ok
    send(a, b, "e2e_from_a")
    r.check("B receives A's message", wait(lambda: has(b, a, "e2e_from_a"), 120, 10))
    send(b, a, "e2e_from_b")
    r.check("A receives B's reply", wait(lambda: has(a, b, "e2e_from_b"), 120, 10))
    b.sh("input keyevent 3")
    time.sleep(2)
    pid = b.sh(f"pidof {PKG}").strip()
    send(a, b, "e2e_bg")
    time.sleep(20)
    out = b.sh("dumpsys notification --noredact")
    r.check("backgrounded B gets a message notification", "e2e_bg" in out or "channel=bitchat_dm_notifications" in out)
    r.check("B did not crash", b.sh(f"pidof {PKG}").strip() == pid and b.crashes() == 0)

def sc_meeting_point_ui(r: Run):
    """Long-press sets a meeting point for friends; Clear asks first and clears for everyone."""
    a, b = r.a, r.b
    a.launch()
    a.tab("Find")
    a.tap("Map")
    time.sleep(4)
    mb = [bb for t, bb, _ in a.nodes() if t.startswith("Showing a Map")]
    if not r.check("map shown", mb):
        return
    cx, cy = (mb[0][0] + mb[0][2]) // 2, (mb[0][1] + mb[0][3]) // 2
    a.sh(f"input swipe {cx} {cy} {cx} {cy} 1200")
    time.sleep(2)
    a.tap("Name it")
    a.sh("input text Gate9")
    time.sleep(1)
    a.tap("Set for all friends")
    time.sleep(2)
    a.tap("List")
    time.sleep(2)
    r.check("meeting point set", any(t.startswith("Gate9") for t in a.texts()), [t for t in a.texts()][:12])
    r.check("friend receives it", wait(lambda: b.state_of(a).get("meeting_label") == "Gate9", 120))
    a.tap("Clear", last=True)
    r.check("Clear asks first", "Clear the meeting point?" in a.texts())
    a.tap("Keep it")
    r.check("'Keep it' keeps it", any(t.startswith("Gate9") for t in a.texts()))
    a.tap("Clear", last=True)
    a.tap("Clear for everyone")
    r.check("cleared for the friend", wait(lambda: b.state_of(a).get("meeting_label") is None, 120))


def sc_remove_friend(r: Run):
    """Destructive: A removes B. Friendship is restored afterwards for later scenarios."""
    a, b = r.a, r.b
    a.launch()
    a.tab("Friends")
    time.sleep(1.5)
    a.tap(b.name)
    time.sleep(1)
    a.tap(f"Remove {b.name}")
    r.check("confirm dialog names the friend", any(t.startswith(f"Remove {b.name}?") for t in a.texts()))
    a.tap("Cancel")
    r.check("Cancel keeps the friend", a.state_of(b).get("is_friend"))
    a.tap(f"Remove {b.name}")
    a.tap("Remove", last=True)
    time.sleep(3)
    r.check("removed on A", not a.state_of(b).get("is_friend"))
    b.hook("mosh_help", action="raise")
    time.sleep(20)
    r.check("B's Help no longer reaches A", not help_seen(r, a, b))
    b.hook("mosh_help", action="cancel")
    befriend(r)

def sc_wipe(r: Run):
    """Destructive: Erase everything with Help on sends a final cancel and clears MoshGrid data."""
    a, b = r.a, r.b
    a.hook("mosh_help", action="raise")
    r.check("friend has A's Help before wipe", wait(lambda: help_seen(r, b, a), 120))
    a.launch()
    a.tab("You")
    time.sleep(1)
    a.sh("input swipe 540 1800 540 600 300")
    time.sleep(1)
    if not r.check("Erase row found", a.tap("Erase everything on this phone")):
        return
    r.check("asks to confirm", "Erase everything?" in a.texts())
    a.tap("Erase everything", last=True)
    time.sleep(8)
    r.check("setup shows again", "Set it up" in a.texts())
    r.check("A's Help stopped", a.hook("mosh_help", action="status").get("alert_id") is None)
    r.check("friend got the final cancel", wait(lambda: not help_seen(r, b, a), 150))

# --- scenarios added for 1.4 (groups, sharing and internet switches, invites) ------------------

def open_you_page(p: Phone, row: str):
    p.launch()
    p.tab("You")
    time.sleep(1)
    p.tap(row)
    time.sleep(1.5)


def set_switch(p: Phone, page: str, label: str, on: bool, key: str, other: Phone):
    """Flip a switch in You > page through the UI until the app reports [key] == on."""
    open_you_page(p, page)
    if p.state_of(other).get(key) != on:
        p.tap(label, last=True)
        time.sleep(2)
    p.sh("input keyevent 4")
    return p.state_of(other).get(key) == on


def group_of(p: Phone, other: Phone, name: str):
    return next((g for g in p.state_of(other).get("groups") or [] if g.get("name") == name), None)


def sc_location_sharing(r: Run):
    """Location sharing: the friend gets updates while it is on, none while off, and again when on."""
    a, b = r.a, r.b
    for p in (a, b):
        p.sh("true")
        run_adb(p.serial, ["emu", "geo", "fix", "77.0664", "28.4689"])  # public venue point, synthetic
    r.check("B's sharing is on", set_switch(b, "Location", "Share my location", True, "sharing", a))
    b.hook("mosh_me", n="5", e="5")
    first = wait(lambda: a.state_of(b).get("location_at"), 180, 10)
    r.check("A sees B's location while sharing is on", first)
    r.check("B switches sharing off in the app", set_switch(b, "Location", "Share my location", False, "sharing", a))
    time.sleep(20)
    before = a.state_of(b).get("location_at")
    b.hook("mosh_me", n="80", e="80")
    time.sleep(150)
    after = a.state_of(b).get("location_at")
    r.check("no new location reaches A while sharing is off", after == before, (before, after))
    r.check("B switches sharing on again", set_switch(b, "Location", "Share my location", True, "sharing", a))
    b.hook("mosh_me", n="120", e="120")
    r.check("updates reach A again", wait(lambda: (a.state_of(b).get("location_at") or 0) > (before or 0), 240, 10))


def sc_internet_switch(r: Run):
    """The app's Internet switch: off disconnects the relay and Help waits; on again delivers it."""
    a, b = r.a, r.b
    a.bluetooth(False)  # emulators have no Bluetooth link anyway; make the route plain
    r.check("A switches Internet off in the app", set_switch(a, "Network", "Internet", False, "internet", b))
    r.check("A reports offline", wait(lambda: not a.online(), 90))
    routes = a.hook("mosh_help", action="raise").get("routes", {})
    r.check("Help is queued, not sent", routes.get(b.peer_id) in ("QUEUED", None) or routes.get(b.peer_id) != "NOSTR", routes)
    time.sleep(30)
    r.check("B has not got Help while A's Internet is off", not help_seen(r, b, a))
    r.check("A switches Internet on again", set_switch(a, "Network", "Internet", True, "internet", b))
    r.check("A back online", wait(lambda: a.online(), 120))
    r.check("queued Help reaches B", wait(lambda: help_seen(r, b, a), 150))
    a.hook("mosh_help", action="cancel")
    r.check("cancel reaches B", wait(lambda: not help_seen(r, b, a), 120))
    a.bluetooth(True)


def sc_group_both_ways(r: Run):
    """Group made in the UI: the friend gets it, messages go both ways, ticks reach delivered/read."""
    a, b = r.a, r.b
    name = "E2Egang"
    a.launch()
    a.tab("Chats")
    a.tap("More options")
    a.tap("New group")
    time.sleep(1.5)
    a.tap("Group name")
    a.sh(f"input text {name}")
    a.sh("input keyevent 4")
    time.sleep(1)
    a.tap(b.name)
    a.tap("Make group")
    time.sleep(3)
    r.check("A has the group", group_of(a, b, name))
    r.check("B gets the group", wait(lambda: group_of(b, a, name), 150, 10))
    a.tap("Message")
    a.sh("input text e2e_group_hello")
    time.sleep(1)
    a.tap("Send")
    time.sleep(2)
    r.check("B gets A's group message", wait(lambda: any(m["text"] == "e2e_group_hello" for m in (group_of(b, a, name) or {}).get("messages", [])), 150, 10))
    def a_states():
        g = group_of(a, b, name) or {}
        m = next((m for m in g.get("messages", []) if m["text"] == "e2e_group_hello"), {})
        return set(m.get("states", []))
    r.check("A's message reaches delivered", wait(lambda: a_states() & {"DELIVERED", "READ"}, 150, 10), a_states())
    b.launch()
    b.tab("Chats")
    time.sleep(1)
    b.tap(name, starts=True)
    time.sleep(2)
    r.check("A's message shows read once B opens the chat", wait(lambda: "READ" in a_states(), 150, 10), a_states())
    b.tap("Message")
    b.sh("input text e2e_group_reply")
    time.sleep(1)
    b.tap("Send")
    r.check("A gets B's reply", wait(lambda: any(m["text"] == "e2e_group_reply" and not m["mine"] for m in (group_of(a, b, name) or {}).get("messages", [])), 150, 10))
    b.sh("input keyevent 4")


def sc_invite_link(r: Run):
    """Destructive-ish: A removes B, B opens A's invite link, sends a request, A accepts: friends again."""
    a, b = r.a, r.b
    link = a.hook("mosh_link").get("link")
    if not r.check("A has an invite link", link and "/add#" in link, link):
        return
    a.launch()
    a.tab("Friends")
    time.sleep(1.5)
    a.tap(b.name)
    time.sleep(1)
    a.tap(f"Remove {b.name}")
    a.tap("Remove", last=True)
    time.sleep(3)
    r.check("not friends before the invite", not a.state_of(b).get("is_friend"))
    b.sh(f"am start -a android.intent.action.VIEW -d '{link}' -p {PKG}")
    time.sleep(4)
    r.check("B is asked to add A", any(t.startswith(f"Add {a.name}") for t in b.texts()), b.texts()[:12])
    b.tap("Send request")
    r.check("A is not friends until A accepts", not a.state_of(b).get("is_friend"))
    for _ in range(3):  # leave the Friends page: there the request is a card, not a banner
        a.sh("input keyevent 4")
        time.sleep(0.5)
    a.launch()
    a.tab("Chats")
    r.check("A gets B's request (banner)", wait(lambda: any("wants to add you" in t for t in (a.launch(2) or a.texts())), 150, 10))
    r.check("still not friends before Accept", not a.state_of(b).get("is_friend"))
    a.tap("View")
    time.sleep(1.5)
    r.check("Friends page shows the request card", any("Used your invite link" in t for t in a.texts()))
    a.tap("Accept")
    r.check("friends again on A", wait(lambda: a.state_of(b).get("is_friend"), 60))
    r.check("friends again on B", wait(lambda: b.state_of(a).get("is_friend"), 150, 10))


def sc_share_and_report_ui(r: Run):
    """Share tab and Report a problem open on both phones; with Internet off the report says why it can't send."""
    for p, other in ((r.a, r.b), (r.b, r.a)):
        p.launch()
        p.tab("Share")
        tx = p.texts()
        r.check(f"{p.alias} Share tab shows", "Share" in tx and ("My code" in tx or "Add my links" in tx), tx[:10])
        open_you_page(p, "Report a problem")
        r.check(f"{p.alias} Report a problem opens", "Send report" in p.texts())
        p.sh("input keyevent 4")
    set_switch(r.a, "Network", "Internet", False, "internet", r.b)
    open_you_page(r.a, "Report a problem")
    r.check("Report says Internet is off", any("Internet is off for MoshGrid" in t for t in r.a.texts()))
    r.a.sh("input keyevent 4")
    set_switch(r.a, "Network", "Internet", True, "internet", r.b)


SCENARIOS = {
    "internet_only": sc_internet_only,
    "no_signal_queues": sc_no_signal_queues,
    "receiver_offline": sc_receiver_offline,
    "relay_outage": sc_relay_outage,
    "back_to_bluetooth": sc_back_to_bluetooth,
    "no_false_alarm_after_replay": sc_no_false_alarm_after_replay,
    "friend_phone_dies": sc_friend_phone_dies,
    "own_help_banner": sc_own_help_banner,
    "notification_opens_find": sc_notification_opens_find,
    "back_navigation": sc_back_navigation,
    "messages_both_ways": sc_messages_both_ways,
    "meeting_point_ui": sc_meeting_point_ui,
    "location_sharing": sc_location_sharing,
    "internet_switch": sc_internet_switch,
    "group_both_ways": sc_group_both_ways,
    "share_and_report_ui": sc_share_and_report_ui,
    "invite_link": sc_invite_link,       # removes and re-adds the friend through an invite link
    "remove_friend": sc_remove_friend,   # destructive, restores friendship
    "radios_off_never_block": sc_radios_off_never_block,
    "wipe": sc_wipe,                     # destructive, needs mesh_lab setup afterwards
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial-a", required=False)
    ap.add_argument("--serial-b", required=False)
    ap.add_argument("--relay-port", type=int, default=7777)
    ap.add_argument("--relay-url", default=None, help="URL the devices use (default ws://10.0.2.2:<port> for emulators)")
    ap.add_argument("--out", default="/tmp/mosh-e2e")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--skip-prepare", action="store_true", help="devices already on the local relay and befriended")
    ap.add_argument("scenarios", nargs="*", default=["all"])
    args = ap.parse_args()
    if args.list:
        for k, f in SCENARIOS.items():
            print(f"{k:30s} {(f.__doc__ or '').strip().splitlines()[0] if f.__doc__ else ''}")
        return
    if not args.serial_a or not args.serial_b:
        ap.error("--serial-a and --serial-b are required")
    names = list(SCENARIOS) if args.scenarios == ["all"] else args.scenarios
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        ap.error(f"unknown scenarios: {unknown}")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    relay = Relay(args.relay_port, out / "relay.log")
    r = Run(Phone(args.serial_a, "A", "Ana"), Phone(args.serial_b, "B", "Ben"), relay,
            args.relay_url or f"ws://10.0.2.2:{args.relay_port}")
    missing = [p.serial for p in (r.a, r.b) if not p.alive()]
    if missing:
        raise SystemExit(f"Not connected: {', '.join(missing)}. Boot the emulators (or plug in phones) and check `adb devices`.")
    for p in (r.a, r.b):
        run_adb(p.serial, ["logcat", "-b", "crash", "-c"])
    try:
        r.scenario = "prepare"
        print("== prepare", flush=True)
        if args.skip_prepare:
            r.a.peer_id = r.a.hook("whoami").get("peer_id", "")
            r.b.peer_id = r.b.hook("whoami").get("peer_id", "")
        else:
            prepare(r)
        for name in names:
            r.scenario = name
            print(f"== {name}", flush=True)
            for p in (r.a, r.b):
                if not p.alive():
                    raise DeviceLost(f"{p.alias} ({p.serial}) is not connected")
            try:
                reset_screens(r)
                SCENARIOS[name](r)
            except DeviceLost:
                raise
            except Exception as e:  # noqa: BLE001 - one scenario failing must not stop the rest
                r.check("scenario ran without error", False, repr(e))
            finally:
                try:
                    cancel_all(r)
                except DeviceLost:
                    pass
        r.scenario = "crashes"
        for p in (r.a, r.b):
            r.check(f"{p.alias} no crashes", p.crashes() == 0)
    except DeviceLost as e:
        r.check("devices stayed connected", False, str(e))
        print(f"\nSTOPPED: {e}. Results after this point would be meaningless; reboot the device and rerun.", flush=True)
    finally:
        relay.stop()
    failed = [x for x in r.results if not x["ok"]]
    (out / "results.json").write_text(json.dumps(r.results, indent=2))
    print(f"\n{len(r.results) - len(failed)}/{len(r.results)} checks passed; results in {out / 'results.json'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    os.environ["PATH"] = os.path.expanduser("~/Library/Android/sdk/platform-tools") + os.pathsep + os.environ.get("PATH", "")
    main()

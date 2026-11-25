#!/usr/bin/env python3
"""Build the MoshGrid landing page into website/dist (APKs, source zip, filled-in HTML)."""
import hashlib, json, re, shutil, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "website"
DIST = SITE / "dist"
SITE_URL = "https://moshgrid.vercel.app/"
VERSION = re.search(r'versionName = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
VERSION = VERSION.replace("-moshgrid", "")
REL = ROOT / "app/build/outputs/apk/release"

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def mb(p): return f"{p.stat().st_size / 1e6:.0f} MB"

shutil.rmtree(DIST, ignore_errors=True)
(DIST / "downloads").mkdir(parents=True)
for d in ("fonts", "img"):
    shutil.copytree(SITE / d, DIST / d)

arm = DIST / "downloads" / f"MoshGrid-{VERSION}.apk"
uni = DIST / "downloads" / f"MoshGrid-{VERSION}-all-phones.apk"
shutil.copy(REL / "app-arm64-v8a-release.apk", arm)
shutil.copy(REL / "app-universal-release.apk", uni)

# Corresponding source (GPL-3.0): the committed tree, without the private planning docs.
src = DIST / "downloads" / f"MoshGrid-{VERSION}-source.zip"
subprocess.run(["git", "-C", str(ROOT), "archive", "--format=zip", f"--prefix=MoshGrid-{VERSION}/", "-o", str(src),
                "HEAD", ":(exclude)docs/superpowers", ":(exclude)docs/research"], check=True)

bt = sorted((Path.home() / "Library/Android/sdk/build-tools").iterdir())[-1]
certs = subprocess.run([str(bt / "apksigner"), "verify", "--print-certs", str(arm)], capture_output=True, text=True).stdout
cert = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", certs).group(1)

icon = (ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
p1, p2 = re.findall(r'android:pathData="(M[^"]+L[^"]+)"', icon)[:2]
(DIST / "poi.svg").write_text(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="22 22 64 64"><rect x="22" y="22" width="64" height="64" rx="14" fill="#000"/>'
    f'<path d="{p1}" transform="rotate(22 54 54)" fill="none" stroke="#FF5CDB" stroke-width="3.4" stroke-linecap="round"/>'
    f'<path d="{p2}" fill="none" stroke="#3DF2FF" stroke-width="3.4" stroke-linecap="round"/>'
    f'<circle cx="81" cy="54" r="4.8" transform="rotate(22 54 54)" fill="#FF5CDB"/><circle cx="54" cy="54" r="3.7" fill="#fff"/></svg>')

html = (SITE / "index.template.html").read_text()
values = {"P1": p1, "P2": p2, "VERSION": VERSION, "APK_ARM": arm.name, "APK_UNI": uni.name, "SRC_ZIP": src.name,
          "SIZE_ARM": mb(arm), "SIZE_UNI": mb(uni), "SIZE_SRC": mb(src), "SHA_ARM": sha(arm), "SHA_UNI": sha(uni),
          "CERT_SHA": ":".join(cert[i:i + 2] for i in range(0, len(cert), 2)).upper()}
for k, v in values.items():
    html = html.replace("{{" + k + "}}", v)
assert "{{" not in html, re.findall(r"\{\{\w+\}\}", html)
(DIST / "index.html").write_text(html)
(DIST / "vercel.json").write_text(json.dumps({"headers": [
    {"source": "/downloads/(.*)", "headers": [{"key": "Content-Disposition", "value": "attachment"}]},
    {"source": "/(fonts|img)/(.*)", "headers": [{"key": "Cache-Control", "value": "public, max-age=604800"}]},
    {"source": "/(.*)", "headers": [{"key": "X-Content-Type-Options", "value": "nosniff"},
                                    {"key": "Referrer-Policy", "value": "no-referrer"},
                                    {"key": "X-Frame-Options", "value": "DENY"},
                                    {"key": "Permissions-Policy", "value": "camera=(), microphone=(), geolocation=()"}]}]}) + "\n")

# Search engines and installable-site metadata.
(DIST / "robots.txt").write_text(f"User-agent: *\nAllow: /\nDisallow: /downloads/\n\nSitemap: {SITE_URL}sitemap.xml\n")
(DIST / "sitemap.xml").write_text('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                                  f'<url><loc>{SITE_URL}</loc></url></urlset>\n')
(DIST / "site.webmanifest").write_text(json.dumps({
    "name": "MoshGrid", "short_name": "MoshGrid", "start_url": "/", "display": "browser",
    "background_color": "#000000", "theme_color": "#000000",
    "icons": [{"src": "img/icon-192.png", "sizes": "192x192", "type": "image/png"},
              {"src": "img/icon-512.png", "sizes": "512x512", "type": "image/png"}]}, indent=1) + "\n")
for k in ("APK_ARM", "SIZE_ARM", "SIZE_UNI", "SIZE_SRC", "CERT_SHA"):
    print(k, values[k])

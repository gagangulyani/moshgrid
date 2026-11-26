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
VERSION_CODE = int(re.search(r"versionCode = (\d+)", (ROOT / "app/build.gradle.kts").read_text()).group(1))
REL = ROOT / "app/build/outputs/apk/release"

from html import escape as html_escape
LEGAL_DATE = "26 November 2026"  # change when the privacy policy or terms change

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

owner = json.loads((SITE / "owner.json").read_text())
missing = [k for k in ("name", "contact", "bio") if not str(owner.get(k, "")).strip()]
assert not missing, f"website/owner.json is missing: {missing}"
contact = owner["contact"].strip()
CONTACT_HREF = ("mailto:" + contact) if "@" in contact and "://" not in contact else contact
html = (SITE / "index.template.html").read_text()
values = {"P1": p1, "P2": p2, "VERSION": VERSION, "APK_ARM": arm.name, "APK_UNI": uni.name, "SRC_ZIP": src.name,
          "SIZE_ARM": mb(arm), "SIZE_UNI": mb(uni), "SIZE_SRC": mb(src), "SHA_ARM": sha(arm), "SHA_UNI": sha(uni),
          "CERT_SHA": ":".join(cert[i:i + 2] for i in range(0, len(cert), 2)).upper()}
for k, v in values.items():
    html = html.replace("{{" + k + "}}", v)
# "Made by": paragraphs of the bio, then links, all escaped (owner.json is plain text).
bio = "\n    ".join(f"<p>{html_escape(p.strip())}</p>" for p in owner["bio"].split("\n\n") if p.strip())
links = owner.get("links") or []
for l in links: assert l["url"].startswith("https://"), l
links_html = ("<ul>" + "".join(f'<li><a href="{html_escape(l["url"])}">{html_escape(l["label"])}</a></li>' for l in links) + "</ul>") if links else ""
for k, v in {"OWNER_NAME": html_escape(owner["name"].strip()), "OWNER_BIO": bio, "OWNER_LINKS": links_html, "CONTACT_HREF": html_escape(CONTACT_HREF)}.items():
    html = html.replace("{{" + k + "}}", v)
assert "{{" not in html, re.findall(r"\{\{\w+\}\}", html)
# Images are cached for a week, so tag each one with its content hash: a changed image gets a new URL.
def versioned(text):
    for img in (SITE / "img").iterdir():
        text = text.replace(f"img/{img.name}\"", f"img/{img.name}?v={sha(img)[:8]}\"")
    return text
html = versioned(html)
(DIST / "index.html").write_text(html)

# Invite links land here (https://moshgrid.vercel.app/add#code); the code stays in the fragment.
add = (SITE / "add.template.html").read_text().replace("{{VERSION}}", VERSION)
(DIST / "add.html").write_text(versioned(add))

# Socials codes land here (https://moshgrid.vercel.app/s#…); the links stay in the fragment.
(DIST / "s.html").write_text(versioned((SITE / "s.template.html").read_text().replace("{{VERSION}}", VERSION)))

# Privacy policy and terms (https://moshgrid.vercel.app/privacy, /terms). Who runs MoshGrid comes from owner.json;
# the build stops if any of it is missing, so a placeholder can never go live.
legal = {"OWNER_NAME": html_escape(owner["name"].strip()), "CONTACT": html_escape(contact),
         "CONTACT_HREF": html_escape(CONTACT_HREF),
         "LEGAL_DATE": LEGAL_DATE,
         "LEGAL_STYLE": (SITE / "legal.css.html").read_text()}
for page in ("privacy", "terms"):
    text = (SITE / f"{page}.template.html").read_text()
    for k, v in legal.items():
        text = text.replace("{{" + k + "}}", v)
    assert "{{" not in text, re.findall(r"\{\{\w+\}\}", text)
    (DIST / f"{page}.html").write_text(versioned(text))

# Lets Android open invite links straight in the app (verified App Link), for the release key only.
(DIST / ".well-known").mkdir()
(DIST / ".well-known" / "assetlinks.json").write_text(json.dumps([{
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {"namespace": "android_app", "package_name": "com.moshgrid.android",
               "sha256_cert_fingerprints": [values["CERT_SHA"]]}}], indent=1) + "\n")

# The app's update check reads this: newest version and where to get it, with checksums.
(DIST / "version.json").write_text(json.dumps({
    "versionCode": VERSION_CODE, "versionName": VERSION,
    "apk": {"arm64-v8a": {"url": SITE_URL + "downloads/" + arm.name, "sha256": values["SHA_ARM"]},
            "universal": {"url": SITE_URL + "downloads/" + uni.name, "sha256": values["SHA_UNI"]}}}, indent=1) + "\n")
# Content Security Policy per page: only that page's own inline scripts (by hash) may run, nothing
# loads from other sites, and nothing can be framed or posted. Inline styles stay allowed.
import base64
def csp_for(page_html):
    hashes = []
    for body in re.findall(r"<script[^>]*>(.*?)</script>", page_html, re.S):
        hashes.append("'sha256-" + base64.b64encode(hashlib.sha256(body.encode()).digest()).decode() + "'")
    script = " ".join(hashes) if hashes else "'none'"
    return ("default-src 'none'; script-src " + script + "; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            "font-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; "
            "frame-ancestors 'none'; upgrade-insecure-requests")
csp_headers = [{"source": src, "headers": [{"key": "Content-Security-Policy", "value": csp_for((DIST / page).read_text())}]}
               for src, page in (("/", "index.html"), ("/add", "add.html"), ("/s", "s.html"),
                                 ("/privacy", "privacy.html"), ("/terms", "terms.html"))]
(DIST / "vercel.json").write_text(json.dumps({"cleanUrls": True, "headers": [
    {"source": "/version.json", "headers": [{"key": "Cache-Control", "value": "no-cache"}]},
    {"source": "/downloads/(.*)", "headers": [{"key": "Content-Disposition", "value": "attachment"}]},
    {"source": "/(fonts|img)/(.*)", "headers": [{"key": "Cache-Control", "value": "public, max-age=604800"}]},
    {"source": "/(.*)", "headers": [{"key": "X-Content-Type-Options", "value": "nosniff"},
                                    {"key": "Referrer-Policy", "value": "no-referrer"},
                                    {"key": "X-Frame-Options", "value": "DENY"},
                                    {"key": "Permissions-Policy", "value": "camera=(), microphone=(), geolocation=()"}]}] + csp_headers}) + "\n")

# Search engines and installable-site metadata.
(DIST / "robots.txt").write_text(f"User-agent: *\nAllow: /\nDisallow: /downloads/\n\nSitemap: {SITE_URL}sitemap.xml\n")
(DIST / "sitemap.xml").write_text('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                                  f'<url><loc>{SITE_URL}</loc></url><url><loc>{SITE_URL}privacy</loc></url><url><loc>{SITE_URL}terms</loc></url></urlset>\n')
(DIST / "site.webmanifest").write_text(json.dumps({
    "name": "MoshGrid", "short_name": "MoshGrid", "start_url": "/", "display": "browser",
    "background_color": "#000000", "theme_color": "#000000",
    "icons": [{"src": "img/icon-192.png", "sizes": "192x192", "type": "image/png"},
              {"src": "img/icon-512.png", "sizes": "512x512", "type": "image/png"}]}, indent=1) + "\n")
for k in ("APK_ARM", "SIZE_ARM", "SIZE_UNI", "SIZE_SRC", "CERT_SHA"):
    print(k, values[k])

"""Fox Ticket für Windows – Prüf-Logik (ohne Oberfläche).

Steuert Microsoft Edge über Playwright. Eventim lässt einfache Abrufe nicht durch,
einen echten Browser aber schon. Das Edge-Profil (Anmeldung, Warenkorb) liegt in
%APPDATA%\\FoxTicket\\edge-profile.
"""
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "FoxTicket"
CONFIG_PATH = APP_DIR / "config.json"
PROFILE_DIR = APP_DIR / "edge-profile"
ACCOUNT_URL = "https://www.eventim.de/myAccount/"

EVENT_LINK = re.compile(r"(?:https?:)?(?:\\?/\\?/www\.eventim\.de)?\\?/event\\?/([a-z0-9-]+-\d{5,})", re.I)
LD_JSON = re.compile(r"<script[^>]*application/ld\+json[^>]*>([\s\S]*?)</script>", re.I)
ARTIST = re.compile(r"/artist/([a-z0-9-]+)", re.I)

DEFAULT_CONFIG = {
    "watch": [{"name": "Fall Out Boy", "url": "https://www.eventim.de/artist/fall-out-boy/"}],
    "known": {},
    "last": None,
    "interval": 5,
    "autostart": False,
    "email": "",
    "pw": "",
    "login": {},
    "cartLog": "",
    "tgToken": "",
    "tgChat": "",
}


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def load_config():
    APP_DIR.mkdir(parents=True, exist_ok=True)
    cfg = dict(DEFAULT_CONFIG)
    try:
        cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
    except Exception:  # noqa: BLE001
        pass
    return cfg


def save_config(cfg):
    APP_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, CONFIG_PATH)


# ---------- Passwort mit Windows-DPAPI verschlüsseln (nur dieser Windows-Benutzer kann es lesen) ----------
def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    buf = ctypes.create_string_buffer(data, len(data))
    inp = BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = BLOB()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(inp), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI fehlgeschlagen")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def encrypt_pw(pw: str) -> str:
    import base64
    if not pw:
        return ""
    try:
        return "dpapi:" + base64.b64encode(_dpapi(pw.encode("utf-8"), True)).decode()
    except Exception:  # noqa: BLE001  (nicht unter Windows)
        return ""


def decrypt_pw(enc: str) -> str:
    import base64
    if not enc or not enc.startswith("dpapi:"):
        return ""
    try:
        return _dpapi(base64.b64decode(enc[6:]), False).decode("utf-8")
    except Exception:  # noqa: BLE001
        return ""


# ---------- Text-Auswertung (gleich wie in der Android-App) ----------
def slug_of(url):
    m = ARTIST.search(url or "")
    return m.group(1).lower() if m else ""


def is_event(url):
    return "/event/" in (url or "")


def title_from_id(i):
    return " ".join(w[:1].upper() + w[1:] for w in re.sub(r"-\d+$", "", i).split("-") if w)


STATUS_TEXT = {
    "InStock": "Tickets verfügbar",
    "LimitedAvailability": "Teilweise verfügbar",
    "SoldOut": "Ausverkauft",
    "PreOrder": "Vorverkauf noch nicht gestartet",
    "PreSale": "Vorverkauf noch nicht gestartet",
    "NotAvailable": "Zurzeit nicht verfügbar",
    "": "Status unbekannt",
}


def buyable(st):
    return st in ("InStock", "LimitedAvailability", "OnlineOnly")


def extract_events(text, slug):
    found = {}
    for m in EVENT_LINK.finditer(text):
        i = m.group(1).lower()
        if slug and slug not in i:
            continue
        link = f"https://www.eventim.de/event/{i}/"
        found.setdefault(link, {"link": link, "title": title_from_id(i)})
    for m in LD_JSON.finditer(text):
        try:
            data = json.loads(m.group(1).strip())
        except Exception:  # noqa: BLE001
            continue
        evs = []

        def walk(x):
            if isinstance(x, list):
                for y in x:
                    walk(y)
            elif isinstance(x, dict):
                if "startDate" in x and "url" in x:
                    evs.append(x)
                for v in x.values():
                    if isinstance(v, (dict, list)):
                        walk(v)

        walk(data)
        for e in evs:
            mm = EVENT_LINK.search(str(e.get("url", "")))
            if not mm:
                continue
            i = mm.group(1).lower()
            if slug and slug not in i:
                continue
            link = f"https://www.eventim.de/event/{i}/"
            loc = e.get("location") or {}
            if isinstance(loc, list):
                loc = loc[0] if loc else {}
            addr = loc.get("address") or {}
            city = addr.get("addressLocality", "") if isinstance(addr, dict) else ""
            place = ", ".join(x for x in [loc.get("name", ""), city] if x)
            offers = e.get("offers") or {}
            if isinstance(offers, list):
                offers = offers[0] if offers else {}
            found[link] = {
                "link": link,
                "title": e.get("name") or title_from_id(i),
                "date": e.get("startDate", ""),
                "place": place,
                "availability": str(offers.get("availability", "")).rsplit("/", 1)[-1],
            }
    return list(found.values())


def price_of(text):
    m = re.search(r"(\d{1,3}(?:\.\d{3})*,\d{2})", text)
    if not m:
        return -1.0
    try:
        return float(m.group(1).replace(".", "").replace(",", "."))
    except ValueError:
        return -1.0


def telegram_send(cfg, text):
    """Gibt None bei Erfolg zurück, sonst eine Fehlermeldung."""
    import urllib.error
    import urllib.parse
    import urllib.request
    token, chat = decrypt_pw(cfg.get("tgToken", "")), str(cfg.get("tgChat", "")).strip()
    if not token or not chat:
        return "nicht eingerichtet"
    try:
        data = urllib.parse.urlencode({"chat_id": chat, "text": text}).encode()
        urllib.request.urlopen(f"https://api.telegram.org/bot{token}/sendMessage", data=data, timeout=15).read()
        return None
    except urllib.error.HTTPError as e:
        if e.code in (401, 404):
            return "Bot-Token ist falsch"
        if e.code in (400, 403):
            return "Chat-ID falsch oder du hast deinem Bot noch nicht „/start“ geschrieben"
        return f"Telegram antwortet mit HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return f"Netzwerkfehler: {e}"


def cat_available(text):
    cl = text.lower()
    no = "nicht verfügbar" in cl or "ausverkauft" in cl or "nicht mehr verfügbar" in cl
    rest = cl.replace("nicht mehr verfügbar", "").replace("nicht verfügbar", "")
    return (not no) or "verfügbar" in rest or "warenkorb" in rest


def analyze_event(text, sel, max_price=0):
    lines = [re.sub(r"\s+", " ", l).strip() for l in text.split("\n")]
    lines = [l for l in lines if l]
    sel = [s.lower() for s in (sel or [])]
    cats, considered, unavailable, any_buy, eligible = [], 0, 0, False, []
    for i, l in enumerate(lines):
        if not l.lower().startswith("kategorie"):
            continue
        info = []
        for n in lines[i + 1:i + 6]:
            nl = n.lower()
            if nl.startswith(("kategorie", "versand", "informationen")):
                break
            if len(n) > 60 or re.fullmatch(r"[−+\-0-9]{1,3}", n):
                continue
            info.append(n)
        av = cat_available(l + " " + " ".join(info))
        price = price_of(" ".join(info))
        too_exp = bool(max_price) and (price < 0 or price > max_price)
        is_sel = (not sel or l.lower() in sel) and not too_exp
        if is_sel and av:
            eligible.append(l)
        cats.append({"name": l, "info": " · ".join(info), "available": av, "tooExpensive": too_exp})
        if is_sel:
            considered += 1
            if av:
                any_buy = True
            else:
                unavailable += 1
    low = text.lower()
    if cats and considered:
        status = ("LimitedAvailability" if unavailable else "InStock") if any_buy else ("SoldOut" if "ausverkauft" in low else "NotAvailable")
    elif cats:
        status = "NotAvailable"
    elif "in den warenkorb" in low:
        status = "InStock"
    elif "ausverkauft" in low:
        status = "SoldOut"
    elif "zurzeit nicht verfügbar" in low:
        status = "NotAvailable"
    else:
        status = ""
    summary = "\n".join(c["name"] + (" · " + c["info"] if c["info"] else "") for c in cats)
    return status, summary, cats, eligible


def looks_ready(text):
    t = text.lower()
    return any(w in t for w in ("kategorie", "verfügbar", "ausverkauft", "warenkorb", "keine veranstaltungen", "termine"))


def fmt_date(d):
    if not d or len(d) < 10:
        return ""
    return f"{d[8:10]}.{d[5:7]}.{d[0:4]}"


class Engine:
    """Läuft komplett in EINEM Thread (Playwright-Vorgabe)."""

    def __init__(self, log=print, notify=None):
        self.log = log
        self.notify = notify or (lambda *a, **k: None)
        self.pw = None
        self.ctx = None
        self.cart_page = None
        self.cart_js = open(resource("cart.js"), encoding="utf-8").read()
        self.login_js = open(resource("login.js"), encoding="utf-8").read()

    # ----- Browser -----
    def _alive(self):
        try:
            return self.ctx is not None and self.ctx.pages is not None and (self.ctx.browser is None or self.ctx.browser.is_connected())
        except Exception:  # noqa: BLE001
            return False

    def browser(self):
        if self._alive():
            return self.ctx
        from playwright.sync_api import sync_playwright
        if self.pw is None:
            self.pw = sync_playwright().start()
        PROFILE_DIR.mkdir(parents=True, exist_ok=True)
        last_err = None
        for channel in ("msedge", "chrome", None):
            try:
                kw = dict(
                    user_data_dir=str(PROFILE_DIR),
                    headless=False,
                    no_viewport=True,
                    args=["--window-position=-32000,-32000", "--window-size=1200,950", "--disable-blink-features=AutomationControlled"],
                    ignore_default_args=["--enable-automation"],
                )
                if channel:
                    kw["channel"] = channel
                self.ctx = self.pw.chromium.launch_persistent_context(**kw)
                self.ctx.on("close", lambda *_: setattr(self, "ctx", None))
                self.log(f"Browser gestartet ({channel or 'chromium'})")
                return self.ctx
            except Exception as e:  # noqa: BLE001
                last_err = e
        raise RuntimeError(f"Kein Browser gefunden (Edge/Chrome): {last_err}")

    def _page(self):
        return self.browser().new_page()

    def show(self, page):
        """Holt das Browserfenster auf den Bildschirm."""
        try:
            cdp = page.context.new_cdp_session(page)
            wid = cdp.send("Browser.getWindowForTarget")["windowId"]
            cdp.send("Browser.setWindowBounds", {"windowId": wid, "bounds": {"windowState": "normal"}})
            cdp.send("Browser.setWindowBounds", {"windowId": wid, "bounds": {"left": 80, "top": 40, "width": 1200, "height": 950}})
        except Exception:  # noqa: BLE001
            pass
        try:
            page.bring_to_front()
        except Exception:  # noqa: BLE001
            pass

    def hide(self):
        try:
            for p in list(self.browser().pages):
                cdp = p.context.new_cdp_session(p)
                wid = cdp.send("Browser.getWindowForTarget")["windowId"]
                cdp.send("Browser.setWindowBounds", {"windowId": wid, "bounds": {"left": -32000, "top": -32000}})
                break
        except Exception:  # noqa: BLE001
            pass

    def open_visible(self, url, cart_js=None):
        page = self._page()
        page.goto(url, wait_until="domcontentloaded", timeout=60000)
        self.show(page)
        if cart_js:
            page.wait_for_timeout(5000)
            page.evaluate(cart_js)
            page.wait_for_timeout(9000)
            try:
                res = page.evaluate("window.__foxCart||''")
            except Exception:  # noqa: BLE001
                res = "Seite gewechselt (vermutlich Warenkorb)"
            return res, page.url
        return "", page.url

    def load(self, url, timeout=40):
        """Seite unsichtbar laden, bis Inhalt da ist. Gibt (text, html, url) zurück."""
        page = self._page()
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=timeout * 1000)
            start = time.time()
            text = ""
            while time.time() - start < timeout:
                page.wait_for_timeout(1500)
                try:
                    text = page.evaluate("document.body ? document.body.innerText : ''")
                except Exception:  # noqa: BLE001
                    continue
                if time.time() - start > 5 and looks_ready(text):
                    break
            return text, page.content(), page.url
        finally:
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass

    # ----- Prüfen -----
    def cart_script(self, item, cats=None):
        qty = max(1, min(10, int(item.get("qty", 1) or 1)))
        cats = cats if cats is not None else (item.get("cats") or [])
        return self.cart_js.replace("__QTY__", str(qty)).replace("__CATS__", json.dumps(cats, ensure_ascii=False)).replace(
            "__STRICT__", "true" if (item.get("cats") or item.get("maxPrice")) else "false")

    def _close_cart_page(self):
        """Alten Warenkorb-Tab schließen, damit sich keine Tabs ansammeln."""
        old, self.cart_page = getattr(self, "cart_page", None), None
        if old is not None:
            try:
                old.close()
            except Exception:  # noqa: BLE001
                pass

    def _wait_ready(self, page, timeout=30):
        start, text = time.time(), ""
        while time.time() - start < timeout:
            page.wait_for_timeout(1500)
            try:
                text = page.evaluate("document.body ? document.body.innerText : ''")
            except Exception:  # noqa: BLE001
                continue
            if time.time() - start > 5 and looks_ready(text):
                break
        return text

    def _do_cart(self, cfg, item, page, js, visible=False):
        """Legt auf der bereits geladenen Seite in den Warenkorb. Die Seite bleibt bei Erfolg/Misserfolg offen (sichtbar)."""
        url = item["url"].split("?")[0]
        if visible:
            self.show(page)
        act = page.evaluate(js)
        page.wait_for_timeout(10000)
        try:
            res = page.evaluate("window.__foxCart||''") or act
        except Exception:  # noqa: BLE001
            res = act
        final = page.url
        low = final.lower()
        try:
            low += " " + page.evaluate("document.body.innerText").lower()
        except Exception:  # noqa: BLE001
            pass
        in_cart = any(w in final.lower() for w in ("basket", "checkout", "cart", "warenkorb")) or "zur kasse" in low or "in deinem warenkorb" in low
        log = f"{datetime.now():%d.%m. %H:%M}: {res} → " + ("im Warenkorb ✅" if in_cart else "nicht im Warenkorb ❌")
        cfg["cartLog"] = log + f" ({final})"
        self.log("Warenkorb: " + log)
        self._close_cart_page()
        self.cart_page = page
        self.show(page)
        if in_cart:
            self.notify("🛒 Tickets im Warenkorb! Jetzt bezahlen", item["name"] + "\nDas Edge-Fenster mit dem Warenkorb ist offen – schnell bezahlen!", alarm=True, url=final)
        else:
            self.notify(f"🎟️ Tickets! {item['name']}", "Automatisch in den Warenkorb hat nicht geklappt – das Edge-Fenster ist offen, schnell selbst kaufen!", alarm=True, url=url)
        return {"inCart": in_cart, "log": log}

    def auto_cart(self, cfg, item, visible=False):
        """Warenkorb-Test: Seite laden und sofort einlegen."""
        page = self._page()
        try:
            page.goto(item["url"].split("?")[0], wait_until="domcontentloaded", timeout=60000)
            if visible:
                self.show(page)
            text = self._wait_ready(page)
            _, _, _, eligible = analyze_event(text, item.get("cats"), item.get("maxPrice") or 0)
            return self._do_cart(cfg, item, page, self.cart_script(item, eligible or None), visible=visible)
        except Exception as e:  # noqa: BLE001
            self.log(f"Warenkorb-Fehler: {e}")
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
            return {"inCart": False, "log": f"Fehler: {e}"}

    def check_event(self, cfg, item, allow_cart=True):
        """Prüft den Termin und legt bei Verfügbarkeit im selben Seitenaufruf in den Warenkorb."""
        url = item["url"].split("?")[0]
        r = {"name": item["name"], "url": item["url"], "type": "event", "ok": False, "events": []}
        page = self._page()
        keep = False
        try:
            try:
                page.goto(url, wait_until="domcontentloaded", timeout=40000)
                text = self._wait_ready(page, 40)
                html = page.content()
            except Exception as e:  # noqa: BLE001
                r.update(detail=f"Eventim: {str(e)[:80]}", message="Eventim nicht erreichbar – nächster Versuch später", status="", statusText="")
                return r
            low = text.lower()
            if "access denied" in low or "captcha" in low or "pardon our interruption" in low:
                r.update(detail="Eventim blockiert den Browser", message="Eventim nicht erreichbar", status="", statusText="")
                return r
            st, summary, cats, eligible = analyze_event(text, item.get("cats"), item.get("maxPrice") or 0)
            m = EVENT_LINK.search(url)
            eid = m.group(1).lower() if m else ""
            ev = next((e for e in extract_events(html, eid) if e.get("date") and eid in e["link"]), None) or {"link": url, "title": item["name"]}
            r.update(ok=True, status=st, statusText=STATUS_TEXT.get(st, st), summary=summary, cats=cats, events=[ev],
                     detail=f"Geprüft im Browser ({len(text) // 1024} KB Text)")
            if allow_cart and item.get("autoCart") and buyable(st) and eligible:
                try:
                    r["cart"] = self._do_cart(cfg, item, page, self.cart_script(item, eligible))
                    keep = True
                except Exception as e:  # noqa: BLE001
                    r["cart"] = {"inCart": False, "log": f"Fehler: {e}"}
            return r
        finally:
            if not keep:
                try:
                    page.close()
                except Exception:  # noqa: BLE001
                    pass

    def check_artist(self, item):
        url = item["url"].split("?")[0]
        r = {"name": item["name"], "url": item["url"], "ok": False, "events": []}
        try:
            text, html, _ = self.load(url)
            r.update(ok=True, events=extract_events(html + "\n" + text, slug_of(url)), detail="Geprüft im Browser")
        except Exception as e:  # noqa: BLE001
            r.update(detail=f"Eventim: {str(e)[:80]}", message="Eventim nicht erreichbar – nächster Versuch später")
        return r

    def run(self, cfg, manual=False):
        if any(w.get("autoCart") for w in cfg["watch"]):
            self.ensure_login(cfg, force=False)
        known = cfg.setdefault("known", {})
        results = []
        for i, item in enumerate(cfg["watch"]):
            if is_event(item["url"]):
                r = self.check_event(cfg, item)
                if r["ok"]:
                    key = "event:" + item["url"].split("?")[0]
                    before = known.get(key)
                    now = r["status"]
                    r["changed"] = before is not None and bool(now) and before != now
                    body = r["statusText"] + ("\n" + r["summary"] if r.get("summary") else "")
                    if r.get("cart"):
                        if r["cart"]["inCart"]:
                            # Erfolg: Auto-Warenkorb ausschalten, damit nicht immer neu eingelegt wird
                            item["autoCart"] = False
                            r["autoOff"] = True
                    elif not now:
                        n = known.get("unk:" + key, 0) + 1
                        known["unk:" + key] = n
                        if n == 3:
                            self.notify("⚠️ Fox Ticket erkennt die Seite nicht", item["name"] + "\nDreimal hintereinander kein Ticketstatus gefunden – bitte nachsehen.", url=item["url"])
                    elif now != before and (before is not None or buyable(now)):
                        ev = r["events"][0]
                        self.notify(("🎟️ Tickets! " if buyable(now) else "ℹ️ ") + item["name"],
                                    (fmt_date(ev.get("date")) + " · " if ev.get("date") else "") + body,
                                    alarm=buyable(now), url=item["url"])
                    if now:
                        known[key] = now
                        known["unk:" + key] = 0
            else:
                r = self.check_artist(item)
                if r["ok"]:
                    key = slug_of(item["url"])
                    before = known.get(key, [])
                    fresh = [e for e in r["events"] if e["link"] not in before]
                    r["newLinks"] = [e["link"] for e in fresh]
                    if fresh:
                        lines = [(fmt_date(e.get("date")) + " · " if e.get("date") else "") + (e.get("place") or e["title"]) for e in fresh[:4]]
                        self.notify(f"🎟️ {item['name']}: {len(fresh)} neue{'r Termin' if len(fresh) == 1 else ' Termine'}",
                                    "\n".join(lines), url=item["url"])
                    known[key] = [e["link"] for e in r["events"]]
            results.append(r)
        cfg["last"] = {"time": time.time(), "results": results}
        return cfg["last"]

    # ----- Anmeldung -----
    def ensure_login(self, cfg, force=False):
        last = cfg.get("login") or {}
        if not force and time.time() - last.get("time", 0) < 3600:
            return last
        email, pw = cfg.get("email", ""), decrypt_pw(cfg.get("pw", ""))
        js = self.login_js.replace("__EMAIL__", json.dumps(email)).replace("__PW__", json.dumps(pw))
        state, submitted = "unknown", False
        page = self._page()
        try:
            page.goto(ACCOUNT_URL, wait_until="domcontentloaded", timeout=60000)
            page.wait_for_timeout(5000)
            start = time.time()
            while time.time() - start < 50:
                try:
                    r = page.evaluate(js)
                except Exception:  # noqa: BLE001
                    r = ""
                if r == "submitted":
                    submitted = True
                if r == "in":
                    state = "relogged" if submitted else "in"
                    break
                if r in ("out", "failed"):
                    state = r
                    break
                page.wait_for_timeout(6000 if r == "submitted" else 2500)
            else:
                state = "failed" if submitted else state
        except Exception as e:  # noqa: BLE001
            state = "error"
            self.log(f"Anmeldung: {e}")
        finally:
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
        texts = {
            "in": "Angemeldet ✅", "relogged": "War abgemeldet – automatisch neu angemeldet ✅",
            "out": "Nicht angemeldet ❌", "failed": "Automatische Anmeldung hat nicht geklappt ❌ (Passwort/Sicherheitsabfrage?)",
        }
        info = {"state": state, "time": time.time(), "text": texts.get(state, f"Anmeldestatus unklar ({state})") + f" – {datetime.now():%H:%M} Uhr"}
        if state in ("out", "failed") and last.get("state") != state:
            self.notify("⚠️ Eventim-Anmeldung", info["text"] + "\nBitte in Fox Ticket auf „Selbst anmelden“ klicken.")
        cfg["login"] = info
        self.log("Anmeldung: " + info["text"])
        return info

    def close(self):
        try:
            if self.ctx:
                self.ctx.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            if self.pw:
                self.pw.stop()
        except Exception:  # noqa: BLE001
            pass

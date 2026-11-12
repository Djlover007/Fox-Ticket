#!/usr/bin/env python3
"""Fox Ticket für Windows: überwacht Eventim, meldet freie Tickets und legt sie auf Wunsch in den Warenkorb."""
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.request
import webbrowser
from datetime import datetime
from tkinter import messagebox, ttk

import engine
from engine import STATUS_TEXT, buyable, fmt_date, is_event, load_config, save_config

try:
    from version import BUILD
except Exception:  # noqa: BLE001
    BUILD = 0

try:
    from winotify import Notification, audio
except Exception:  # noqa: BLE001
    Notification = None

APP_NAME = "Fox Ticket"
REPO = "Djlover007/Fox-Ticket"
ICON = engine.resource("fox.ico")
INTERVALS = {"2 Minuten": 2, "5 Minuten": 5, "10 Minuten": 10, "15 Minuten": 15, "30 Minuten": 30}

PURPLE = "#7b2fd1"
BG = "#f3ecfb"


def notify(title, body, alarm=False, url=None):
    if Notification is None:
        return
    try:
        n = Notification(app_id=APP_NAME, title=title, msg=body[:250], icon=ICON, duration="long" if alarm else "short")
        if alarm:
            n.set_audio(audio.LoopingAlarm, loop=True)
        else:
            n.set_audio(audio.Default, loop=False)
        if url:
            n.add_actions(label="Öffnen", launch=url)
        n.show()
    except Exception:  # noqa: BLE001
        pass


class Worker(threading.Thread):
    """Eigener Thread für den Browser (Playwright darf nur aus einem Thread benutzt werden)."""

    def __init__(self, app):
        super().__init__(daemon=True)
        self.app = app
        self.jobs = queue.Queue()
        self.eng = None

    def submit(self, name, *args):
        self.jobs.put((name, args))

    def run(self):
        self.eng = engine.Engine(log=self.app.log, notify=notify)
        while True:
            name, args = self.jobs.get()
            if name == "quit":
                self.eng.close()
                return
            try:
                self.app.busy(True)
                getattr(self, "job_" + name)(*args)
            except Exception as e:  # noqa: BLE001
                self.app.log(f"Fehler bei {name}: {e}")
            finally:
                self.app.busy(False)

    def job_check(self, manual):
        with self.app.cfg_lock:
            cfg = json.loads(json.dumps(self.app.cfg))
        last = self.eng.run(cfg, manual=manual)
        with self.app.cfg_lock:
            # Nur Ergebnisse übernehmen, Einstellungen könnten sich inzwischen geändert haben
            for k in ("known", "last", "login", "cartLog"):
                self.app.cfg[k] = cfg.get(k)
            save_config(self.app.cfg)
        self.app.post(self.app.render)
        self.app.log("Geprüft: " + " | ".join(
            f"{r['name']}: " + (r.get("statusText") or (f"{len(r['events'])} Termine" if r["ok"] else "nicht erreichbar"))
            for r in last["results"]))

    def job_cart(self, index):
        with self.app.cfg_lock:
            cfg = json.loads(json.dumps(self.app.cfg))
        item = cfg["watch"][index]
        res = self.eng.auto_cart(cfg, item, visible=True)
        with self.app.cfg_lock:
            self.app.cfg["cartLog"] = cfg.get("cartLog")
            save_config(self.app.cfg)
        self.app.post(self.app.render)
        self.app.log("Warenkorb-Test: " + res["log"])

    def job_open(self, url):
        self.eng.open_visible(url)

    def job_hide(self):
        self.eng.hide()

    def job_login(self):
        with self.app.cfg_lock:
            cfg = json.loads(json.dumps(self.app.cfg))
        self.eng.ensure_login(cfg, force=True)
        with self.app.cfg_lock:
            self.app.cfg["login"] = cfg["login"]
            save_config(self.app.cfg)
        self.app.post(self.app.render)


class App:
    def __init__(self):
        self.cfg = load_config()
        self.cfg_lock = threading.Lock()
        self.ui = queue.Queue()
        self.root = tk.Tk()
        self.root.title(f"🦊 {APP_NAME}" + (f" – Version 1.{BUILD}" if BUILD else ""))
        self.root.geometry("820x760")
        self.root.configure(bg=BG)
        try:
            self.root.iconbitmap(ICON)
        except Exception:  # noqa: BLE001
            pass
        self._busy = 0
        self.next_check = 0
        self.selected = None
        self.build()
        self.worker = Worker(self)
        self.worker.start()
        self.root.after(200, self.pump)
        self.root.after(1500, lambda: self.check(manual=True))
        self.root.after(1000, self.tick)
        self.root.after(4000, lambda: threading.Thread(target=self.check_update, daemon=True).start())
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    # ---------- Hilfen ----------
    def post(self, fn, *a):
        self.ui.put((fn, a))

    def pump(self):
        try:
            while True:
                fn, a = self.ui.get_nowait()
                fn(*a)
        except queue.Empty:
            pass
        self.root.after(200, self.pump)

    def log(self, msg):
        self.post(self._log, f"{datetime.now():%H:%M:%S}  {msg}")

    def _log(self, line):
        self.logbox.configure(state="normal")
        self.logbox.insert("end", line + "\n")
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def busy(self, on):
        self.post(self._set_busy, on)

    def _set_busy(self, on):
        self._busy += 1 if on else -1
        self.btn_check.configure(text="⏳ Prüfe …" if self._busy > 0 else "🔍 Jetzt prüfen")

    def save(self):
        with self.cfg_lock:
            save_config(self.cfg)

    # ---------- Oberfläche ----------
    def build(self):
        st = ttk.Style()
        try:
            st.theme_use("clam")
        except Exception:  # noqa: BLE001
            pass
        st.configure("TFrame", background=BG)
        st.configure("TLabel", background=BG)
        st.configure("TCheckbutton", background=BG)
        st.configure("Card.TFrame", background="white")
        st.configure("Card.TLabel", background="white")
        st.configure("Card.TCheckbutton", background="white")
        st.configure("Accent.TButton", background=PURPLE, foreground="white")
        st.map("Accent.TButton", background=[("active", "#6424b0")])
        st.configure("Treeview", rowheight=26)

        top = ttk.Frame(self.root, padding=10)
        top.pack(fill="x")
        ttk.Label(top, text="🦊 Fox Ticket", font=("Segoe UI", 18, "bold")).pack(side="left")
        self.lbl_last = ttk.Label(top, text="Noch nicht geprüft", foreground="#6f5d84")
        self.lbl_last.pack(side="left", padx=14)
        self.upd = ttk.Button(top, text="", command=self.install_update)

        # Liste
        mid = ttk.Frame(self.root, padding=(10, 0))
        mid.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(mid, columns=("status", "auto"), show="tree headings", height=6)
        self.tree.heading("#0", text="Künstler / Termin")
        self.tree.heading("status", text="Status")
        self.tree.heading("auto", text="🛒 Auto")
        self.tree.column("#0", width=330)
        self.tree.column("status", width=300)
        self.tree.column("auto", width=80, anchor="center")
        self.tree.pack(fill="x")
        self.tree.bind("<<TreeviewSelect>>", lambda e: self.show_detail())

        self.detail = ttk.Frame(mid, style="Card.TFrame", padding=10)
        self.detail.pack(fill="both", expand=True, pady=8)

        # Hinzufügen
        add = ttk.Frame(self.root, padding=(10, 0))
        add.pack(fill="x")
        ttk.Label(add, text="Eventim-Link (Künstler oder Termin):").pack(side="left")
        self.ent = ttk.Entry(add)
        self.ent.pack(side="left", fill="x", expand=True, padx=6)
        self.ent.bind("<Return>", lambda e: self.add_link())
        ttk.Button(add, text="+ Hinzufügen", command=self.add_link).pack(side="left")

        # Steuerung
        ctl = ttk.Frame(self.root, padding=10)
        ctl.pack(fill="x")
        self.btn_check = ttk.Button(ctl, text="🔍 Jetzt prüfen", style="Accent.TButton", command=lambda: self.check(manual=True))
        self.btn_check.pack(side="left")
        ttk.Label(ctl, text="  Automatisch prüfen alle").pack(side="left")
        self.cmb = ttk.Combobox(ctl, values=list(INTERVALS), width=11, state="readonly")
        cur = next((k for k, v in INTERVALS.items() if v == self.cfg.get("interval", 5)), "5 Minuten")
        self.cmb.set(cur)
        self.cmb.bind("<<ComboboxSelected>>", lambda e: self.set_interval())
        self.cmb.pack(side="left", padx=4)
        self.lbl_next = ttk.Label(ctl, text="", foreground="#6f5d84")
        self.lbl_next.pack(side="left", padx=8)
        self.var_auto = tk.BooleanVar(value=bool(self.cfg.get("autostart")))
        ttk.Checkbutton(ctl, text="Mit Windows starten", variable=self.var_auto, command=self.set_autostart).pack(side="right")

        # Anmeldung
        lg = ttk.LabelFrame(self.root, text=" 🔑 Eventim-Anmeldung (für Auto-Warenkorb) ", padding=8)
        lg.pack(fill="x", padx=10)
        self.e_mail = ttk.Entry(lg, width=28)
        self.e_mail.insert(0, self.cfg.get("email", ""))
        self.e_pw = ttk.Entry(lg, width=22, show="•")
        ttk.Label(lg, text="E-Mail").grid(row=0, column=0, sticky="w")
        self.e_mail.grid(row=0, column=1, padx=4)
        ttk.Label(lg, text="Passwort").grid(row=0, column=2, sticky="w")
        self.e_pw.grid(row=0, column=3, padx=4)
        ttk.Button(lg, text="Speichern & prüfen", command=self.save_login).grid(row=0, column=4, padx=4)
        ttk.Button(lg, text="Selbst anmelden", command=lambda: self.worker.submit("open", engine.ACCOUNT_URL)).grid(row=0, column=5, padx=4)
        self.lbl_login = ttk.Label(lg, text="", foreground="#6f5d84")
        self.lbl_login.grid(row=1, column=0, columnspan=6, sticky="w", pady=(6, 0))
        ttk.Label(lg, text="Passwort wird mit Windows verschlüsselt nur auf diesem PC gespeichert. Stündliche Prüfung, automatisches Neu-Anmelden.",
                  foreground="#6f5d84", font=("Segoe UI", 8)).grid(row=2, column=0, columnspan=6, sticky="w")

        # Unten
        bot = ttk.Frame(self.root, padding=10)
        bot.pack(fill="both")
        bb = ttk.Frame(bot)
        bb.pack(fill="x")
        ttk.Button(bb, text="🔊 Test-Alarm", command=lambda: notify("🎟️ Test-Alarm", "So klingt es, wenn Tickets verfügbar sind", alarm=True)).pack(side="left")
        ttk.Button(bb, text="Edge-Fenster ausblenden", command=lambda: self.worker.submit("hide")).pack(side="left", padx=6)
        self.lbl_cart = ttk.Label(bb, text="", foreground="#6f5d84", wraplength=520)
        self.lbl_cart.pack(side="left", padx=6)
        self.logbox = tk.Text(bot, height=6, state="disabled", font=("Consolas", 9), bg="white", relief="flat")
        self.logbox.pack(fill="both", expand=True, pady=(6, 0))
        self.render()

    def result_for(self, item):
        last = self.cfg.get("last") or {}
        for r in last.get("results", []):
            if r.get("url") == item.get("url"):
                return r
        return None

    def render(self):
        last = self.cfg.get("last") or {}
        if last.get("time"):
            self.lbl_last.configure(text=f"Zuletzt geprüft: {datetime.fromtimestamp(last['time']):%d.%m.%Y, %H:%M} Uhr")
        sel = self.selected
        self.tree.delete(*self.tree.get_children())
        for i, w in enumerate(self.cfg["watch"]):
            r = self.result_for(w)
            if not r:
                st = "Noch nicht geprüft"
            elif not r.get("ok"):
                st = "⚠️ " + (r.get("message") or "Fehler")
            elif r.get("type") == "event":
                st = ("🎟️ " if buyable(r.get("status", "")) else "⏳ ") + r.get("statusText", "")
            else:
                st = f"✅ {len(r['events'])} Termine" if r["events"] else "Keine Termine – passe weiter auf"
            self.tree.insert("", "end", iid=str(i), text=w["name"], values=(st, "✓" if w.get("autoCart") else ""))
        if sel is not None and sel < len(self.cfg["watch"]):
            self.tree.selection_set(str(sel))
        lg = self.cfg.get("login") or {}
        self.lbl_login.configure(text=lg.get("text", "Noch nicht geprüft"))
        self.lbl_cart.configure(text=("Letzter Warenkorb-Versuch: " + self.cfg["cartLog"]) if self.cfg.get("cartLog") else "")
        self.show_detail()

    def show_detail(self):
        for c in self.detail.winfo_children():
            c.destroy()
        s = self.tree.selection()
        if not s:
            ttk.Label(self.detail, text="Wähle oben einen Eintrag aus.", style="Card.TLabel").pack(anchor="w")
            return
        i = int(s[0])
        self.selected = i
        w = self.cfg["watch"][i]
        r = self.result_for(w) or {}
        head = ttk.Frame(self.detail, style="Card.TFrame")
        head.pack(fill="x")
        ttk.Label(head, text=w["name"], style="Card.TLabel", font=("Segoe UI", 12, "bold")).pack(side="left")
        ttk.Button(head, text="✕ Entfernen", command=lambda: self.remove(i)).pack(side="right")
        ttk.Button(head, text="Im Browser öffnen", command=lambda: webbrowser.open(w["url"])).pack(side="right", padx=4)
        if r.get("detail"):
            ttk.Label(self.detail, text=r["detail"], style="Card.TLabel", foreground="#6f5d84").pack(anchor="w")

        if is_event(w["url"]):
            ev = (r.get("events") or [{}])[0]
            if ev.get("date") or ev.get("place"):
                ttk.Label(self.detail, text=" · ".join(x for x in [fmt_date(ev.get("date")), ev.get("place", "")] if x), style="Card.TLabel").pack(anchor="w")
            cats = r.get("cats") or []
            if cats:
                ttk.Label(self.detail, text="Kategorien für den Warenkorb (nichts gewählt = alle):", style="Card.TLabel", foreground="#6f5d84").pack(anchor="w", pady=(6, 0))
                chosen = set(w.get("cats") or [])
                for c in cats:
                    v = tk.BooleanVar(value=c["name"] in chosen)
                    ttk.Checkbutton(self.detail, style="Card.TCheckbutton", variable=v,
                                    text=("🟢 " if c["available"] else "🔴 ") + c["name"] + ("  –  " + c["info"] if c["info"] else ""),
                                    command=lambda n=c["name"], var=v: self.toggle_cat(i, n, var.get())).pack(anchor="w")
            row = ttk.Frame(self.detail, style="Card.TFrame")
            row.pack(fill="x", pady=(8, 0))
            av = tk.BooleanVar(value=bool(w.get("autoCart")))
            ttk.Checkbutton(row, text="🛒 Automatisch in den Warenkorb", style="Card.TCheckbutton", variable=av,
                            command=lambda: self.set_item(i, "autoCart", av.get())).pack(side="left")
            ttk.Label(row, text="   Anzahl:", style="Card.TLabel").pack(side="left")
            qty = ttk.Spinbox(row, from_=1, to=10, width=4, command=lambda: self.set_item(i, "qty", int(qty.get())))
            qty.set(w.get("qty", 1))
            qty.pack(side="left", padx=4)
            ttk.Button(row, text="🧪 Warenkorb-Test jetzt", command=lambda: self.worker.submit("cart", i)).pack(side="left", padx=10)
            if r.get("cart"):
                ttk.Label(self.detail, text="🛒 " + r["cart"].get("log", ""), style="Card.TLabel", foreground="#6f5d84", wraplength=740).pack(anchor="w", pady=(6, 0))
        else:
            evs = r.get("events") or []
            watched = {x["url"] for x in self.cfg["watch"]}
            for e in evs:
                row = ttk.Frame(self.detail, style="Card.TFrame")
                row.pack(fill="x", pady=1)
                txt = " · ".join(x for x in [fmt_date(e.get("date")), e.get("place") or e.get("title", "")] if x)
                if e["link"] in (r.get("newLinks") or []):
                    txt = "🆕 " + txt
                ttk.Label(row, text=txt, style="Card.TLabel").pack(side="left")
                if e["link"] not in watched:
                    ttk.Button(row, text="+ Überwachen", command=lambda e=e: self.add_item(e.get("place") or e["title"], e["link"])).pack(side="right")
            if not evs:
                ttk.Label(self.detail, text="Noch keine Termine gefunden.", style="Card.TLabel").pack(anchor="w")

    # ---------- Aktionen ----------
    def set_item(self, i, key, val):
        with self.cfg_lock:
            self.cfg["watch"][i][key] = val
            save_config(self.cfg)
        self.render()

    def toggle_cat(self, i, name, on):
        with self.cfg_lock:
            cats = [c for c in (self.cfg["watch"][i].get("cats") or []) if c != name]
            if on:
                cats.append(name)
            self.cfg["watch"][i]["cats"] = cats
            save_config(self.cfg)

    def remove(self, i):
        with self.cfg_lock:
            self.cfg["watch"].pop(i)
            save_config(self.cfg)
        self.selected = None
        self.render()

    def add_item(self, name, url):
        with self.cfg_lock:
            if any(w["url"].split("?")[0] == url for w in self.cfg["watch"]):
                return
            self.cfg["watch"].append({"name": name, "url": url})
            save_config(self.cfg)
        self.render()
        self.check(manual=True)

    def add_link(self):
        import re
        v = self.ent.get().strip()
        m = re.search(r"eventim\.[a-z.]+/(artist|event)/([a-z0-9-]+)", v, re.I)
        if not m:
            messagebox.showinfo(APP_NAME, "Bitte einen Eventim-Link einfügen (…/artist/… oder …/event/…)")
            return
        kind, slug = m.group(1).lower(), m.group(2).lower()
        name = " ".join(x[:1].upper() + x[1:] for x in re.sub(r"-\d+$", "", slug).split("-"))
        self.ent.delete(0, "end")
        self.add_item(name, f"https://www.eventim.de/{kind}/{slug}/")

    def check(self, manual=False):
        self.next_check = time.time() + self.cfg.get("interval", 5) * 60
        if self.worker.jobs.qsize() < 2:
            self.worker.submit("check", manual)

    def tick(self):
        left = int(self.next_check - time.time())
        if self.next_check and left <= 0:
            self.check(manual=False)
        elif self.next_check:
            self.lbl_next.configure(text=f"nächste Prüfung in {left // 60}:{left % 60:02d}")
        self.root.after(1000, self.tick)

    def set_interval(self):
        with self.cfg_lock:
            self.cfg["interval"] = INTERVALS[self.cmb.get()]
            save_config(self.cfg)
        self.next_check = time.time() + self.cfg["interval"] * 60

    def save_login(self):
        em, pw = self.e_mail.get().strip(), self.e_pw.get()
        with self.cfg_lock:
            self.cfg["email"] = em
            if pw:
                self.cfg["pw"] = engine.encrypt_pw(pw)
            save_config(self.cfg)
        self.e_pw.delete(0, "end")
        self.lbl_login.configure(text="⏳ Prüfe Anmeldung … (bis zu 1 Minute)")
        self.worker.submit("login")

    def set_autostart(self):
        on = self.var_auto.get()
        try:
            import winreg
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE)
            if on:
                winreg.SetValueEx(key, "FoxTicket", 0, winreg.REG_SZ, f'"{sys.executable}"')
            else:
                try:
                    winreg.DeleteValue(key, "FoxTicket")
                except FileNotFoundError:
                    pass
            winreg.CloseKey(key)
        except Exception as e:  # noqa: BLE001
            self.log(f"Autostart: {e}")
        with self.cfg_lock:
            self.cfg["autostart"] = on
            save_config(self.cfg)

    # ---------- Update ----------
    def check_update(self):
        if not BUILD:
            return
        try:
            req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases?per_page=30", headers={"User-Agent": "FoxTicket"})
            rels = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())
            best = None
            for r in rels:
                tag = r.get("tag_name", "")
                if not tag.startswith("win-v"):
                    continue
                n = int(tag[5:]) if tag[5:].isdigit() else 0
                asset = next((a for a in r.get("assets", []) if a["name"].lower().endswith(".exe")), None)
                if asset and n > BUILD and (best is None or n > best[0]):
                    best = (n, asset["browser_download_url"])
            if best:
                self.update_url = best[1]
                self.post(lambda: (self.upd.configure(text=f"⬆️ Version 1.{best[0]} installieren"), self.upd.pack(side="right")))
                self.log(f"Neue Version 1.{best[0]} verfügbar")
        except Exception as e:  # noqa: BLE001
            self.log(f"Update-Prüfung: {e}")

    def install_update(self):
        if not getattr(sys, "frozen", False):
            webbrowser.open(self.update_url)
            return
        self.upd.configure(text="⏳ Lade Update …")

        def go():
            try:
                exe = sys.executable
                new = exe + ".new"
                urllib.request.urlretrieve(self.update_url, new)
                bat = os.path.join(tempfile.gettempdir(), "foxticket_update.bat")
                with open(bat, "w", encoding="utf-8") as f:
                    f.write(f'@echo off\r\nping 127.0.0.1 -n 3 >nul\r\n:loop\r\nmove /y "{new}" "{exe}" >nul 2>&1 || (ping 127.0.0.1 -n 2 >nul & goto loop)\r\nstart "" "{exe}"\r\ndel "%~f0"\r\n')
                subprocess.Popen(["cmd", "/c", bat], creationflags=0x08000000)
                self.post(self.on_close)
            except Exception as e:  # noqa: BLE001
                self.log(f"Update fehlgeschlagen: {e}")

        threading.Thread(target=go, daemon=True).start()

    def on_close(self):
        self.worker.submit("quit")
        self.root.after(1500, self.root.destroy)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().run()

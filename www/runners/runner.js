// Fox Ticket – Hintergrund-Prüfer
// Läuft ohne geöffnete App (Android WorkManager) und prüft Eventim-Künstlerseiten.

var DEFAULT_WATCH = [
  { name: "Fall Out Boy", url: "https://www.eventim.de/artist/fall-out-boy/" }
];

var UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

function kvGet(key, fallback) {
  try {
    var r = CapacitorKV.get(key);
    var v = r && typeof r === "object" ? r.value : r;
    if (v === undefined || v === null || v === "") return fallback;
    return JSON.parse(v);
  } catch (e) {
    return fallback;
  }
}

function kvSet(key, value) {
  CapacitorKV.set(key, JSON.stringify(value));
}

function getWatch() {
  var w = kvGet("watch", null);
  return Array.isArray(w) ? w : DEFAULT_WATCH;
}

// "https://www.eventim.de/artist/fall-out-boy/?affiliate=FEU" -> "fall-out-boy"
function slugOf(url) {
  var m = String(url).match(/\/artist\/([a-z0-9-]+)/i);
  return m ? m[1].toLowerCase() : "";
}

function cleanUrl(url) {
  return String(url).split("?")[0].split("#")[0];
}

// "fall-out-boy-so-much-tour-uber-arena-17661437" -> "Fall Out Boy So Much Tour Uber Arena"
function titleFromLink(link) {
  var m = link.match(/\/event\/([a-z0-9-]+?)-(\d+)\/?$/i);
  var s = m ? m[1] : link;
  return s.split("-").map(function (w) {
    return w ? w.charAt(0).toUpperCase() + w.slice(1) : w;
  }).join(" ");
}

function looksBlocked(status, html) {
  if (status === 403 || status === 429 || status === 503) return true;
  var h = html.slice(0, 20000).toLowerCase();
  return h.indexOf("captcha") >= 0 || h.indexOf("access denied") >= 0 ||
    h.indexOf("pardon our interruption") >= 0 || h.indexOf("request unsuccessful") >= 0;
}

// Sucht alle Eventim-Event-Links (funktioniert für HTML und JSON gleichermaßen)
function extractEvents(text, slug) {
  var found = {};
  var re = /(?:https?:)?(?:\\?\/\\?\/www\.eventim\.de)?\\?\/event\\?\/([a-z0-9-]+-\d{5,})\\?\/?/gi;
  var m;
  while ((m = re.exec(text)) !== null) {
    var id = m[1].toLowerCase();
    if (slug && id.indexOf(slug) < 0) continue; // nur Events dieses Künstlers
    var link = "https://www.eventim.de/event/" + id + "/";
    found[link] = { link: link, title: titleFromLink(link) };
  }
  // Zusätzliche Details aus JSON-LD (Datum, Ort, Verfügbarkeit), falls vorhanden
  var ld = /<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi;
  while ((m = ld.exec(text)) !== null) {
    var data;
    try { data = JSON.parse(m[1]); } catch (e) { continue; }
    var list = [];
    (function walk(x) {
      if (!x || typeof x !== "object") return;
      if (Array.isArray(x)) { x.forEach(walk); return; }
      if (x.startDate && x.url) list.push(x);
      Object.keys(x).forEach(function (k) { if (typeof x[k] === "object") walk(x[k]); });
    })(data);
    list.forEach(function (ev) {
      var mm = String(ev.url).match(/\/event\/([a-z0-9-]+-\d{5,})/i);
      if (!mm) return;
      var id = mm[1].toLowerCase();
      if (slug && id.indexOf(slug) < 0) return;
      var link = "https://www.eventim.de/event/" + id + "/";
      var loc = ev.location || {};
      var addr = loc.address || {};
      var offers = Array.isArray(ev.offers) ? ev.offers[0] : (ev.offers || {});
      found[link] = {
        link: link,
        title: ev.name || titleFromLink(link),
        date: ev.startDate,
        place: [loc.name, addr.addressLocality].filter(Boolean).join(", "),
        availability: offers && offers.availability ? String(offers.availability).replace(/^.*\//, "") : ""
      };
    });
  }
  return Object.keys(found).map(function (k) { return found[k]; });
}

async function fetchText(url) {
  var res = await fetch(url, {
    method: "GET",
    headers: {
      "User-Agent": UA,
      "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
      "Accept-Language": "de-DE,de;q=0.9"
    }
  });
  var text = "";
  try { text = await res.text(); } catch (e) { text = ""; }
  return { status: res.status, text: text || "" };
}

async function checkArtist(item) {
  var slug = slugOf(item.url);
  var result = { name: item.name, url: item.url, ok: false, blocked: false, events: [], message: "" };
  try {
    var page = await fetchText(cleanUrl(item.url));
    if (looksBlocked(page.status, page.text)) {
      result.blocked = true;
    } else if (page.status >= 200 && page.status < 400) {
      result.ok = true;
      result.events = extractEvents(page.text, slug);
    }
  } catch (e) {
    result.message = "Netzwerkfehler";
  }
  // Ersatzweg: Eventim-Suche, falls die Künstlerseite blockiert war
  if (!result.ok && slug) {
    try {
      var term = encodeURIComponent(item.name || slug.replace(/-/g, " "));
      var api = await fetchText("https://public-api.eventim.com/websearch/search/api/exploration/v2/productGroups?webId=web__eventim-de&language=de&search_term=" + term);
      if (api.status >= 200 && api.status < 300 && !looksBlocked(api.status, api.text)) {
        result.ok = true;
        result.blocked = false;
        result.events = extractEvents(api.text, slug);
      }
    } catch (e) { /* ignorieren */ }
  }
  if (!result.ok && !result.message) {
    result.message = result.blocked ? "Eventim hat die Abfrage blockiert – nächster Versuch später" : "Seite nicht erreichbar";
  }
  return result;
}

async function runCheck(notify) {
  var watch = getWatch();
  var known = kvGet("known", {});
  var results = [];
  var newCount = 0;
  for (var i = 0; i < watch.length; i++) {
    var r = await checkArtist(watch[i]);
    if (r.ok) {
      var key = slugOf(r.url) || r.url;
      var before = known[key] || [];
      var fresh = r.events.filter(function (e) { return before.indexOf(e.link) < 0; });
      r.newLinks = fresh.map(function (e) { return e.link; });
      if (fresh.length && notify) {
        newCount += fresh.length;
        var lines = fresh.slice(0, 4).map(function (e) {
          return (e.date ? String(e.date).slice(0, 10).split("-").reverse().join(".") + " · " : "") + (e.place || e.title);
        });
        CapacitorNotifications.schedule([{
          id: 1000 + i,
          title: "🎟️ " + r.name + ": " + fresh.length + (fresh.length === 1 ? " neuer Termin" : " neue Termine"),
          body: lines.join("\n") + (fresh.length > 4 ? "\n…" : ""),
          scheduleAt: new Date(Date.now() + 1000)
        }]);
      }
      known[key] = r.events.map(function (e) { return e.link; });
    }
    results.push(r);
  }
  kvSet("known", known);
  var state = { time: Date.now(), results: results, newCount: newCount };
  kvSet("last", state);
  return state;
}

// Wird regelmäßig vom System aufgerufen
addEventListener("checkTickets", async function (resolve, reject, args) {
  try {
    await runCheck(true);
    resolve();
  } catch (e) {
    reject(e);
  }
});

// Von der App aus: jetzt prüfen
addEventListener("checkNow", async function (resolve, reject, args) {
  try {
    resolve(await runCheck(true));
  } catch (e) {
    reject(e);
  }
});

addEventListener("getState", function (resolve, reject, args) {
  try {
    resolve({ watch: getWatch(), last: kvGet("last", null) });
  } catch (e) {
    reject(e);
  }
});

addEventListener("setWatch", function (resolve, reject, args) {
  try {
    var list = (args && args.watch) || [];
    kvSet("watch", list);
    resolve({ watch: list });
  } catch (e) {
    reject(e);
  }
});

addEventListener("testNotification", function (resolve, reject, args) {
  try {
    CapacitorNotifications.schedule([{
      id: 999,
      title: "🦊 Fox Ticket",
      body: "Benachrichtigungen funktionieren!",
      scheduleAt: new Date(Date.now() + 1000)
    }]);
    resolve();
  } catch (e) {
    reject(e);
  }
});

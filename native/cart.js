(function (qty, cats, strict) {
  function t(e) { return ((e.innerText || e.textContent || "") + " " + (e.getAttribute("aria-label") || "") + " " + (e.getAttribute("title") || "")).replace(/\s+/g, " ").trim().toLowerCase(); }
  function usable(b) { return !b.disabled && b.getAttribute("aria-disabled") !== "true"; }
  function buttonsIn(root) { return [].slice.call(root.querySelectorAll("button,[role=button],a,input[type=submit]")).filter(usable); }
  function txt(e) { return (e.textContent || e.value || "").replace(/\s+/g, " ").trim(); }
  // Mengen-Anzeige: Zahl, die zwischen zwei Knöpfen (− und +) steht
  function counterIn(root) {
    var cands = [].slice.call(root.querySelectorAll("*")).filter(function (e) {
      return (e.children.length === 0 && /^\d{1,2}$/.test(txt(e))) || (e.tagName === "INPUT" && /^\d{0,2}$/.test(e.value || ""));
    });
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      for (var p = c.parentElement, d = 0; p && d < 3; p = p.parentElement, d++) {
        var btns = [].slice.call(p.querySelectorAll("button,[role=button]"));
        var before = btns.filter(function (b) { return c.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_PRECEDING; });
        var after = btns.filter(function (b) { return c.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING; });
        if (before.length && after.length) return { el: c, plus: after[0], minus: before[before.length - 1] };
      }
    }
    return null;
  }
  function plusIn(root) {
    var c = counterIn(root);
    if (c && usable(c.plus)) return c.plus;
    return buttonsIn(root).filter(isPlus)[0] || null;
  }
  function isPlus(b) { var s = t(b); return s === "+" || /(erhöhen|mehr tickets|increase|plus|hinzufügen)/.test(s) && s.length < 40; }
  // Warenkorb-Knopf: "In den Warenkorb" oder Eventim-Stil "1 Ticket, € 122,90"
  // Nur der Kauf-Knopf ("1 Ticket, € 122,90" / "In den Warenkorb"), NICHT der Warenkorb-Link oben im Kopf
  function isTicketBtn(b) { return /\d+\s*tickets?\s*,?\s*(€|eur)?\S{0,3}\s*\d+[.,]\d{2}/.test(t(b)); }
  function isCart(b) { var s = t(b); return isTicketBtn(b) || /in den warenkorb|warenkorb legen/.test(s); }
  var log = [];
  window.__foxCart = "läuft";

  buttonsIn(document).forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen|alle cookies/.test(t(b))) { b.click(); log.push("Cookies"); } });

  // Bereich der gewünschten Kategorie finden (Karte, die genau eine Kategorie enthält)
  function norm(x) { return String(x).toLowerCase().replace(/[^a-z0-9äöüß]+/g, " ").trim(); }
  function katCount(el) { return ((el.innerText || "").toLowerCase().match(/kategorie/g) || []).length; }
  // Alle Kategorie-Kästen der Seite: von jedem Mengen-Zähler (− Zahl +) nach oben bis zur Karte mit genau einer Kategorie
  function allBoxes() {
    var out = [];
    var nums = [].slice.call(document.querySelectorAll("body *")).filter(function (e) {
      return (e.children.length === 0 && /^\d{1,2}$/.test(txt(e))) || (e.tagName === "INPUT" && /^\d{0,2}$/.test(e.value || "") && e.type !== "hidden");
    });
    nums.forEach(function (num) {
      var small = null;
      for (var p = num.parentElement, d = 0; p && d < 4 && !small; p = p.parentElement, d++) { if (counterIn(p)) small = p; }
      if (!small) return;
      var box = small;
      for (var el = small, d2 = 0; el && d2 < 12; el = el.parentElement, d2++) {
        var k = katCount(el);
        if (k > 1) break;
        box = el;
        if (k === 1 && el.parentElement && katCount(el.parentElement) > 1) break;
      }
      if (out.indexOf(box) < 0) out.push(box);
    });
    return out;
  }
  var boxes = allBoxes();
  function categoryBox(name) {
    var n = norm(name);
    var head = norm(name.split(" - ")[0]);
    var hit = boxes.filter(function (b) { return norm(b.innerText || "").indexOf(n) >= 0 && plusIn(b); })[0]
      || boxes.filter(function (b) { var s = norm(b.innerText || ""); return (s.indexOf(head + " ") === 0 || s === head) && plusIn(b); })[0];
    return hit || null;
  }

  var box = null;
  if (cats.length) {
    for (var i = 0; i < cats.length && !box; i++) {
      var bx = categoryBox(cats[i]);
      if (bx) { box = bx; log.push("Kategorie: " + cats[i]); }
    }
    if (!box && !strict) {
      // Keine bestimmte Kategorie verlangt: erste kaufbare nehmen
      box = boxes.filter(function (b) { return plusIn(b); })[0] || null;
      if (box) log.push("erste kaufbare Kategorie");
    }
    if (!box) {
      var diag = "gewählte Kategorie nicht gefunden (" + boxes.length + " Kästen: " + boxes.slice(0, 4).map(function (b) { return norm(b.innerText || "").slice(0, 30); }).join(" / ") + ")";
      window.__foxCart = diag;
      return log.concat(diag).join(", ");
    }
  } else {
    var firstPlus = buttonsIn(document).filter(isPlus)[0];
    if (!firstPlus) {
      var nums = [].slice.call(document.querySelectorAll("body *")).filter(function (e) { return e.children.length === 0 && /^\d{1,2}$/.test((e.innerText || "").trim()); });
      for (var k = 0; k < nums.length && !firstPlus; k++) {
        for (var p = nums[k].parentElement, dd = 0; p && dd < 4 && !firstPlus; p = p.parentElement, dd++) firstPlus = plusIn(p);
      }
    }
    if (firstPlus) {
      for (var el = firstPlus, d = 0; el && d < 12; el = el.parentElement, d++) {
        if (((el.innerText || "").toLowerCase().match(/kategorie/g) || []).length > 1) break;
        box = el;
      }
    }
    if (!box) { window.__foxCart = "kein Plus-Knopf gefunden"; return log.concat("kein Plus-Knopf").join(", "); }
  }

  // Aktuelle Menge lesen (Zahl zwischen − und +) und bis qty erhöhen – mit Pausen
  function current() {
    var c = counterIn(box);
    if (!c) return clicks;
    var v = parseInt(c.el.tagName === "INPUT" ? c.el.value : txt(c.el), 10);
    return isNaN(v) ? clicks : v;
  }
  var clicks = 0;
  function step() {
    var plus = plusIn(box);
    if (plus && current() < qty && clicks < qty + 3) {
      plus.click(); clicks++;
      setTimeout(step, 700);
      return;
    }
    log.push("Menge " + current());
    var tries = 0;
    (function findCart() {
      var want = new RegExp("(^|\\D)" + current() + "\\s*tickets?\\b");
      var list = buttonsIn(box).filter(isCart).concat(buttonsIn(document).filter(isTicketBtn));
      var btn = list.filter(function (b) { return want.test(t(b)); })[0] || (tries >= 10 ? list[0] : null);
      if (btn) { btn.click(); window.__foxCart = log.join(", ") + ", geklickt: " + t(btn).slice(0, 40); return; }
      if (++tries < 16) { setTimeout(findCart, 500); return; }
      window.__foxCart = log.join(", ") + ", kein Kauf-Knopf erschienen";
    })();
  }
  step();
  return log.join(", ") + ", starte";
})(__QTY__, __CATS__, __STRICT__)

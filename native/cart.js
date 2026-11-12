(function (qty, cats) {
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
  function isTicketBtn(b) { return /\d+\s*tickets?\s*,?\s*€\s*[\d.,]+/.test(t(b)); }
  function isCart(b) { var s = t(b); return isTicketBtn(b) || /in den warenkorb|warenkorb legen/.test(s); }
  var log = [];
  window.__foxCart = "läuft";

  buttonsIn(document).forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen|alle cookies/.test(t(b))) { b.click(); log.push("Cookies"); } });

  // Bereich der gewünschten Kategorie finden (Karte, die genau eine Kategorie enthält)
  function categoryBox(name) {
    var n = name.toLowerCase().replace(/\s+/g, " ");
    var head = n.split(" - ")[0];
    var all = [].slice.call(document.querySelectorAll("body *"));
    var label = all.filter(function (e) {
      var s = (e.innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
      return e.children.length < 6 && s.length < 120 && (s.indexOf(n) === 0 || s.indexOf(head + " ") === 0 || s === head);
    }).pop();
    var box = null;
    for (var el = label, d = 0; el && d < 12; el = el.parentElement, d++) {
      if (((el.innerText || "").toLowerCase().match(/kategorie/g) || []).length > 1) break;
      if (plusIn(el)) box = el;
    }
    return box;
  }

  var box = null;
  if (cats.length) {
    for (var i = 0; i < cats.length && !box; i++) {
      var bx = categoryBox(cats[i]);
      if (bx && plusIn(bx)) { box = bx; log.push("Kategorie: " + cats[i]); }
    }
    if (!box) { window.__foxCart = "gewählte Kategorie nicht kaufbar"; return log.concat("gewählte Kategorie nicht kaufbar").join(", "); }
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
})(__QTY__, __CATS__)

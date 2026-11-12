(function (qty, cats) {
  function t(e) { return ((e.innerText || e.textContent || "") + " " + (e.getAttribute("aria-label") || "") + " " + (e.getAttribute("title") || "")).replace(/\s+/g, " ").trim().toLowerCase(); }
  function usable(b) { return !b.disabled && b.getAttribute("aria-disabled") !== "true"; }
  function buttonsIn(root) { return [].slice.call(root.querySelectorAll("button,[role=button],a,input[type=submit]")).filter(usable); }
  // Plus = Knopf direkt nach der Mengen-Zahl (auch wenn er nur ein Symbol ist)
  function plusIn(root) {
    var named = buttonsIn(root).filter(isPlus)[0];
    if (named) return named;
    var all = [].slice.call(root.querySelectorAll("*"));
    var num = all.filter(function (e) { return e.children.length === 0 && /^\d{1,2}$/.test((e.innerText || e.textContent || "").trim()); })[0]
      || root.querySelector("input[type=number],input[inputmode=numeric]");
    if (!num) return null;
    var btns = [].slice.call(root.querySelectorAll("button,[role=button]"));
    for (var i = 0; i < btns.length; i++) {
      if (num.compareDocumentPosition(btns[i]) & Node.DOCUMENT_POSITION_FOLLOWING) return usable(btns[i]) ? btns[i] : null;
    }
    return null;
  }
  function isPlus(b) { var s = t(b); return s === "+" || /(erhöhen|mehr tickets|increase|plus|hinzufügen)/.test(s) && s.length < 40; }
  // Warenkorb-Knopf: "In den Warenkorb" oder Eventim-Stil "1 Ticket, € 122,90"
  function isCart(b) { var s = t(b); return /in den warenkorb|warenkorb legen|zum warenkorb/.test(s) || /\d+\s*tickets?\s*,?\s*€\s*[\d.,]+/.test(s) || /tickets? sichern|jetzt buchen/.test(s); }
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
    var nums = [].slice.call(box.querySelectorAll("*")).filter(function (e) { return e.children.length === 0 && /^\d{1,2}$/.test((e.innerText || e.value || "").trim()); });
    var inp = box.querySelector("input[type=number],input[inputmode=numeric]");
    if (inp) return parseInt(inp.value || "0", 10);
    return nums.length ? parseInt(nums[0].innerText.trim(), 10) : 0;
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
    setTimeout(function () {
      var btn = buttonsIn(box).filter(isCart)[0] || buttonsIn(document).filter(isCart)[0];
      if (btn) { btn.click(); window.__foxCart = log.join(", ") + ", geklickt: " + t(btn).slice(0, 40); }
      else { window.__foxCart = log.join(", ") + ", kein Warenkorb-Knopf"; }
    }, 1200);
  }
  step();
  return log.join(", ") + ", starte";
})(__QTY__, __CATS__)

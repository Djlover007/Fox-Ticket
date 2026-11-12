(function (qty, cats) {
  function t(e) { return ((e.innerText || e.textContent || "") + " " + (e.getAttribute("aria-label") || "") + " " + (e.getAttribute("title") || "")).trim().toLowerCase(); }
  function clickable() { return [].slice.call(document.querySelectorAll("button,[role=button],a,input[type=submit]")).filter(function (b) { return !b.disabled && b.getAttribute("aria-disabled") !== "true" && b.offsetParent !== null; }); }
  var log = [];
  // Cookie-Banner wegklicken
  clickable().forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen|alle cookies/.test(t(b))) { b.click(); log.push("cookies"); } });
  function isPlus(b) { var s = t(b); return s === "+" || /(erhöhen|mehr tickets|increase|anzahl erhöhen|plus)/.test(s); }
  // Menge erhöhen: "+"-Knopf der ersten verfügbaren (gewählten) Kategorie
  var plus = [];
  if (cats.length) {
    var all = [].slice.call(document.querySelectorAll("body *"));
    cats.forEach(function (name) {
      if (plus.length) return;
      var n = name.toLowerCase();
      var label = all.filter(function (e) { return e.children.length < 4 && (e.innerText || "").trim().toLowerCase().indexOf(n) === 0; }).pop();
      for (var el = label, d = 0; el && d < 10 && !plus.length; el = el.parentElement, d++) {
        if (((el.innerText || "").toLowerCase().match(/kategorie/g) || []).length > 1) break; // nicht in andere Kategorien rutschen
        plus = [].slice.call(el.querySelectorAll("button,[role=button]")).filter(function (b) { return !b.disabled && b.getAttribute("aria-disabled") !== "true" && isPlus(b); });
      }
      if (plus.length) log.push("Kategorie: " + name);
    });
    if (!plus.length) log.push("gewählte Kategorie nicht kaufbar");
  } else {
    plus = clickable().filter(isPlus);
  }
  if (cats.length && !plus.length) {
    window.__foxCart = "abgebrochen";
    return log.join(", ");
  }
  if (plus.length) {
    for (var i = 0; i < qty; i++) plus[0].click();
    log.push("plus x" + qty);
  } else {
    var sel = [].slice.call(document.querySelectorAll("select")).filter(function (s) { return s.offsetParent !== null; })[0];
    if (sel) {
      var opt = [].slice.call(sel.options).filter(function (o) { return parseInt(o.value || o.text, 10) === qty; })[0] || sel.options[Math.min(qty, sel.options.length - 1)];
      sel.value = opt.value;
      sel.dispatchEvent(new Event("input", { bubbles: true }));
      sel.dispatchEvent(new Event("change", { bubbles: true }));
      log.push("select " + opt.value);
    } else {
      log.push("keine Mengenauswahl");
    }
  }
  // Danach "In den Warenkorb" klicken
  setTimeout(function () {
    var btn = clickable().filter(function (b) { return /in den warenkorb|zum warenkorb hinzufügen|warenkorb legen/.test(t(b)); })[0]
      || clickable().filter(function (b) { return /tickets sichern|jetzt buchen|weiter zur buchung/.test(t(b)); })[0];
    if (btn) { btn.click(); window.__foxCart = "geklickt: " + t(btn).slice(0, 40); }
    else { window.__foxCart = "kein Warenkorb-Knopf"; }
  }, 2000);
  return log.join(", ");
})(__QTY__, __CATS__)

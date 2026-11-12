(function (qty) {
  function t(e) { return ((e.innerText || e.textContent || "") + " " + (e.getAttribute("aria-label") || "") + " " + (e.getAttribute("title") || "")).trim().toLowerCase(); }
  function clickable() { return [].slice.call(document.querySelectorAll("button,[role=button],a,input[type=submit]")).filter(function (b) { return !b.disabled && b.getAttribute("aria-disabled") !== "true" && b.offsetParent !== null; }); }
  var log = [];
  // Cookie-Banner wegklicken
  clickable().forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen|alle cookies/.test(t(b))) { b.click(); log.push("cookies"); } });
  // Menge erhöhen: "+"-Knopf der ersten verfügbaren Kategorie
  var plus = clickable().filter(function (b) { var s = t(b); return s === "+" || /(erhöhen|mehr tickets|increase|anzahl erhöhen|plus)/.test(s); });
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
})(__QTY__)

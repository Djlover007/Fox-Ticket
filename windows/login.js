(function (email, pw) {
  function setVal(el, v) {
    var proto = Object.getPrototypeOf(el);
    var d = Object.getOwnPropertyDescriptor(proto, 'value') || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    d.set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur', { bubbles: true }));
  }
  var q = function (s) { return [].slice.call(document.querySelectorAll(s)).filter(function (e) { return !e.disabled && e.type !== 'hidden'; }); };
  [].slice.call(document.querySelectorAll('button')).forEach(function (b) { if (/alle akzeptieren|akzeptieren und schließen/i.test(b.innerText || '')) b.click(); });
  var pwd = q('input[type=password]')[0];
  var mail = q('input[type=email],input[autocomplete=username],input[autocomplete=email],input[name*=mail i],input[id*=mail i],input[name=username],input[id=username]')[0];
  var href = location.href.toLowerCase();
  var txt = (document.body ? document.body.innerText : '').toLowerCase();
  if (!pwd && !mail) {
    if (/abmelden|ausloggen|logout|meine bestellungen|persönliche daten|meine daten/.test(txt)) return 'in';
    var lb = q('button,a').filter(function (b) { return /^(anmelden|einloggen|login)$/i.test((b.innerText || '').trim()); })[0];
    if (lb && !window.__foxOpened) { window.__foxOpened = 1; lb.click(); return 'opening'; }
    return 'unknown';
  }
  if (!email || !pw) return 'out';
  if (window.__foxLoginTries > 2) return 'failed';
  window.__foxLoginTries = (window.__foxLoginTries || 0) + 1;
  if (mail && !mail.value) setVal(mail, email);
  if (pwd) setVal(pwd, pw);
  var form = (pwd || mail).form;
  var btn = (form ? [].slice.call(form.querySelectorAll('button,input[type=submit]')) : q('button,input[type=submit]')).filter(function (b) {
    return b.type === 'submit' || /anmelden|einloggen|login|weiter|fortfahren/i.test(b.innerText || b.value || '');
  })[0];
  setTimeout(function () { if (btn) btn.click(); else if (form) form.requestSubmit ? form.requestSubmit() : form.submit(); }, 600);
  return 'submitted';
})(__EMAIL__, __PW__)
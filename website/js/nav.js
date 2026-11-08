// The header's plain-script half (loaded in <head> on every page).
// Page transitions: the morphing itself is pure CSS (@view-transition + view-transition-name
// in css/site.css). This only covers two edges of it:
//  - browsers without cross-document view transitions get .no-vt, which gives
//    the page a quick fade-in instead;
//  - the home header slides away while scrolling down. Leaving the page, it is
//    put back first so the transition has a header to morph.
// The phone menu: below 900 px the header's links fold into a sheet under the bar
// ([data-menu] toggles #site-nav). Esc or a link closes it; focus goes in and back out.
(function () {
  var root = document.documentElement;
  if (!('onpageswap' in window)) root.classList.add('no-vt');
  addEventListener('pageswap', function () {
    var top = document.querySelector('[data-top]');
    if (!top) return;
    top.style.transition = 'none';
    top.classList.remove('is-hidden');
  });

  function initMenu() {
    var btn = document.querySelector('[data-menu]'), nav = document.getElementById('site-nav');
    if (!btn || !nav) return;
    var label = btn.querySelector('[data-menu-label]');
    function set(open) {
      btn.setAttribute('aria-expanded', String(open));
      if (label) label.textContent = open ? 'Close' : 'Menu';
      nav.classList.toggle('is-open', open);
      document.body.classList.toggle('menu-open', open);
      if (open) { var first = nav.querySelector('a'); if (first) first.focus(); }
    }
    btn.addEventListener('click', function () { set(btn.getAttribute('aria-expanded') !== 'true'); });
    nav.addEventListener('click', function (e) { if (e.target.closest('a')) set(false); });
    addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && nav.classList.contains('is-open')) { set(false); btn.focus(); }
    });
    // Growing back past the phone layout closes it (the links are inline there).
    matchMedia('(min-width: 901px)').addEventListener('change', function (e) { if (e.matches) set(false); });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initMenu);
  else initMenu();
})();

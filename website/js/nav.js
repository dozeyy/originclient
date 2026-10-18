// Page transitions, the plain-script half (loaded in <head> on every page).
// The morphing itself is pure CSS (@view-transition + view-transition-name in
// css/site.css). This only covers two edges of it:
//  - browsers without cross-document view transitions get .no-vt, which gives
//    the page a quick fade-in instead;
//  - the home header slides away while scrolling down. Leaving the page, it is
//    put back first so the transition has a header to morph.
(function () {
  var root = document.documentElement;
  if (!('onpageswap' in window)) root.classList.add('no-vt');
  addEventListener('pageswap', function () {
    var top = document.querySelector('[data-top]');
    if (!top) return;
    top.style.transition = 'none';
    top.classList.remove('is-hidden');
  });
})();

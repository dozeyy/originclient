// Page transitions, the plain-script half (loaded in <head> on every page).
// The morphing itself is pure CSS (@view-transition + view-transition-name in
// css/site.css). This only covers the one edge of it: browsers without
// cross-document view transitions get .no-vt, which gives the page a quick
// fade-in instead.
(function () {
  if (!('onpageswap' in window)) document.documentElement.classList.add('no-vt');
})();

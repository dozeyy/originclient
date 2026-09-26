document.documentElement.classList.add('js');

const header = document.querySelector('[data-header]');
const setHeaderState = () => header?.classList.toggle('is-scrolled', window.scrollY > 24);
setHeaderState();
window.addEventListener('scroll', setHeaderState, { passive: true });

const revealItems = document.querySelectorAll('.reveal');
if ('IntersectionObserver' in window) {
  const revealObserver = new IntersectionObserver((entries, observer) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add('is-visible');
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -48px' });
  revealItems.forEach((item) => revealObserver.observe(item));
} else {
  revealItems.forEach((item) => item.classList.add('is-visible'));
}

const tilt = document.querySelector('[data-tilt]');
const canTilt = window.matchMedia('(pointer: fine)').matches && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
if (tilt && canTilt) {
  tilt.addEventListener('pointermove', (event) => {
    const bounds = tilt.getBoundingClientRect();
    const x = ((event.clientX - bounds.left) / bounds.width - 0.5) * 8;
    const y = ((event.clientY - bounds.top) / bounds.height - 0.5) * 8;
    tilt.style.setProperty('--tilt-x', `${x}px`);
    tilt.style.setProperty('--tilt-y', `${y}px`);
  });
  tilt.addEventListener('pointerleave', () => {
    tilt.style.removeProperty('--tilt-x');
    tilt.style.removeProperty('--tilt-y');
  });
}

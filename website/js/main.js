(() => {
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Nav background on scroll
  const nav = document.getElementById("nav");
  const onScroll = () => nav.classList.toggle("scrolled", window.scrollY > 8);
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  // Scroll reveal
  const revealEls = document.querySelectorAll(".reveal");
  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry, i) => {
        if (entry.isIntersecting) {
          setTimeout(() => entry.target.classList.add("is-visible"), i * 40);
          io.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.15, rootMargin: "0px 0px -40px 0px" }
  );
  revealEls.forEach((el) => io.observe(el));

  // Count-up stat ("1x" -> "3x") once it scrolls into view
  const statEl = document.querySelector(".stat__number");
  if (statEl) {
    const target = parseFloat(statEl.dataset.countTo);
    const suffix = statEl.dataset.suffix || "";
    let played = false;
    const statIo = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting && !played) {
            played = true;
            if (reduceMotion) {
              statEl.textContent = target + suffix;
              return;
            }
            const duration = 1200;
            const start = performance.now();
            const from = 1;
            const step = (now) => {
              const p = Math.min((now - start) / duration, 1);
              const eased = 1 - Math.pow(1 - p, 3);
              const value = from + (target - from) * eased;
              statEl.textContent = value.toFixed(1).replace(/\.0$/, "") + suffix;
              if (p < 1) requestAnimationFrame(step);
              else statEl.textContent = target + suffix;
            };
            requestAnimationFrame(step);
            statIo.unobserve(statEl);
          }
        });
      },
      { threshold: 0.6 }
    );
    statIo.observe(statEl);
  }

  // Graph metric toggle (FPS / Frame Time)
  const tabs = document.querySelectorAll(".graph__tab");
  const lines = document.querySelectorAll(".graph__line");
  tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      const metric = tab.dataset.metric;
      tabs.forEach((t) => {
        t.classList.toggle("is-active", t === tab);
        t.setAttribute("aria-selected", t === tab ? "true" : "false");
      });
      lines.forEach((line) => {
        const points = line.dataset[metric];
        if (points) line.setAttribute("points", points);
      });
    });
  });

  // Cursor glow follow (desktop only)
  const glow = document.getElementById("cursorGlow");
  if (!reduceMotion && matchMedia("(hover: hover)").matches) {
    window.addEventListener("pointermove", (e) => {
      glow.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0)`;
    });
  }

  // No public build / Discord invite yet — give honest, visible feedback instead of a dead link
  const toast = document.getElementById("toast");
  let toastTimer;
  const showToast = (msg) => {
    toast.textContent = msg;
    toast.classList.add("is-shown");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("is-shown"), 3200);
  };
  document.querySelectorAll("#downloadBtn, #downloadBtnNav").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      showToast("Windows build isn't public yet — check back for v1.0.");
    });
  });
  document.querySelectorAll("#discordLink, #discordLinkNav, #discordLinkFooter").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      showToast("Discord invite is coming soon.");
    });
  });
  document.querySelectorAll("#githubLinkFooter, #termsLinkFooter").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      showToast("Not live yet.");
    });
  });
})();

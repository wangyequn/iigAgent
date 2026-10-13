/* 方博领航试用报告 — 交互 */
(function () {
  const progress = document.getElementById("progress");
  const nav = document.getElementById("nav");
  const links = nav ? Array.from(nav.querySelectorAll("a[href^='#']")) : [];
  const sections = links
    .map((a) => document.querySelector(a.getAttribute("href")))
    .filter(Boolean);

  function onScroll() {
    const doc = document.documentElement;
    const max = doc.scrollHeight - doc.clientHeight;
    const ratio = max > 0 ? doc.scrollTop / max : 0;
    if (progress) progress.style.width = Math.min(100, ratio * 100) + "%";

    const y = window.scrollY + 96;
    let current = sections[0];
    for (const sec of sections) {
      if (sec.offsetTop <= y) current = sec;
    }
    links.forEach((a) => {
      const active = current && a.getAttribute("href") === "#" + current.id;
      a.classList.toggle("active", Boolean(active));
    });
  }

  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();
})();

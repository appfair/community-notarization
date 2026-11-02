const header = document.querySelector<HTMLElement>(".draft-status");

if (header) {
  let scrollTimer: ReturnType<typeof setTimeout> | undefined;
  const stopScrolling = () => {
    clearTimeout(scrollTimer);
    header.classList.remove("is-scrolling");
  };

  window.addEventListener("scroll", () => {
    header.classList.add("is-scrolling");
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(stopScrolling, 180);
  }, { passive: true });
  window.addEventListener("scrollend", stopScrolling);
}

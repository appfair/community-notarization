const viewport = document.querySelector<HTMLElement>(".simulator-viewport");
const simulator = document.getElementById("simulator");

if (viewport && simulator) {
  const layoutWidth = parseFloat(getComputedStyle(simulator).width);
  const fit = (width: number) => {
    if (width > 0 && layoutWidth > 0)
      simulator.style.zoom = String(width / layoutWidth);
  };
  fit(viewport.getBoundingClientRect().width);
  new ResizeObserver(([entry]) => fit(entry.contentRect.width)).observe(
    viewport,
  );
}

import { res } from "../generated/res.generated";
import {
  notaries,
  verdictLabels,
  advLabels,
  type App,
  type Verdict,
} from "./model";
import type { AdvRegistration } from "./adv";
import type { Assessment, Route } from "./community";
import { platformGate, type Environment } from "./platform";

const s = res.str;
const sourceLabels = {
  fresh: s.source_fresh,
  local: s.source_local,
  cache: s.source_cache,
  provider: s.source_provider,
  exempt: s.source_exempt,
  none: s.source_none,
};
type Overview = {
  app: App;
  active: number[];
  verdicts: readonly Verdict[];
  registration: AdvRegistration;
  baseline: boolean;
  assessments: Assessment[];
  environment: Environment;
  routes: Route[];
  online: boolean;
  evidenceCount: number;
  expectedResult: string;
};

export function updateOverview(state: Overview) {
  const root = document.getElementById("proposal-overview");
  if (!root) return;
  const set = (id: string, value: string) => {
    root.querySelector(`#${id}`)!.textContent = value;
  };
  set("overview-app", state.app.file);
  root
    .querySelector("#overview-app-icon")!
    .setAttribute("d", res.vectors[state.app.icon]);
  set("overview-outcome", s.overview_result(state.expectedResult));
  set(
    "overview-coordinator-name",
    state.environment.platform === "aosp"
      ? s.overview_aosp_coordinator()
      : s.overview_google_coordinator(),
  );
  set(
    "overview-coordinator-id",
    state.environment.platform === "aosp"
      ? res.assets.providers.coordinators.aosp
      : res.assets.providers.coordinators.google,
  );
  set("overview-cache-count", s.overview_cache_count(state.evidenceCount));
  const native = root.querySelector("#overview-native")!;
  native.toggleAttribute(
    "data-hidden",
    state.environment.platform !== "platform",
  );
  set(
    "overview-native-label",
    state.environment.coordinated
      ? s.overview_native_coordinated()
      : s.overview_native(),
  );
  const canAssess =
    platformGate(state.environment, state.registration, state.baseline) ===
      "community" &&
    (state.environment.delivery === "incremental"
      ? state.environment.identityAvailable
      : state.environment.extensionAvailable);
  const cacheOnly = state.environment.delivery === "incremental";
  const accessibleRows: HTMLLIElement[] = [];
  let usesCache = cacheOnly;
  for (const [i, notary] of notaries.entries()) {
    const row = root.querySelector<SVGGElement>(
      `[data-overview-provider="${notary.id}"]`,
    )!;
    const enabled = state.active.includes(i);
    const assessment = state.baseline
      ? undefined
      : state.assessments.find((a) => a.id === notary.id);
    const verdict = assessment?.verdict ?? state.verdicts[i];
    const route = state.routes[i];
    const cached = assessment?.source === "cache";
    const local = route === "service-local";
    const saved = route === "service-store";
    const source = !enabled
      ? s.inactive()
      : state.baseline
        ? s.overview_baseline()
        : assessment
          ? sourceLabels[assessment.source]()
          : s.source_none();
    const status = !enabled
      ? s.inactive()
      : i === 0 &&
          verdict !== "exempt" &&
          (!assessment || assessment.source === "fresh")
        ? advLabels[state.registration]()
        : verdictLabels[verdict]();
    const hasLiveResult =
      enabled &&
      canAssess &&
      !cacheOnly &&
      !cached &&
      verdict !== "exempt" &&
      (state.online || local || saved) &&
      (!assessment || assessment.source !== "none");
    row.dataset.state = enabled ? verdict : "inactive";
    row.dataset.route = route;
    row.dataset.live = String(hasLiveResult);
    row.dataset.cached = String(cached);
    row.querySelector("[data-overview-verdict]")!.textContent = status;
    row.querySelector("[data-overview-source]")!.textContent = source;
    row.querySelector("[data-overview-evidence-title]")!.textContent = local
      ? s.overview_database()
      : saved
        ? s.overview_saved()
        : s.overview_server();
    row.querySelector("[data-overview-evidence-detail]")!.textContent = local
      ? notary.id === "fossr"
        ? s.overview_database_detail()
        : s.overview_local_detail()
      : saved
        ? s.overview_saved_detail()
        : notary.id === "adv"
          ? s.overview_adv_server()
          : s.overview_server_detail();
    row
      .querySelector("[data-overview-evidence-icon]")!
      .setAttribute("d", res.vectors[local || saved ? "database" : "server"]);
    row
      .querySelector(".overview-direct-wire")!
      .toggleAttribute("data-hidden", route !== "https");
    row
      .querySelector(".overview-source-wire")!
      .toggleAttribute("data-hidden", route === "https");
    row.querySelector("[data-overview-transport]")!.textContent =
      !enabled || !canAssess || verdict === "exempt"
        ? s.overview_not_contacted()
        : cacheOnly
          ? cached
            ? s.overview_cache_used()
            : s.overview_cache_missing()
          : cached
            ? s.overview_cache_used()
            : route === "https"
              ? s.overview_https()
              : local
                ? s.overview_lookup()
                : saved
                  ? s.overview_read()
                  : s.overview_network();
    usesCache ||= enabled && cached;
    const item = document.createElement("li");
    item.textContent = s.overview_status_row(notary.name(), status, source);
    accessibleRows.push(item);
  }
  root.querySelector("#overview-cache")!.classList.toggle("is-used", usesCache);
  root
    .querySelector("#overview-assessments")!
    .replaceChildren(...accessibleRows);
}

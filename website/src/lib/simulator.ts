import { updateOverview } from "./overview";
import { res } from "../generated/res.generated";
import {
  apps,
  notaries,
  verdictLabels,
  verdictIcons,
  customOptionsFor,
  customReasons,
  advRegistrations,
  advLabels,
  advReasons,
  advVotes,
  type App,
  type Verdict,
} from "./model";
import {
  evaluateAdv,
  type AdvRegistration,
  type AdvancedFlow,
  type InstallMethod,
  type AdvAction,
} from "./adv";
import {
  CommunitySession,
  type Context,
  type Result,
  type Assessment,
  type Route,
} from "./community";
import {
  platformGate,
  decideDelivery,
  type Environment,
  type DecisionReason,
} from "./platform";
const s = res.str;
const element = <T extends HTMLElement = HTMLElement>(id: string) =>
  document.getElementById(id) as T;
const select = element<HTMLSelectElement>("app-select");
const controls = element<HTMLFieldSetElement>("controls");
const checkboxes = [
  ...document.querySelectorAll<HTMLInputElement>('input[name="notary"]'),
];
const responseSelects = [
  ...document.querySelectorAll<HTMLSelectElement>("[data-response]"),
];
const overlay = element("dialog-overlay");
const dialog = element("install-dialog");
const cancel = element<HTMLButtonElement>("cancel-button");
const confirm = element<HTMLButtonElement>("confirm-button");
const done = element<HTMLButtonElement>("done-button");
const reset = element<HTMLButtonElement>("reset-button");
const advFlow = element<HTMLSelectElement>("adv-flow");
const advMethod = element<HTMLSelectElement>("adv-method");
const advActionButton = element<HTMLButtonElement>("adv-action-button");
const advDuration = element<HTMLSelectElement>("adv-duration");
const connection = element<HTMLSelectElement>("connection");
const artifact = element<HTMLSelectElement>("artifact");
const useCache = element<HTMLInputElement>("use-cache");
const platform = element<HTMLSelectElement>("platform");
const coordination = element<HTMLSelectElement>("coordination");
const installerTarget = element<HTMLSelectElement>("installer-target");
const delivery = element<HTMLSelectElement>("delivery");
const extensionAvailable = element<HTMLInputElement>("extension-available");
const identityAvailable = element<HTMLInputElement>("identity-available");
function environment(): Environment {
  return {
    platform: platform.value as Environment["platform"],
    coordinated: coordination.value === "coordinated",
    installer: installerTarget.value as Environment["installer"],
    delivery: delivery.value as Environment["delivery"],
    identityAvailable: identityAvailable.checked,
    extensionAvailable: extensionAvailable.checked,
    method: advMethod.value as InstallMethod,
    advancedFlow: advFlow.value as AdvancedFlow,
  };
}
const routes = [
  ...document.querySelectorAll<HTMLSelectElement>("select[data-route]"),
];
const session = new CommunitySession();
let now = 0;
let allowanceExpires = Infinity;
let selected: App = apps[0];
const customVerdicts: Verdict[] = notaries.map(() => "approve");
let customRegistration: AdvRegistration = "registered";
type PendingInstall = {
  app: App;
  active: number[];
  verdicts: readonly Verdict[];
  registration: AdvRegistration;
  advAction?: AdvAction;
  method: InstallMethod;
  context: Context;
  result?: Result;
  environment: Environment;
  reason?: DecisionReason;
};
let pending: PendingInstall | null = null;
let lastReport: PendingInstall | null = null;
let phase:
  | "idle"
  | "checking"
  | "review"
  | "block"
  | "success"
  | "adv"
  | "failure"
  | "system" = "idle";
let timer: ReturnType<typeof setTimeout> | undefined;
const installed = new Set<string>();
const activeIndices = () =>
  checkboxes.flatMap((box, i) =>
    notaries[i].required || box.checked ? [i] : [],
  );
const currentVerdicts = (): readonly Verdict[] => {
  const verdicts: Verdict[] = [
    ...(selected.id === "custom" ? customVerdicts : selected.verdicts),
  ];
  verdicts[0] =
    advMethod.value === "standard" ? advVotes[currentRegistration()] : "exempt";
  return verdicts;
};
const currentRegistration = (): AdvRegistration =>
  selected.id === "custom" ? customRegistration : selected.advRegistration;
function context(): Context {
  const verdicts = currentVerdicts();
  return {
    artifact: `${selected.id}:${artifact.value}`,
    now,
    online: connection.value === "online",
    useCache: useCache.checked,
    cacheOnly: delivery.value === "incremental",
    requests: activeIndices().map((i) => ({
      id: notaries[i].id,
      route: routes[i].value as Route,
      verdict: verdicts[i],
      reason:
        i === 0
          ? advMethod.value !== "standard"
            ? s.adv_exempt_detail()
            : currentRegistration() === "revoked"
              ? s.adv_community_revoked_reason()
              : advReasons[currentRegistration()]()
          : selected.id === "custom"
            ? customReasons[verdicts[i]]()
            : s[selected.reasons[i]](),
    })),
  };
}
const sourceLabels = {
  fresh: s.source_fresh,
  local: s.source_local,
  cache: s.source_cache,
  provider: s.source_provider,
  exempt: s.source_exempt,
  none: s.source_none,
};
const advCopy = {
  allow: [s.adv_allow_title, s.adv_allow_detail],
  setup: [s.adv_setup_title, s.adv_setup_detail],
  wait: [s.adv_wait_title, s.adv_wait_detail],
  finish: [s.adv_finish_title, s.adv_finish_detail],
  warn: [s.adv_warn_title, s.adv_warn_detail],
  unavailable: [s.adv_unavailable_title, s.adv_unavailable_reason],
  undocumented: [s.adv_undocumented_title, s.adv_revoked_reason],
  expired: [s.adv_expired_title, s.adv_expired_detail],
};
function icon(name: keyof typeof res.vectors) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  for (const [key, value] of Object.entries({
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.7",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
    class: "icon",
  }))
    svg.setAttribute(key, value);
  const path = document.createElementNS(svg.namespaceURI, "path");
  path.setAttribute("d", res.vectors[name]);
  svg.append(path);
  return svg;
}
function setAppIcon(target: HTMLElement, app: App) {
  target.className = `app-icon ${app.color}`;
  target.replaceChildren(icon(app.icon));
}
function syncControls() {
  selected = apps.find((app) => app.id === select.value) ?? apps[0];
  setAppIcon(element("selected-icon"), selected);
  checkboxes.forEach((box, i) => {
    box.disabled = notaries[i].required;
    if (notaries[i].required) box.checked = true;
    routes[i].disabled = notaries[i].required || !box.checked;
    const app = document.querySelector<HTMLElement>(
      `[data-provider-app="${notaries[i].id}"]`,
    );
    if (app) app.hidden = !box.checked;
  });
  element("notary-apps").hidden = !checkboxes.some(
    (box, i) => !notaries[i].required && box.checked,
  );
  const active = activeIndices();
  const verdicts = currentVerdicts();
  const custom = selected.id === "custom";
  const advOnly = active.length === 1 && delivery.value === "staged";
  const env = environment();
  element("native-adv-settings").hidden = env.platform !== "platform";
  element("incremental-settings").hidden = env.delivery !== "incremental";
  element("extension-setting").hidden = env.delivery !== "staged";
  element("platform-status").textContent =
    env.delivery === "incremental"
      ? s.platform_status_streaming()
      : env.platform === "aosp"
        ? s.platform_status_aosp()
        : env.platform === "legacy"
          ? s.platform_status_legacy()
          : env.coordinated
            ? s.platform_status_coordinated()
            : s.platform_status_native();
  const preview = session.assess(context());
  element("active-count").textContent = s.active_count(
    active.length,
    notaries.length,
  );
  checkboxes.forEach((box, i) => {
    box.closest(".notary-row")?.classList.toggle("inactive", !box.checked);
    const badge = document.querySelector<HTMLElement>(`[data-verdict="${i}"]`)!;
    const assessment = !advOnly
      ? preview.assessments.find((a) => a.id === notaries[i].id)
      : undefined;
    const verdict = assessment?.verdict ?? verdicts[i];
    badge.hidden = custom;
    const response = responseSelects[i];
    response.hidden = !custom;
    response.disabled = !custom || !box.checked;
    response.value =
      notaries[i].id === "adv" ? customRegistration : customVerdicts[i];
    response.className = `custom-response ${verdicts[i]}`;
    badge.className = `verdict-badge ${box.checked ? verdict : "inactive"}`;
    const label = document.createElement("span");
    label.textContent = box.checked
      ? i === 0 &&
        verdict !== "exempt" &&
        (!assessment ||
          assessment.source === "fresh" ||
          assessment.source === "provider")
        ? advLabels[currentRegistration()]()
        : verdictLabels[verdict]()
      : s.inactive();
    badge.title = assessment ? sourceLabels[assessment.source]() : "";
    badge.replaceChildren(
      icon(box.checked ? verdictIcons[verdict] : "minus"),
      label,
    );
  });
  const outcome = preview.outcome;
  const mode = active.length ? outcome : "none";
  const copy = {
    allow: [s.prediction_allow, s.prediction_allow_detail],
    block: [s.prediction_block, s.prediction_block_detail],
    review: [s.prediction_review, s.prediction_review_detail],
    none: [s.prediction_none, s.prediction_none_detail],
  }[mode];
  element("prediction").className = `prediction ${outcome}`;
  element("prediction-title").textContent = copy[0]();
  element("prediction-detail").textContent = copy[1]();
  element("prediction-icon").replaceChildren(
    icon(
      outcome === "allow" ? "check" : outcome === "block" ? "close" : "chat",
    ),
  );
  element("adv-settings").hidden = !advOnly;
  element("simulation-settings").hidden = advOnly;
  element(advOnly ? "adv-settings" : "simulation-settings").append(
    element("simulation-clock"),
  );
  element("advance-seven").hidden = !advOnly;
  element("elapsed-time").textContent = s.elapsed_time(now);
  const counts = session.counts(now);
  element("cache-summary").textContent = s.cache_summary(counts.evidence);
  element("adv-notice").hidden = true;
  element("install-label").textContent = s.install();
  const gate = platformGate(env, currentRegistration(), advOnly);
  const deliveryDecision = decideDelivery(env, outcome);
  if (
    deliveryDecision.reason === "extension-failed" ||
    deliveryDecision.reason === "streaming-identity"
  ) {
    setPrediction(
      "block",
      reasonCopy[deliveryDecision.reason][0],
      reasonCopy[deliveryDecision.reason][1],
    );
  } else if (gate !== "community") {
    const reason = gate;
    setPrediction(
      gate === "system-flow" ? "review" : "block",
      reasonCopy[reason][0],
      reasonCopy[reason][1],
    );
  } else if (advOnly) {
    const action = evaluateAdv(
      currentRegistration(),
      advFlow.value as AdvancedFlow,
      advMethod.value as InstallMethod,
    );
    const [title, detail] = advCopy[action];
    const explanation =
      action === "allow" && advMethod.value !== "standard"
        ? s.adv_exempt_detail()
        : detail();
    element("prediction").className =
      `prediction ${action === "allow" ? "allow" : "review"}`;
    element("prediction-title").textContent = title();
    element("prediction-detail").textContent =
      action === "allow"
        ? explanation
        : ["unavailable", "undocumented", "expired"].includes(action)
          ? s.adv_not_simulated()
          : s.prediction_review_detail();
    element("prediction-icon").replaceChildren(
      icon(action === "allow" ? "check" : "warning"),
    );
    element("adv-notice").hidden = action === "allow";
    element("adv-notice-text").textContent = explanation;
    if (["setup", "wait", "finish"].includes(action))
      element("install-label").textContent = s.install_high_friction();
    else if (action === "warn")
      element("install-label").textContent = s.install_anyway();
  } else {
    const decision = deliveryDecision;
    if (decision.reason !== "community") {
      if (decision.outcome === "allow")
        setPrediction(
          "allow",
          s.streaming_allow_title,
          s.streaming_allow_detail,
        );
      else
        setPrediction(
          "block",
          reasonCopy[decision.reason][0],
          reasonCopy[decision.reason][1],
        );
    }
  }
  updateOverview({
    app: selected,
    active,
    verdicts,
    registration: currentRegistration(),
    baseline: advOnly,
    assessments: preview.assessments,
    environment: env,
    routes: routes.map((route) => route.value as Route),
    online: connection.value === "online",
    evidenceCount: counts.evidence,
    expectedResult: element("prediction-title").textContent ?? "",
  });
}
const reasonCopy = {
  community: [s.blocked_title, s.blocked_detail],
  "platform-denial": [s.platform_denial_title, s.platform_denial_detail],
  "system-flow": [s.system_flow_title, s.system_flow_detail],
  "streaming-cache": [s.streaming_block_title, s.streaming_deny_detail],
  "streaming-review": [s.streaming_block_title, s.streaming_review_detail],
  "streaming-identity": [s.streaming_block_title, s.streaming_identity_detail],
  "review-timeout": [s.review_timeout_title, s.review_timeout_detail],
  "extension-failed": [s.extension_failure_title, s.extension_failure_detail],
} as const;
function setPrediction(
  outcome: "allow" | "block" | "review",
  title: () => string,
  detail: () => string,
) {
  element("prediction").className = `prediction ${outcome}`;
  element("prediction-title").textContent = title();
  element("prediction-detail").textContent = detail();
  element("prediction-icon").replaceChildren(
    icon(
      outcome === "allow" ? "check" : outcome === "block" ? "close" : "warning",
    ),
  );
}
function showFailure(reason: DecisionReason) {
  if (!pending) return;
  pending.reason = reason;
  lastReport = { ...pending };
  showPhase("failure");
  element("dialog-owner").textContent = s.system_installer_label();
  element("dialog-title").textContent = s.generic_failure_title();
  element("dialog-description").textContent = s.generic_failure_detail();
  element("dialog-verdicts").replaceChildren();
  done.focus();
}
function showSystemFlow() {
  showPhase("system");
  element("dialog-owner").textContent = s.system_installer_label();
  element("dialog-title").textContent = s.system_flow_title();
  element("dialog-description").textContent = s.system_flow_detail();
  element("dialog-verdicts").replaceChildren();
  done.focus();
}
function showPhase(next: typeof phase) {
  phase = next;
  overlay.hidden = false;
  controls.disabled = true;
  // Keep the reset control available before opening, and again after dismissing.
  reset.disabled = true;
  element("home-screen").inert = true;
  element("specification").inert = true;
  element("proposal-overview").inert = true;
  element("proposal-introduction").inert = true;
  element("pending-hint").hidden = false;
  element("checking-progress").hidden = next !== "checking";
  cancel.hidden = next !== "review";
  confirm.hidden = next !== "review";
  done.hidden = !["success", "block", "failure", "system"].includes(next);
  element("dialog-owner").textContent = s.installer_label();
  element("report-notification").hidden = true;
  advActionButton.hidden = true;
  element("adv-duration-control").hidden = true;
  element("adv-simulation-note").hidden = true;
  element("review-timeout-button").hidden = next !== "review";
  dialog.dataset.phase = next;
}
function verdictRows(
  app: App,
  active: number[],
  verdicts: readonly Verdict[],
  assessments?: Assessment[],
) {
  const rows = active.map((i) => {
    const assessment = assessments?.find((a) => a.id === notaries[i].id);
    const verdict = assessment?.verdict ?? verdicts[i];
    const row = document.createElement("div");
    row.className = `dialog-verdict ${verdict}`;
    const mark = document.createElement("span");
    mark.className = "verdict-mark";
    mark.append(icon(verdictIcons[verdict]));
    const text = document.createElement("div");
    const heading = document.createElement("div");
    heading.className = "verdict-heading";
    const name = document.createElement("strong");
    name.textContent = notaries[i].name();
    const status = document.createElement("span");
    status.textContent = verdictLabels[verdict]();
    if (
      i === 0 &&
      pending &&
      (!assessment ||
        assessment.source === "fresh" ||
        assessment.source === "provider")
    )
      status.textContent = advLabels[pending.registration]();
    heading.append(name, status);
    const reason = document.createElement("p");
    reason.textContent = assessment
      ? assessment.source === "none"
        ? customReasons[verdict]()
        : assessment.reason
      : notaries[i].id === "adv" && pending
        ? advReasons[pending.registration]()
        : app.id === "custom"
          ? customReasons[verdict]()
          : s[app.reasons[i]]();
    text.append(heading, reason);
    if (assessment) {
      const source = document.createElement("small");
      source.className = "evidence-source";
      source.textContent = assessment.problem
        ? s.source_with_problem(
            sourceLabels[assessment.source](),
            verdictLabels[assessment.problem](),
          )
        : sourceLabels[assessment.source]();
      text.append(source);
    }
    row.append(mark, text);
    return row;
  });
  element("dialog-verdicts").replaceChildren(...rows);
}
function showAdv(action: AdvAction) {
  if (!pending) return;
  pending.advAction = action;
  if (action === "allow") return installApp(false);
  showPhase("adv");
  element("dialog-owner").textContent = s.adv_flow_owner();
  element("dialog-title").textContent = advCopy[action][0]();
  element("dialog-description").textContent = advCopy[action][1]();
  element("dialog-verdicts").replaceChildren();
  const unsupported =
    action === "unavailable" ||
    action === "undocumented" ||
    action === "expired";
  cancel.hidden = unsupported;
  done.hidden = !unsupported;
  advActionButton.hidden = unsupported;
  element("adv-duration-control").hidden = action !== "finish";
  element("adv-simulation-note").hidden = unsupported || action === "warn";
  const buttons = {
    setup: s.adv_setup_button,
    wait: s.adv_wait_button,
    finish: s.adv_finish_button,
    warn: s.install_anyway,
  };
  if (!unsupported) advActionButton.textContent = buttons[action]();
  (unsupported ? done : cancel).focus({ preventScroll: true });
}
function installApp(manual: boolean) {
  if (!pending) return;
  const { app } = pending;
  if (!installed.has(app.id)) {
    const tile = document.createElement("div");
    tile.className = "installed-app";
    tile.dataset.app = app.id;
    const image = document.createElement("span");
    setAppIcon(image, app);
    const name = document.createElement("span");
    name.textContent = app.name;
    tile.append(image, name);
    element("installed-apps").append(tile);
    installed.add(app.id);
  }
  showPhase("success");
  element("dialog-title").textContent = s.success_title();
  element("dialog-description").textContent = pending.advAction
    ? s.adv_success()
    : manual
      ? s.success_manual()
      : s.success_auto();
  element("dialog-verdicts").replaceChildren();
  if (pending.result)
    verdictRows(
      app,
      pending.active,
      pending.verdicts,
      pending.result.assessments,
    );
  element("announcement").textContent = s.success_announcement(app.name);
  done.focus();
}
function closeDialog(canceled = false) {
  clearTimeout(timer);
  if (canceled && pending)
    element("announcement").textContent = s.cancel_announcement(
      pending.app.name,
    );
  overlay.hidden = true;
  element("report-notification").hidden = !lastReport;
  element("review-timeout-button").hidden = true;
  controls.disabled = false;
  reset.disabled = false;
  element("home-screen").inert = false;
  element("specification").inert = false;
  element("proposal-overview").inert = false;
  element("proposal-introduction").inert = false;
  element("pending-hint").hidden = true;
  pending = null;
  phase = "idle";
  syncControls();
  element("install-button").focus({ preventScroll: true });
}
element<HTMLFormElement>("install-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (phase !== "idle") return;
  const activeNotaries = activeIndices();
  const method = advMethod.value as InstallMethod;
  const registration = currentRegistration();
  pending = {
    app: selected,
    active: activeNotaries,
    verdicts: [...currentVerdicts()],
    registration,
    method,
    context: context(),
    environment: environment(),
    advAction:
      activeNotaries.length === 1 &&
      delivery.value === "staged" &&
      notaries[activeNotaries[0]].id === "adv"
        ? evaluateAdv(registration, advFlow.value as AdvancedFlow, method)
        : undefined,
  };
  lastReport = null;
  const { app, active, verdicts } = pending;
  setAppIcon(element("dialog-app-icon"), app);
  element("dialog-file").textContent = app.file;
  element("dialog-title").textContent = s.checking();
  element("dialog-description").textContent = s.checking_detail();
  element("dialog-verdicts").replaceChildren();
  showPhase("checking");
  dialog.focus({ preventScroll: true });
  if (window.matchMedia("(max-width: 880px)").matches)
    element("install-dialog").scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "instant"
        : "smooth",
      block: "center",
    });
  timer = setTimeout(
    () => {
      if (!pending) return;
      if (
        pending.environment.delivery === "staged" &&
        !pending.environment.extensionAvailable
      )
        return showFailure("extension-failed");
      if (
        pending.environment.delivery === "incremental" &&
        !pending.environment.identityAvailable
      )
        return showFailure("streaming-identity");
      const gate = platformGate(
        pending.environment,
        pending.registration,
        pending.advAction !== undefined,
      );
      if (gate === "platform-denial") return showFailure(gate);
      if (gate === "system-flow") return showSystemFlow();
      if (pending.advAction) return showAdv(pending.advAction);
      pending.result = session.assess(pending.context, true);
      const { assessments } = pending.result;
      const decision = decideDelivery(
        pending.environment,
        pending.result.outcome,
      );
      const outcome = decision.outcome;
      if (outcome === "allow") return installApp(false);
      if (outcome === "block") return showFailure(decision.reason);
      showPhase(outcome);
      element("dialog-title").textContent = s.review_title();
      element("dialog-description").textContent = active.length
        ? s.review_detail()
        : s.no_notaries_detail();
      verdictRows(app, active, verdicts, assessments);
      cancel.focus({ preventScroll: true });
    },
    window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 650,
  );
});
select.addEventListener("change", syncControls);
checkboxes.forEach((box) =>
  box.addEventListener("change", () => {
    session.clear();
    syncControls();
  }),
);
responseSelects.forEach((response, i) =>
  response.addEventListener("change", () => {
    if (phase !== "idle" || response.disabled || selected.id !== "custom")
      return;
    if (notaries[i].id === "adv") {
      const registration = advRegistrations.find(
        (option) => option === response.value,
      );
      if (registration) {
        customRegistration = registration;
        customVerdicts[i] = advVotes[registration];
      }
    } else {
      const verdict = customOptionsFor(notaries[i].id).find(
        (option) => option === response.value,
      );
      if (verdict) customVerdicts[i] = verdict;
    }
    syncControls();
  }),
);
advFlow.addEventListener("change", () => {
  allowanceExpires = advFlow.value === "seven-days" ? now + 168 : Infinity;
  syncControls();
});
advMethod.addEventListener("change", syncControls);
advActionButton.addEventListener("click", () => {
  if (phase !== "adv" || !pending) return;
  const action = pending.advAction;
  if (action === "warn") return installApp(true);
  if (action === "setup") advFlow.value = "waiting";
  else if (action === "wait") {
    now += 24;
    advFlow.value = "ready";
  } else if (action === "finish") {
    advFlow.value = advDuration.value;
    allowanceExpires = advFlow.value === "seven-days" ? now + 168 : Infinity;
  } else return;
  showAdv(
    evaluateAdv(
      pending.registration,
      advFlow.value as AdvancedFlow,
      pending.method,
    ),
  );
});
confirm.addEventListener("click", () => {
  if (phase === "review") {
    installApp(true);
  }
});
cancel.addEventListener("click", () => {
  closeDialog(true);
});
done.addEventListener("click", () => closeDialog());
reset.addEventListener("click", () => {
  closeDialog();
  installed.clear();
  element("installed-apps").replaceChildren();
  select.value = apps[0].id;
  checkboxes.forEach((box) => {
    box.checked = true;
  });
  customVerdicts.fill("approve");
  customRegistration = "registered";
  advFlow.value = "off";
  advMethod.value = "standard";
  advDuration.value = "seven-days";
  now = 0;
  allowanceExpires = Infinity;
  session.clear();
  connection.value = "online";
  artifact.value = "original";
  useCache.checked = true;
  platform.value = "legacy";
  coordination.value = "independent";
  installerTarget.value = "modern";
  delivery.value = "staged";
  identityAvailable.checked = true;
  extensionAvailable.checked = true;
  lastReport = null;
  element("report-notification").hidden = true;
  element<HTMLDetailsElement>("platform-settings").open = false;
  routes.forEach((route, i) => {
    route.value = notaries[i].defaultRoute;
  });
  element<HTMLDetailsElement>("simulation-settings").open = false;
  element<HTMLDetailsElement>("adv-settings").open = false;
  element("announcement").textContent = "";
  syncControls();
});
// A contained dialog keeps the phone appearance while preserving modal keyboard behavior.
document.addEventListener("keydown", (event) => {
  if (phase === "idle") return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeDialog(phase === "review" || phase === "checking" || phase === "adv");
  }
  if (event.key === "Tab") {
    const buttons: HTMLElement[] = [
      cancel,
      confirm,
      advActionButton,
      done,
      element<HTMLButtonElement>("review-timeout-button"),
    ].filter((button) => !button.hidden);
    if (!element("adv-duration-control").hidden) buttons.unshift(advDuration);
    if (!buttons.length) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    const first = buttons[0],
      last = buttons[buttons.length - 1];
    if (
      event.shiftKey &&
      (document.activeElement === first || document.activeElement === dialog)
    ) {
      event.preventDefault();
      last.focus();
    } else if (
      !event.shiftKey &&
      (document.activeElement === last || document.activeElement === dialog)
    ) {
      event.preventDefault();
      first.focus();
    }
  }
});
for (const control of [
  connection,
  artifact,
  useCache,
  platform,
  coordination,
  installerTarget,
  delivery,
  identityAvailable,
  extensionAvailable,
])
  control.addEventListener("change", syncControls);
routes.forEach((route) =>
  route.addEventListener("change", () => {
    session.clear();
    syncControls();
  }),
);
element("clear-evidence").addEventListener("click", () => {
  session.clear();
  syncControls();
  element("announcement").textContent = s.cache_cleared();
});
element("replace-provider").addEventListener("click", () => {
  session.clear();
  syncControls();
  element("announcement").textContent = s.trust_changed();
});
function advance(hours: number) {
  now += hours;
  if (advFlow.value === "waiting" && hours >= 24) advFlow.value = "ready";
  if (advFlow.value === "seven-days" && now >= allowanceExpires) {
    advFlow.value = "expired";
    element("announcement").textContent = s.adv_flow_expired();
  }
  syncControls();
}
element("advance-day").addEventListener("click", () => advance(24));
element("advance-seven").addEventListener("click", () => advance(168));
element("review-timeout-button").addEventListener("click", () => {
  if (phase === "review") showFailure("review-timeout");
});
element("prepare-evidence").addEventListener("click", () => {
  session.assess({ ...context(), cacheOnly: false }, true);
  syncControls();
  element("announcement").textContent = s.prepared_evidence();
});
element("report-notification").addEventListener("click", () => {
  if (phase !== "idle" || !lastReport) return;
  pending = lastReport;
  showPhase("block");
  setAppIcon(element("dialog-app-icon"), pending.app);
  element("dialog-file").textContent = pending.app.file;
  const [title, detail] = reasonCopy[pending.reason ?? "community"];
  element("dialog-title").textContent = title();
  element("dialog-description").textContent = detail();
  if (pending.result)
    verdictRows(
      pending.app,
      pending.active,
      pending.verdicts,
      pending.result.assessments,
    );
  else element("dialog-verdicts").replaceChildren();
  done.focus();
});
syncControls();

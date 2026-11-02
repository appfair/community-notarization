export type AdvRegistration =
  "registered" | "unregistered" | "mismatch" | "unavailable" | "revoked";
export type AdvancedFlow =
  "off" | "waiting" | "ready" | "seven-days" | "indefinite" | "expired";
export type InstallMethod = "standard" | "adb" | "managed";
export type AdvAction =
  | "allow"
  | "setup"
  | "wait"
  | "finish"
  | "warn"
  | "unavailable"
  | "undocumented"
  | "expired";

// Models the announced worldwide requirements, not the regional rollout schedule.
export function evaluateAdv(
  registration: AdvRegistration,
  flow: AdvancedFlow,
  method: InstallMethod,
): AdvAction {
  if (method === "adb" || method === "managed") return "allow";
  if (registration === "registered") return "allow";
  // Published guidance does not establish a complete on-device policy for these cases.
  if (registration === "unavailable") return "unavailable";
  if (registration === "revoked") return "undocumented";
  if (flow === "expired") return "expired";
  if (flow === "waiting") return "wait";
  if (flow === "ready") return "finish";
  if (flow === "seven-days" || flow === "indefinite") return "warn";
  return "setup";
}

import type { AdvRegistration, AdvancedFlow, InstallMethod } from "./adv.ts";
import type { Outcome } from "./model.ts";

export type Platform = "legacy" | "platform" | "aosp";
export type Installer = "modern" | "legacy-user" | "legacy-privileged";
export type Environment = {
  platform: Platform;
  coordinated: boolean;
  installer: Installer;
  delivery: "staged" | "incremental";
  identityAvailable: boolean;
  extensionAvailable: boolean;
  method: InstallMethod;
  advancedFlow: AdvancedFlow;
};
// A system-flow result leaves the native outcome unresolved; it never implies permission to install.
export type Gate = "community" | "platform-denial" | "system-flow";
export type DecisionReason =
  | Gate
  | "streaming-cache"
  | "streaming-review"
  | "streaming-identity"
  | "review-timeout"
  | "extension-failed";

export type NativeAdvResult = "pass" | "confirmation" | "incomplete" | "deny";

// An advanced-flow allowance permits confirmation; it does not change registration.
export function nativeAdvResult(
  registration: AdvRegistration,
  flow: AdvancedFlow,
): NativeAdvResult {
  if (registration === "registered") return "pass";
  if (registration === "unavailable") return "incomplete";
  if (
    (registration === "unregistered" || registration === "mismatch") &&
    (flow === "seven-days" || flow === "indefinite")
  )
    return "confirmation";
  return "deny";
}

export function platformGate(
  environment: Environment,
  registration: AdvRegistration,
  advOnly = false,
): Gate {
  if (
    environment.platform !== "platform" ||
    environment.coordinated ||
    environment.method !== "standard"
  )
    return "community";
  const result = nativeAdvResult(registration, environment.advancedFlow);
  if (result === "pass") return "community";
  if (result === "confirmation") {
    // Only the ADV-only flow models Google's per-install confirmation.
    return advOnly && environment.delivery === "staged"
      ? "community"
      : "system-flow";
  }
  if (environment.installer === "legacy-privileged") return "platform-denial";
  if (result === "incomplete" || environment.installer === "legacy-user")
    return "system-flow";
  return "platform-denial";
}

export function decideDelivery(
  environment: Environment,
  outcome: Outcome,
): { outcome: Outcome; reason: DecisionReason } {
  if (environment.delivery === "staged")
    return environment.extensionAvailable
      ? { outcome, reason: "community" }
      : { outcome: "block", reason: "extension-failed" };
  if (!environment.identityAvailable)
    return { outcome: "block", reason: "streaming-identity" };
  if (outcome === "review")
    return { outcome: "block", reason: "streaming-review" };
  return { outcome, reason: "streaming-cache" };
}

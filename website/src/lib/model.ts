import { res } from "../generated/res.generated.ts";
import type { AdvRegistration } from "./adv.ts";
export type Verdict =
  | "approve"
  | "disapprove"
  | "abstain"
  | "revoke"
  | "age16"
  | "unavailable"
  | "timeout"
  | "invalid"
  | "crashed"
  | "exempt";
export type Outcome = "allow" | "block" | "review";
export const apps = res.assets.scenarios;
export type App = (typeof apps)[number];
export const notaries = [
  {
    id: "adv",
    ...res.assets.providers.notaries.adv,
    required: true,
    code: res.str.adv_code(),
    name: res.str.adv_name,
    description: res.str.adv_description,
    appIcon: "shield",
    appColor: "mint",
  },
  {
    id: "vt",
    ...res.assets.providers.notaries.vt,
    required: false,
    code: res.str.vt_code(),
    name: res.str.vt_name,
    description: res.str.vt_description,
    appIcon: "notary_scan",
    appColor: "blue",
  },
  {
    id: "fossr",
    ...res.assets.providers.notaries.fossr,
    required: false,
    code: res.str.fossr_code(),
    name: res.str.fossr_name,
    description: res.str.fossr_description,
    appIcon: "layers",
    appColor: "mint",
  },
  {
    id: "cr",
    ...res.assets.providers.notaries.cr,
    required: false,
    code: res.str.cr_code(),
    name: res.str.cr_name,
    description: res.str.cr_description,
    appIcon: "notary_report",
    appColor: "peach",
  },
  {
    id: "cs",
    ...res.assets.providers.notaries.cs,
    required: false,
    code: res.str.cs_code(),
    name: res.str.cs_name,
    description: res.str.cs_description,
    appIcon: "notary_media",
    appColor: "lavender",
  },
] as const;
export function evaluate(verdicts: readonly Verdict[]): Outcome {
  if (!verdicts.length) return "review";
  verdicts = verdicts.filter((v) => v !== "exempt");
  if (!verdicts.length) return "allow";
  if (verdicts.every((v) => v === "approve")) return "allow";
  if (verdicts.every((v) => v === "disapprove" || v === "revoke"))
    return "block";
  return "review";
}
export const verdictLabels = {
  approve: res.str.approve,
  disapprove: res.str.disapprove,
  abstain: res.str.abstain,
  revoke: res.str.revoke,
  age16: res.str.age16,
  unavailable: res.str.unavailable,
  timeout: res.str.timeout,
  invalid: res.str.invalid,
  crashed: res.str.crashed,
  exempt: res.str.adv_exempt,
};
export const verdictIcons = {
  approve: "check",
  disapprove: "close",
  abstain: "minus",
  revoke: "close",
  age16: "warning",
  unavailable: "warning",
  timeout: "warning",
  invalid: "warning",
  crashed: "warning",
  exempt: "minus",
} as const;
const customOptions: readonly Verdict[] = [
  "approve",
  "abstain",
  "disapprove",
  "revoke",
  "age16",
  "unavailable",
  "timeout",
  "invalid",
  "crashed",
];
export function customOptionsFor(
  notaryId: (typeof notaries)[number]["id"],
): readonly Verdict[] {
  return customOptions.filter(
    (verdict) => verdict !== "age16" || notaryId === "cs",
  );
}
export const customReasons = {
  approve: res.str.custom_approve,
  disapprove: res.str.custom_disapprove,
  abstain: res.str.custom_abstain,
  revoke: res.str.custom_revoke,
  age16: res.str.custom_age16,
  unavailable: res.str.custom_unavailable,
  timeout: res.str.custom_timeout,
  invalid: res.str.custom_invalid,
  crashed: res.str.custom_crashed,
  exempt: res.str.adv_exempt_detail,
};
export const advRegistrations: readonly AdvRegistration[] = [
  "registered",
  "unregistered",
  "mismatch",
  "unavailable",
  "revoked",
];
export const advLabels = {
  registered: res.str.adv_registered,
  unregistered: res.str.adv_unregistered,
  mismatch: res.str.adv_mismatch,
  unavailable: res.str.adv_unavailable,
  revoked: res.str.adv_revoked,
};
export const advReasons = {
  registered: res.str.adv_registered_reason,
  unregistered: res.str.adv_unregistered_reason,
  mismatch: res.str.adv_mismatch_reason,
  unavailable: res.str.adv_unavailable_reason,
  revoked: res.str.adv_revoked_reason,
};
export const advVotes: Record<AdvRegistration, Verdict> = {
  registered: "approve",
  unregistered: "disapprove",
  mismatch: "disapprove",
  unavailable: "unavailable",
  revoked: "revoke",
};

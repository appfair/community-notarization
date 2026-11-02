import test from "node:test";
import assert from "node:assert/strict";
import {
  evaluateAdv,
  type AdvancedFlow,
  type AdvRegistration,
} from "../src/lib/adv.ts";
// Synthetic states for Google's announced worldwide baseline.
test("registered packages have no additional ADV restriction", () => {
  for (const flow of [
    "off",
    "waiting",
    "ready",
    "seven-days",
    "indefinite",
    "expired",
  ] as AdvancedFlow[]) {
    assert.equal(evaluateAdv("registered", flow, "standard"), "allow");
  }
});
test("unregistered packages and mismatched certificates require advanced flow", () => {
  for (const registration of [
    "unregistered",
    "mismatch",
  ] as AdvRegistration[]) {
    assert.equal(evaluateAdv(registration, "off", "standard"), "setup");
    assert.equal(evaluateAdv(registration, "waiting", "standard"), "wait");
    assert.equal(evaluateAdv(registration, "ready", "standard"), "finish");
    assert.equal(evaluateAdv(registration, "seven-days", "standard"), "warn");
    assert.equal(evaluateAdv(registration, "indefinite", "standard"), "warn");
  }
});
test("ADB and organization-store installs on managed devices are exempt from ADV", () => {
  for (const registration of [
    "registered",
    "unregistered",
    "mismatch",
    "unavailable",
    "revoked",
  ] as AdvRegistration[]) {
    assert.equal(evaluateAdv(registration, "off", "adb"), "allow");
    assert.equal(evaluateAdv(registration, "off", "managed"), "allow");
  }
});
test("missing evidence and undocumented revocation policy are not treated as unverified developers", () => {
  assert.equal(evaluateAdv("unavailable", "off", "standard"), "unavailable");
  assert.equal(
    evaluateAdv("revoked", "indefinite", "standard"),
    "undocumented",
  );
  assert.equal(evaluateAdv("unregistered", "expired", "standard"), "expired");
});

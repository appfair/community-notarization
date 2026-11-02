import test from "node:test";
import assert from "node:assert/strict";
import {
  platformGate,
  nativeAdvResult,
  decideDelivery,
  type Environment,
} from "../src/lib/platform.ts";

const environment = (overrides: Partial<Environment> = {}): Environment => ({
  platform: "legacy",
  coordinated: false,
  installer: "modern",
  delivery: "staged",
  identityAvailable: true,
  extensionAvailable: true,
  method: "standard",
  advancedFlow: "off",
  ...overrides,
});

test("legacy and AOSP paths leave the decision to the package verifier", () => {
  for (const platform of ["legacy", "aosp"] as const)
    for (const registration of [
      "registered",
      "unregistered",
      "mismatch",
      "revoked",
      "unavailable",
    ] as const)
      assert.equal(
        platformGate(environment({ platform }), registration),
        "community",
      );
});

test("independent platform ADV can prevent community review", () => {
  const native = environment({ platform: "platform" });
  assert.equal(platformGate(native, "registered"), "community");
  for (const registration of ["unregistered", "mismatch", "revoked"] as const) {
    assert.equal(platformGate(native, registration), "platform-denial");
    assert.equal(
      platformGate({ ...native, installer: "legacy-user" }, registration),
      "system-flow",
    );
    assert.equal(
      platformGate({ ...native, installer: "legacy-privileged" }, registration),
      "platform-denial",
    );
    assert.equal(
      platformGate({ ...native, coordinated: true }, registration),
      "community",
    );
  }
  assert.equal(platformGate(native, "unavailable"), "system-flow");
  assert.equal(
    platformGate({ ...native, installer: "legacy-privileged" }, "unavailable"),
    "platform-denial",
  );
});

test("ADV exemptions bypass the native ADV gate, not community policy", () => {
  for (const method of ["adb", "managed"] as const) {
    const env = environment({ platform: "platform", method });
    assert.equal(platformGate(env, "unregistered"), "community");
    assert.equal(decideDelivery(env, "block").outcome, "block");
  }
});

test("complete APKs support review; incremental delivery requires an identified APK and cached approval", () => {
  for (const outcome of ["allow", "review", "block"] as const) {
    assert.equal(decideDelivery(environment(), outcome).outcome, outcome);
    assert.equal(
      decideDelivery(
        environment({ delivery: "incremental", identityAvailable: false }),
        outcome,
      ).reason,
      "streaming-identity",
    );
    const result = decideDelivery(
      environment({ delivery: "incremental" }),
      outcome,
    );
    assert.equal(result.outcome, outcome === "allow" ? "allow" : "block");
    assert.equal(
      result.reason,
      outcome === "review" ? "streaming-review" : "streaming-cache",
    );
  }
});

test("failed deadline extension rejects staged installs even with unanimous approval", () => {
  for (const outcome of ["allow", "review", "block"] as const)
    assert.deepEqual(
      decideDelivery(environment({ extensionAvailable: false }), outcome),
      { outcome: "block", reason: "extension-failed" },
    );
  assert.equal(
    decideDelivery(
      environment({ delivery: "incremental", extensionAvailable: false }),
      "allow",
    ).outcome,
    "allow",
  );
});

test("advanced-flow confirmation is separate from developer registration", () => {
  for (const advancedFlow of ["seven-days", "indefinite"] as const) {
    const env = environment({ platform: "platform", advancedFlow });
    for (const registration of ["unregistered", "mismatch"] as const) {
      assert.equal(nativeAdvResult(registration, advancedFlow), "confirmation");
      assert.equal(platformGate(env, registration, true), "community");
      assert.equal(platformGate(env, registration, false), "system-flow");
      assert.equal(
        platformGate({ ...env, delivery: "incremental" }, registration, true),
        "system-flow",
      );
    }
    assert.equal(platformGate(env, "revoked", true), "platform-denial");
    assert.equal(platformGate(env, "unavailable", true), "system-flow");
  }
  for (const advancedFlow of ["off", "waiting", "ready", "expired"] as const) {
    assert.equal(nativeAdvResult("unregistered", advancedFlow), "deny");
  }
});

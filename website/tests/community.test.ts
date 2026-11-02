import test from "node:test";
import assert from "node:assert/strict";
import {
  CommunitySession,
  type Context,
  type Request,
} from "../src/lib/community.ts";

// Synthetic fixtures represent authenticated provider results, not organization assessments.
const requests: Request[] = [
  {
    id: "adv",
    route: "builtin",
    verdict: "approve",
    reason: "fixture registered",
  },
  {
    id: "scanner",
    route: "https",
    verdict: "approve",
    reason: "fixture approved",
  },
];
const context = (overrides: Partial<Context> = {}): Context => ({
  artifact: "digest-a",
  now: 0,
  online: true,
  useCache: true,
  requests: structuredClone(requests),
  ...overrides,
});
const mixed = () =>
  context({
    requests: [requests[0], { ...requests[1], verdict: "disapprove" }],
  });

test("unavailable and unauthenticated results never create unanimity", () => {
  for (const verdict of [
    "unavailable",
    "timeout",
    "invalid",
    "crashed",
  ] as const) {
    for (const other of ["approve", "disapprove"] as const) {
      const c = context({
        requests: [
          { ...requests[0], verdict: other },
          { ...requests[1], verdict },
        ],
      });
      const result = new CommunitySession().assess(c);
      assert.equal(result.outcome, "review");
      assert.equal(result.assessments[1].source, "none");
    }
  }
});

test("preview does not save evidence; committed checks do", () => {
  const session = new CommunitySession();
  session.assess(context());
  assert.equal(session.assess(context({ online: false })).outcome, "review");
  session.assess(context(), true);
  const cached = session.assess(context({ online: false, now: 23 }));
  assert.equal(cached.outcome, "allow");
  assert.ok(cached.assessments.every((a) => a.source === "cache"));
  assert.equal(
    session.assess(context({ online: false, now: 24 })).outcome,
    "review",
  );
  assert.equal(
    session.assess(context({ online: false, artifact: "digest-b" })).outcome,
    "review",
  );
  assert.equal(
    session.assess(context({ online: false, useCache: false })).outcome,
    "review",
  );
});

test("failed requests can use cached evidence but invalid responses cannot", () => {
  const session = new CommunitySession();
  session.assess(context(), true);
  for (const verdict of ["timeout", "unavailable", "crashed"] as const) {
    const result = session.assess(
      context({ requests: [requests[0], { ...requests[1], verdict }] }),
    );
    assert.equal(result.outcome, "allow");
    assert.equal(result.assessments[1].source, "cache");
    assert.equal(result.assessments[1].problem, verdict);
  }
  const invalid = session.assess(
    context({
      requests: [requests[0], { ...requests[1], verdict: "invalid" }],
    }),
    true,
  );
  assert.equal(invalid.outcome, "review");
  assert.equal(invalid.assessments[1].source, "none");
  assert.equal(session.assess(context({ online: false })).outcome, "review");
});

test("on-device service can respond offline; online service cannot", () => {
  const local = { ...requests[1], route: "service-local" as const };
  const session = new CommunitySession();
  const result = session.assess(
    context({ online: false, requests: [requests[0], local] }),
  );
  assert.equal(result.assessments[0].source, "none");
  assert.equal(result.assessments[1].source, "local");
  assert.equal(result.outcome, "review");
  const remote = session.assess(
    context({
      online: false,
      requests: [{ ...local, route: "service-online" }],
    }),
  );
  assert.equal(remote.assessments[0].source, "none");
});

test("saved attestations arrive through an enabled provider and retain their expiry", () => {
  const session = new CommunitySession();
  const saved = context({
    online: false,
    now: 23,
    requests: [{ ...requests[1], route: "service-store" }],
  });
  const result = session.assess(saved, true);
  assert.equal(result.outcome, "allow");
  assert.equal(result.assessments[0].source, "provider");
  assert.equal(result.assessments[0].expires, 24);
  assert.equal(session.assess({ ...saved, now: 24 }).outcome, "review");
  assert.equal(
    session.assess({
      ...saved,
      requests: [{ ...saved.requests[0], verdict: "invalid" }],
    }).outcome,
    "review",
  );
});

test("incremental checks only read existing evidence, including for local providers", () => {
  const session = new CommunitySession();
  const c = context({
    requests: [requests[0], { ...requests[1], route: "service-local" }],
  });
  assert.equal(
    session.assess({ ...c, cacheOnly: true }, true).outcome,
    "review",
  );
  assert.equal(session.counts(0).evidence, 0);
  session.assess(c, true);
  const result = session.assess({
    ...c,
    cacheOnly: true,
    online: false,
    now: 23,
  });
  assert.equal(result.outcome, "allow");
  assert.ok(result.assessments.every((a) => a.source === "cache"));
  for (const change of [
    { now: 24 },
    { artifact: "digest-b" },
    { useCache: false },
  ]) {
    assert.equal(
      session.assess({ ...c, cacheOnly: true, ...change }).outcome,
      "review",
    );
  }
});

test("ADV exemptions do not override other notaries or require online evidence", () => {
  const session = new CommunitySession();
  const exempt = { ...requests[0], verdict: "exempt" as const };
  const c = context({
    online: false,
    requests: [
      exempt,
      { ...requests[1], route: "service-local", verdict: "disapprove" },
    ],
  });
  const result = session.assess(c, true);
  assert.equal(result.outcome, "block");
  assert.equal(result.assessments[0].source, "exempt");
  assert.equal(session.counts(0).evidence, 1);
  assert.equal(session.assess({ ...c, cacheOnly: true }).outcome, "block");
});

test("repeated checks of mixed assessments require review, including cached offline evidence", () => {
  const session = new CommunitySession();
  const c = mixed();
  assert.equal(session.assess(c, true).outcome, "review");
  assert.equal(session.assess(c, true).outcome, "review");
  assert.equal(session.assess({ ...c, online: false }).outcome, "review");
  assert.equal(
    session.assess({ ...c, online: false, useCache: false }).outcome,
    "review",
  );
});

test("clearing evidence removes offline approval", () => {
  const session = new CommunitySession();
  session.assess(context(), true);
  assert.equal(session.assess(context({ online: false })).outcome, "allow");
  session.clear();
  assert.deepEqual(session.counts(0), { evidence: 0 });
  assert.equal(session.assess(context({ online: false })).outcome, "review");
});

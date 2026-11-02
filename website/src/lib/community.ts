import { evaluate, type Verdict, type Outcome } from "./model.ts";

export type Route =
  "builtin" | "https" | "service-online" | "service-local" | "service-store";
export type Source =
  "fresh" | "local" | "cache" | "provider" | "none" | "exempt";
export type Request = {
  id: string;
  route: Route;
  verdict: Verdict;
  reason: string;
};
export type Assessment = Request & {
  source: Source;
  expires: number;
  problem?: Verdict;
};
export type Context = {
  artifact: string;
  now: number;
  online: boolean;
  useCache: boolean;
  cacheOnly?: boolean;
  requests: Request[];
};
export type Result = {
  outcome: Outcome;
  assessments: Assessment[];
};
const lifetime = 24;
const missing = new Set<Verdict>([
  "unavailable",
  "timeout",
  "invalid",
  "crashed",
]);
// Synthetic evidence models validation outcomes; it does not verify real signatures.
export class CommunitySession {
  private evidence = new Map<string, Assessment>();
  private evidenceKey(context: Context, request: Request) {
    return JSON.stringify([context.artifact, request.id, request.route]);
  }

  assess(context: Context, saveEvidence = false): Result {
    const assessments = context.requests.map((request) => {
      const key = this.evidenceKey(context, request);
      if (request.verdict === "exempt")
        return { ...request, source: "exempt" as const, expires: context.now };
      if (context.cacheOnly) {
        const cached = context.useCache ? this.evidence.get(key) : undefined;
        return cached && cached.expires > context.now
          ? { ...cached, source: "cache" as const }
          : {
              ...request,
              verdict: "unavailable" as const,
              source: "none" as const,
              expires: context.now,
            };
      }
      const local = request.route === "service-local";
      const store = request.route === "service-store";
      const verdict =
        (store && context.now >= lifetime) ||
        (!context.online && !local && !store)
          ? "unavailable"
          : request.verdict;
      let assessment: Assessment = {
        ...request,
        verdict,
        source: "none",
        expires: context.now,
      };
      if (!missing.has(verdict)) {
        assessment = {
          ...assessment,
          source: local ? "local" : store ? "provider" : "fresh",
          expires: store ? lifetime : context.now + lifetime,
        };
        if (saveEvidence) this.evidence.set(key, assessment);
      } else {
        const cached = context.useCache ? this.evidence.get(key) : undefined;
        if (verdict !== "invalid" && cached && cached.expires > context.now) {
          assessment = { ...cached, source: "cache", problem: verdict };
        } else if (saveEvidence && verdict === "invalid") {
          this.evidence.delete(key);
        }
      }
      return assessment;
    });
    const outcome = evaluate(assessments.map((a) => a.verdict));
    return { outcome, assessments };
  }

  clear() {
    this.evidence.clear();
  }

  counts(now: number) {
    return {
      evidence: [...this.evidence.values()].filter((a) => a.expires > now)
        .length,
    };
  }
}

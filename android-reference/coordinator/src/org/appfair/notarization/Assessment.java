package org.appfair.notarization;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One notary's assessment of one APK, as the coordinator understood it.
 *
 * The verdict vocabulary mirrors the specification: five answers a notary can give (approve,
 * deny, abstain, revoke, advisory) and four coordinator-generated states for a missing or
 * unusable answer (unavailable, timeout, invalid, stopped). Problems are shown to the user but
 * never become an approval, a denial or an abstention. When valid cached evidence stands in
 * for a missing answer, the verdict is the cached one, the source is CACHE and
 * {@link #problem} says what went wrong live.
 */
public final class Assessment {
    public enum Verdict {
        /** The notary vouches for this exact APK. */
        APPROVE,
        /** The notary objects to this APK ("deny" on the wire). */
        DISAPPROVE,
        /** The notary has no recommendation. */
        ABSTAIN,
        /** The notary withdrew an earlier approval; counts as a denial. */
        REVOKE,
        /** Advice that requires review (age 16+ is an advisory with minAge 16); not a check. */
        ADVISORY,
        /** The notary could not be reached (offline, not installed, connection refused). */
        UNAVAILABLE,
        /** No answer within the per-notary deadline. */
        TIMEOUT,
        /** An answer arrived but failed authentication or identity checks. */
        INVALID,
        /** The notary's service died while assessing. */
        CRASHED;

        /** True for the five verdicts that carry a real answer from the notary. */
        public boolean answered() {
            return this == APPROVE || this == DISAPPROVE || this == ABSTAIN || this == REVOKE || this == ADVISORY;
        }

        public boolean denies() {
            return this == DISAPPROVE || this == REVOKE;
        }

        public static Verdict parse(String s) {
            if (s == null) return INVALID;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "approve": return APPROVE;
                case "deny": case "disapprove": return DISAPPROVE;
                case "abstain": return ABSTAIN;
                case "revoke": return REVOKE;
                case "advisory": case "age16": return ADVISORY;
                case "unavailable": return UNAVAILABLE;
                case "timeout": return TIMEOUT;
                case "crashed": return CRASHED;
                default: return INVALID;
            }
        }

        public String label() {
            switch (this) {
                case APPROVE: return "Approved";
                case DISAPPROVE: return "Denied";
                case ABSTAIN: return "No recommendation";
                case REVOKE: return "Approval revoked";
                case ADVISORY: return "Advisory";
                case UNAVAILABLE: return "Unavailable";
                case TIMEOUT: return "Timed out";
                case INVALID: return "Invalid response";
                default: return "Service stopped";
            }
        }

        /** The user-facing explanation of a problem, in the simulator's words. */
        public String problemText() {
            switch (this) {
                case UNAVAILABLE: return "The notary could not be reached.";
                case TIMEOUT: return "The notary did not respond before the deadline.";
                case INVALID: return "The response failed authentication or did not match this request and app.";
                case CRASHED: return "The provider service stopped before returning an assessment.";
                default: return "";
            }
        }
    }

    /** Where the evidence came from (specification: "evidence source"). */
    public enum Source {
        /** A live response to this request. */
        FRESH,
        /** Computed by a local service on this device. */
        LOCAL,
        /** Unexpired evidence cached from an earlier request. */
        CACHE,
        /** A signed, portable attestation handed over by a provider app. */
        ATTESTATION,
        /** No valid assessment. */
        NONE;

        public String label() {
            switch (this) {
                case FRESH: return "current response";
                case LOCAL: return "on-device result";
                case CACHE: return "cached assessment";
                case ATTESTATION: return "signed attestation from the provider app";
                default: return "no valid assessment";
            }
        }
    }

    public final String notaryId;
    public final String notaryName;
    public final Verdict verdict;
    /** For an advisory: the recommended minimum age, else 0. */
    public final int minAge;
    public final String reason;
    public final Source source;
    /** When the source is CACHE: the problem the live request had. Otherwise null. */
    public final Verdict problem;
    public final long latencyMs;

    public Assessment(String notaryId, String notaryName, Verdict verdict, int minAge, String reason,
                      Source source, Verdict problem, long latencyMs) {
        this.notaryId = notaryId;
        this.notaryName = notaryName;
        this.verdict = verdict;
        this.minAge = minAge;
        this.reason = reason == null ? "" : reason;
        this.source = source;
        this.problem = problem;
        this.latencyMs = latencyMs;
    }

    public Assessment(String notaryId, String notaryName, Verdict verdict, String reason,
                      Source source, long latencyMs) {
        this(notaryId, notaryName, verdict, verdict == Verdict.ADVISORY ? 16 : 0, reason, source, null, latencyMs);
    }

    /** A recorded problem: no usable answer. */
    public static Assessment problem(String id, String name, Verdict problem, String detail, long latencyMs) {
        return new Assessment(id, name, problem, 0, detail == null || detail.isEmpty()
                ? problem.problemText() : detail, Source.NONE, null, latencyMs);
    }

    public Assessment asCached(Verdict liveProblem) {
        return new Assessment(notaryId, notaryName, verdict, minAge, reason, Source.CACHE, liveProblem, latencyMs);
    }

    /** The verdict as shown to the user; advisories name the age. */
    public String verdictLabel() {
        if (verdict == Verdict.ADVISORY && minAge > 0) return "Age " + minAge + "+";
        return verdict.label();
    }

    /** The source line shown under an assessment. */
    public String sourceText() {
        if (source == Source.CACHE && problem != null) {
            return source.label() + " · " + problem.label().toLowerCase(Locale.ROOT) + " just now";
        }
        return source.label();
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", notaryId).put("name", notaryName)
                    .put("verdict", verdict.name()).put("min_age", minAge).put("reason", reason)
                    .put("source", source.name()).put("problem", problem == null ? JSONObject.NULL : problem.name())
                    .put("latency_ms", latencyMs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Assessment fromJson(JSONObject o) {
        String p = o.optString("problem", null);
        return new Assessment(o.optString("id"), o.optString("name"),
                Verdict.valueOf(o.optString("verdict", "INVALID")), o.optInt("min_age", 0), o.optString("reason"),
                Source.valueOf(o.optString("source", "NONE")),
                p == null || p.equals("null") ? null : Verdict.valueOf(p), o.optLong("latency_ms"));
    }

    public static String toJsonArray(List<Assessment> list) {
        JSONArray a = new JSONArray();
        for (Assessment x : list) a.put(x.toJson());
        return a.toString();
    }

    public static List<Assessment> fromJsonArray(String json) {
        List<Assessment> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    @Override
    public String toString() {
        return notaryId + " -> " + verdict + (minAge > 0 ? "(" + minAge + "+)" : "") + " (" + reason + "; "
                + sourceText() + ", " + latencyMs + " ms)";
    }
}

package org.appfair.notarization;

import java.util.ArrayList;
import java.util.List;

/**
 * The decision rule, as a pure function so that it can be read, tested and argued about in
 * one place. It is the strict-unanimity rule of the specification
 * (website/src/lib/model.ts, {@code evaluate}), applied to the effective assessments of the
 * participating notaries, after exemptions and cache replacement:
 *
 * <pre>
 *   no participating notaries                       -> REVIEW
 *   every notary approves                           -> ALLOW  (install without confirmation)
 *   every notary denies or revokes                  -> BLOCK  (reject and show the reasons)
 *   any other combination                           -> REVIEW (show every assessment; Install / Cancel)
 * </pre>
 *
 * Abstentions, advisories and every kind of missing or invalid response prevent unanimity in
 * both directions; they are never counted as an approval or a denial. An exempt notary is not
 * a participant at all: the caller removes it before calling this rule, and when nothing
 * remains after an exemption the caller allows without asking (specification, "If no checks
 * remain after the ADV exemption, allow this verification step").
 */
public final class Policy {
    public enum Outcome { ALLOW, BLOCK, REVIEW }

    public static final class Decision {
        public final Outcome outcome;
        /** One sentence for the user explaining the outcome. */
        public final String headline;
        public final int approvals, denials, abstentions, advisories, problems;

        Decision(Outcome outcome, String headline, int approvals, int denials, int abstentions,
                 int advisories, int problems) {
            this.outcome = outcome; this.headline = headline; this.approvals = approvals;
            this.denials = denials; this.abstentions = abstentions; this.advisories = advisories;
            this.problems = problems;
        }

        public String tally() {
            return approvals + " approved · " + denials + " denied · " + abstentions
                    + " no recommendation · " + advisories + " advisory · " + problems + " no answer";
        }
    }

    /** The bare rule over verdicts; identical to the simulator's {@code evaluate}. */
    public static Outcome evaluate(List<Assessment.Verdict> verdicts) {
        if (verdicts.isEmpty()) return Outcome.REVIEW;
        boolean allApprove = true, allDeny = true;
        for (Assessment.Verdict v : verdicts) {
            if (v != Assessment.Verdict.APPROVE) allApprove = false;
            if (!v.denies()) allDeny = false;
        }
        if (allApprove) return Outcome.ALLOW;
        if (allDeny) return Outcome.BLOCK;
        return Outcome.REVIEW;
    }

    /** The rule plus an explanation. */
    public static Decision decide(List<Assessment> assessments) {
        int approvals = 0, denials = 0, abstentions = 0, advisories = 0, problems = 0;
        List<Assessment.Verdict> verdicts = new ArrayList<>();
        for (Assessment a : assessments) {
            verdicts.add(a.verdict);
            switch (a.verdict) {
                case APPROVE: approvals++; break;
                case DISAPPROVE: case REVOKE: denials++; break;
                case ABSTAIN: abstentions++; break;
                case ADVISORY: advisories++; break;
                default: problems++;
            }
        }
        Outcome outcome = evaluate(verdicts);
        String headline;
        if (assessments.isEmpty()) {
            headline = "No notaries are enabled on this device, so nothing is known about this app.";
        } else if (outcome == Outcome.ALLOW) {
            headline = (assessments.size() == 1 ? "The enabled notary approved" : "Every enabled notary approved")
                    + " this exact file.";
        } else if (outcome == Outcome.BLOCK) {
            headline = (assessments.size() == 1 ? "The enabled notary denied" : "Every enabled notary denied")
                    + " this app.";
        } else if (problems == assessments.size()) {
            headline = "No enabled notary could be reached, so nothing current is known about this app.";
        } else if (approvals > 0 && denials > 0) {
            headline = "The notaries disagree about this app.";
        } else if (approvals > 0) {
            headline = "Some notaries approved this app, but not all of them could vouch for it.";
        } else if (denials > 0) {
            headline = "Some notaries denied this app, and the others did not approve it.";
        } else {
            headline = "No enabled notary could vouch for this app.";
        }
        return new Decision(outcome, headline, approvals, denials, abstentions, advisories, problems);
    }

    private Policy() { }
}

import org.appfair.notarization.Assessment;
import org.appfair.notarization.Assessment.Verdict;
import org.appfair.notarization.Policy;
import org.appfair.notarization.Policy.Outcome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the strict-unanimity rule against the same cases as the specification's simulator
 * tests (website/tests/policy.test.ts). Run with scripts/test-policy.sh; no device needed.
 */
public class PolicyTest {
    static int failures = 0, checks = 0;

    static void expect(Outcome want, Verdict... verdicts) {
        checks++;
        Outcome got = Policy.evaluate(Arrays.asList(verdicts));
        List<Assessment> as = new ArrayList<>();
        for (int i = 0; i < verdicts.length; i++)
            as.add(new Assessment("n" + i, "Notary " + i, verdicts[i], "test", Assessment.Source.LOCAL, 1));
        Policy.Decision d = Policy.decide(as);
        boolean ok = got == want && d.outcome == want;
        System.out.println((ok ? "  ok   " : "  FAIL ") + Arrays.toString(verdicts) + " -> " + got + "  \"" + d.headline + "\"");
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // all active notaries must approve for a silent install
        expect(Outcome.ALLOW, Verdict.APPROVE);
        expect(Outcome.ALLOW, Verdict.APPROVE, Verdict.APPROVE);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.ABSTAIN);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.ADVISORY);
        // unanimous disapproval or revocation blocks, but dissent or abstention asks
        expect(Outcome.BLOCK, Verdict.REVOKE);
        expect(Outcome.BLOCK, Verdict.REVOKE, Verdict.DISAPPROVE);
        expect(Outcome.REVIEW, Verdict.DISAPPROVE, Verdict.APPROVE);
        expect(Outcome.REVIEW, Verdict.DISAPPROVE, Verdict.ABSTAIN);
        // no notaries and all abstentions never silently approve or block
        expect(Outcome.REVIEW);
        expect(Outcome.REVIEW, Verdict.ABSTAIN, Verdict.ABSTAIN);
        // missing or invalid responses prevent unanimity in both directions
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.TIMEOUT);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.UNAVAILABLE);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.INVALID);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.CRASHED);
        expect(Outcome.REVIEW, Verdict.DISAPPROVE, Verdict.TIMEOUT);
        expect(Outcome.REVIEW, Verdict.UNAVAILABLE, Verdict.UNAVAILABLE);
        // the six scenario matrices of the simulator
        expect(Outcome.ALLOW, Verdict.APPROVE, Verdict.APPROVE, Verdict.APPROVE, Verdict.APPROVE, Verdict.APPROVE);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.APPROVE, Verdict.ABSTAIN, Verdict.APPROVE, Verdict.ADVISORY);
        expect(Outcome.REVIEW, Verdict.ABSTAIN, Verdict.APPROVE, Verdict.ABSTAIN, Verdict.DISAPPROVE, Verdict.ABSTAIN);
        expect(Outcome.REVIEW, Verdict.REVOKE, Verdict.APPROVE, Verdict.ABSTAIN, Verdict.ABSTAIN, Verdict.ABSTAIN);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.DISAPPROVE, Verdict.ABSTAIN, Verdict.ABSTAIN, Verdict.ABSTAIN);
        expect(Outcome.REVIEW, Verdict.APPROVE, Verdict.APPROVE, Verdict.ABSTAIN, Verdict.DISAPPROVE, Verdict.DISAPPROVE);

        // every subset of every verdict obeys unanimity
        Verdict[] all = Verdict.values();
        for (int mask = 1; mask < (1 << all.length); mask++) {
            List<Verdict> votes = new ArrayList<>();
            for (int i = 0; i < all.length; i++) if ((mask & (1 << i)) != 0) votes.add(all[i]);
            Outcome o = Policy.evaluate(votes);
            boolean allApprove = votes.stream().allMatch(v -> v == Verdict.APPROVE);
            boolean allDeny = votes.stream().allMatch(Verdict::denies);
            Outcome want = allApprove ? Outcome.ALLOW : allDeny ? Outcome.BLOCK : Outcome.REVIEW;
            checks++;
            if (o != want) { failures++; System.out.println("  FAIL subset " + votes + " -> " + o + " wanted " + want); }
        }
        System.out.println(failures == 0 ? "all " + checks + " policy checks passed" : failures + " policy check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}

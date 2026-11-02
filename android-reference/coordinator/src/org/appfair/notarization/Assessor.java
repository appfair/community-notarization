package org.appfair.notarization;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.appfair.notarization.provider.Attestation;
import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks every participating notary about one complete, staged APK, in parallel, each under the
 * specification's eight-second limit, and turns what comes back into {@link Assessment}s:
 *
 * <ul>
 *   <li>a valid live answer is recorded as evidence until its expiry, at most 24 hours;</li>
 *   <li>a timeout, a crash or an unreachable provider falls back to unexpired evidence for the
 *       same provider, configuration and exact APK, marked as cached;</li>
 *   <li>an invalid response deletes that evidence and never falls back;</li>
 *   <li>if the active provider list or an accepted identity changed while the check ran, the
 *       results are discarded and collection restarts once, within the original deadline.</li>
 * </ul>
 *
 * Streaming (incremental) installs never reach this class: the specification lets them use
 * only already cached evidence for an authenticated APK identity, and this reference has no
 * such identity for an incomplete file, so {@link VerificationService} rejects them first.
 */
public final class Assessor {
    public static final class Result {
        public final List<ProviderRegistry.Provider> active;
        public final String configuration;
        public final List<Assessment> assessments;

        Result(List<ProviderRegistry.Provider> active, String configuration, List<Assessment> assessments) {
            this.active = active; this.configuration = configuration; this.assessments = assessments;
        }
    }

    /** What an adapter returns: the coordinator's reading of the answer plus its expiry. */
    static final class Live {
        final Assessment assessment;
        final long expiresAt;

        Live(Assessment assessment, long expiresAt) {
            this.assessment = assessment; this.expiresAt = expiresAt;
        }

        static Live problem(ProviderRegistry.Provider p, Assessment.Verdict problem, String detail, long t0) {
            return new Live(Assessment.problem(p.id, p.name, problem, detail, System.currentTimeMillis() - t0), 0);
        }
    }

    private final Context context;
    private final ExecutorService executor;
    private final EvidenceStore evidence;

    public Assessor(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
        this.evidence = new EvidenceStore(context);
    }

    /**
     * @param active     the participants, already selected (exemptions applied)
     * @param deadlineAt the verification's one monotonic deadline ({@link SystemClock#elapsedRealtime})
     */
    public Result assess(int id, List<ProviderRegistry.Provider> active, ApkFacts facts, String installer,
                         long deadlineAt) {
        return assess(id, active, facts, installer, deadlineAt, false);
    }

    private Result assess(int id, List<ProviderRegistry.Provider> active, ApkFacts facts, String installer,
                          long deadlineAt, boolean restarted) {
        String configuration = ProviderRegistry.configurationFingerprint(active);
        Log.i(VerificationReceiver.TAG, "id=" + id + " active notaries=" + ProviderRegistry.describe(active));

        List<Future<Assessment>> futures = new ArrayList<>();
        for (ProviderRegistry.Provider p : active) {
            futures.add(executor.submit(() -> one(id, p, facts, configuration)));
        }
        List<Assessment> out = new ArrayList<>();
        long collectUntil = Math.min(SystemClock.elapsedRealtime() + NotaryContract.DEADLINE_MS + 3_000, deadlineAt - 60_000);
        for (int i = 0; i < futures.size(); i++) {
            ProviderRegistry.Provider p = active.get(i);
            try {
                out.add(futures.get(i).get(Math.max(1, collectUntil - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                out.add(Assessment.problem(p.id, p.name, Assessment.Verdict.TIMEOUT, null, NotaryContract.DEADLINE_MS));
            } catch (Exception e) {
                Log.e(VerificationReceiver.TAG, "id=" + id + " adapter for " + p.id + " failed", e);
                out.add(Assessment.problem(p.id, p.name, Assessment.Verdict.CRASHED, "The coordinator's adapter failed: " + e, 0));
            }
        }

        // Specification, "Provider updates": if the active list or an accepted configuration
        // changed during the check, discard the results and restart collection, once, within the
        // original deadline.
        if (!restarted) {
            PackageManager pm = context.getPackageManager();
            for (ProviderRegistry.Provider p : active) {
                String signerNow = ProviderRegistry.signerOf(pm, p.packageName);
                if (signerNow == null || !signerNow.equals(p.signerSha256)) {
                    Log.w(VerificationReceiver.TAG, "id=" + id + " provider " + p.id + " was "
                            + (signerNow == null ? "removed" : "replaced") + " during the check; restarting it once");
                    ProviderRegistry refreshed = ProviderRegistry.discover(context);
                    return assess(id, refreshed.active(context, installer).providers, facts, installer, deadlineAt, true);
                }
            }
        }
        return new Result(active, configuration, out);
    }

    private Assessment one(int id, ProviderRegistry.Provider p, ApkFacts facts, String configuration) {
        long t0 = System.currentTimeMillis();
        Live live;
        if (p.identityChanged()) {
            live = Live.problem(p, Assessment.Verdict.INVALID, "The provider app's signing identity changed since "
                    + "it was enabled. Enable it again in settings to trust the new version.", t0);
        } else {
            NotaryRequest request = request(p, facts);
            try {
                live = p.isHttps() ? HttpsAdapter.assess(p, request, facts) : BinderAdapter.assess(context, p, request, facts);
            } finally {
                if (request.apk != null) {
                    try { request.apk.close(); } catch (Exception ignored) { }
                }
            }
        }
        Assessment a = live.assessment;
        Log.i(VerificationReceiver.TAG, "id=" + id + " " + a);
        if (a.verdict.answered()) {
            evidence.save(configuration, p, facts, a, live.expiresAt);
            return a;
        }
        if (a.verdict == Assessment.Verdict.INVALID) {
            evidence.invalidate(configuration, p, facts);
            return a;
        }
        Assessment cached = evidence.find(configuration, p, facts);
        if (cached != null) {
            Assessment replacement = cached.asCached(a.verdict);
            Log.i(VerificationReceiver.TAG, "id=" + id + " " + p.id + ": using cached evidence instead ("
                    + cached.verdict + ")");
            Log.i(VerificationReceiver.TAG, "id=" + id + " " + replacement);
            return replacement;
        }
        return a;
    }

    private NotaryRequest request(ProviderRegistry.Provider p, ApkFacts facts) {
        ParcelFileDescriptor apk = null;
        if (p.apkAccessGranted() && facts.apkPath != null) {
            try {
                apk = ParcelFileDescriptor.open(new File(facts.apkPath), ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception e) {
                Log.w(VerificationReceiver.TAG, "could not open " + facts.apkPath + " for " + p.id + ": " + e);
            }
        }
        return new NotaryRequest(NotaryContract.PROTOCOL_VERSION, UUID.randomUUID().toString(),
                facts.packageName, facts.versionCode, facts.apkSha256,
                facts.signerSha256 == null ? new String[0] : new String[] {facts.signerSha256},
                p.wantsLanguage() ? Locale.getDefault().toLanguageTag() : null, apk);
    }

    /**
     * The checks every answer must pass (specification, "Response"): known version, matching
     * provider, request ID and exact app identity, a known verdict, bounded text, not expired,
     * and where a signature is expected, a valid one from the accepted keys. Returns null when
     * valid, otherwise the reason.
     */
    static String validate(ProviderRegistry.Provider p, NotaryRequest request, ApkFacts facts,
                           NotaryAssessment a, boolean https) {
        if (a == null) return "empty response";
        if (a.protocolVersion != NotaryContract.PROTOCOL_VERSION) return "unknown protocolVersion " + a.protocolVersion;
        if (!p.id.equals(a.providerId)) return "providerId mismatch (" + a.providerId + ")";
        if (!request.requestId.equals(a.requestId)) return "requestId mismatch";
        if (!request.packageName.equals(a.packageName)) return "package name mismatch (" + a.packageName + ")";
        if (request.versionCode != a.versionCode) return "version code mismatch (" + a.versionCode + ")";
        if (a.apkSha256 == null || !a.apkSha256.equalsIgnoreCase(facts.apkSha256)) return "APK digest mismatch";
        if (!sameDigests(request.signingCertificateSha256, a.signingCertificateSha256)) return "signing certificate mismatch";
        if (!NotaryContract.isVerdict(a.verdict)) return "unknown verdict " + a.verdict;
        if (a.minAge < 0 || a.minAge > 99) return "bad minAge";
        if (length(a.reasonCode) == 0 || length(a.reasonCode) > NotaryContract.MAX_REASON_CODE_CHARS) return "bad reasonCode";
        if (length(a.explanation) > NotaryContract.MAX_EXPLANATION_CHARS) return "explanation too long";
        long now = System.currentTimeMillis(), issued = a.issuedAtMillis(), expires = a.expiresAtMillis();
        if (issued < 0 || expires < 0) return "unparseable time";
        if (expires <= now) return "expired";
        if (issued > now + 5 * 60_000) return "issued in the future";
        if (https) {
            if (!Attestation.verify(a, p.keys, true)) return "signature did not verify";
        } else if (a.attestationSignature != null && !p.issuerKeys.isEmpty()) {
            if (!Attestation.verify(a, p.issuerKeys, false)) return "attestation signature did not verify";
        }
        return null;
    }

    /** Turns a validated answer into the coordinator's record of it. */
    static Live accept(ProviderRegistry.Provider p, NotaryAssessment a, long t0) {
        Assessment.Source source;
        if (p.isHttps()) source = Assessment.Source.FRESH;
        else if (a.attestationSignature != null && !p.issuerKeys.isEmpty()) source = Assessment.Source.ATTESTATION;
        else source = p.local ? Assessment.Source.LOCAL : Assessment.Source.FRESH;
        String reason = a.explanation == null || a.explanation.isEmpty() ? a.reasonCode : a.explanation;
        Assessment.Verdict v = Assessment.Verdict.parse(a.verdict);
        return new Live(new Assessment(p.id, p.name, v, v == Assessment.Verdict.ADVISORY ? a.minAge : 0, reason,
                source, null, System.currentTimeMillis() - t0), a.expiresAtMillis());
    }

    private static boolean sameDigests(String[] a, String[] b) {
        HashSet<String> x = new HashSet<>(), y = new HashSet<>();
        for (String s : a) x.add(s.toLowerCase(Locale.ROOT));
        for (String s : b == null ? new String[0] : b) y.add(s.toLowerCase(Locale.ROOT));
        return x.equals(y);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    static final List<String> NO_IDS = Arrays.asList();
}

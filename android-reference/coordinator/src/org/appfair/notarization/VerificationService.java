package org.appfair.notarization;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The coordinator's job for one pending install, in the order the specification gives
 * ("Decide on the device"):
 *
 * <ol>
 *   <li>reject unsupported sessions: split or multi-package sessions, and streaming
 *       (incremental) sessions, for which this reference has no authenticated APK identity to
 *       match cached evidence against;</li>
 *   <li>fix the facts about the staged APK (digest, signing certificate, label) and the
 *       participating notaries: the required developer-verification notary unless exempt, plus
 *       the enabled ones;</li>
 *   <li>with no participants left after an exemption, allow this verification step; with no
 *       notaries at all, ask the user;</li>
 *   <li>with only the developer-verification notary, apply the ADV-only baseline: registered
 *       proceeds, anything else is rejected with a report;</li>
 *   <li>otherwise collect assessments ({@link Assessor}), apply {@link Policy} and answer the
 *       system directly on ALLOW or BLOCK, or open {@link ReviewActivity} for at most ten
 *       minutes and let the user's explicit choice answer. A review that cannot open or does
 *       not conclude in time rejects.</li>
 * </ol>
 *
 * Exactly one decision is submitted per verification ID, and the system's own completion is
 * honoured immediately.
 */
public class VerificationService extends Service {
    static final String EXTRA_ID = "id";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_VERSION = "version";
    static final String EXTRA_INSTALLER = "installer";
    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_INCREMENTAL = "incremental";
    static final String EXTRA_DEADLINE = "deadline";

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Verification IDs the system has not yet concluded. Decisions are only sent for these. */
    private final Set<Integer> mPending = new HashSet<>();
    private Assessor mAssessor;

    private final BroadcastReceiver mVerified = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int id = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_ID, -1);
            int result = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_RESULT, 0);
            Log.i(VerificationReceiver.TAG, "PACKAGE_VERIFIED id=" + id + " result="
                    + VerificationReceiver.codeName(result));
            // Close a review that may still be open for this id (for example after a system timeout).
            sendBroadcast(new Intent(VerificationReceiver.ACTION_CONCLUDED).setPackage(getPackageName())
                    .putExtra(PackageManager.EXTRA_VERIFICATION_ID, id));
            settle(id);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mAssessor = new Assessor(this, mExecutor);
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_VERIFIED);
        try {
            filter.addDataType("application/vnd.android.package-archive");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new IllegalStateException(e);
        }
        registerReceiver(mVerified, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mVerified);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        final int id = intent.getIntExtra(EXTRA_ID, -1);
        final long deadlineAt = intent.getLongExtra(EXTRA_DEADLINE,
                SystemClock.elapsedRealtime() + VerificationReceiver.DECISION_TIMEOUT_MS);
        synchronized (mPending) { mPending.add(id); }
        // If the acknowledgement never comes, do not linger past the system's own deadline.
        mHandler.postDelayed(() -> settle(id), deadlineAt - SystemClock.elapsedRealtime() + 30_000);
        mExecutor.execute(() -> {
            try {
                verify(id, intent, deadlineAt);
            } catch (Throwable t) {
                Log.e(VerificationReceiver.TAG, "verification failed; rejecting id=" + id, t);
                decide(id, false, "internal failure");
            }
        });
        return START_NOT_STICKY;
    }

    /** Forgets a verification once the system has concluded it; stops when none is pending. */
    private void settle(int id) {
        boolean idle;
        synchronized (mPending) { mPending.remove(id); idle = mPending.isEmpty(); }
        if (idle) mHandler.post(this::stopSelf);
    }

    /** Submits the one decision for a live verification; a no-op once the system concluded it. */
    private boolean decide(int id, boolean allow, String how) {
        synchronized (mPending) {
            if (!mPending.contains(id)) {
                Log.i(VerificationReceiver.TAG, "id=" + id + " already concluded; not sending "
                        + (allow ? "ALLOW" : "REJECT") + " (" + how + ")");
                return false;
            }
        }
        Log.i(VerificationReceiver.TAG, "id=" + id + " verifyPendingInstall("
                + (allow ? "ALLOW" : "REJECT") + ") by " + how);
        try {
            getPackageManager().verifyPendingInstall(id,
                    allow ? PackageManager.VERIFICATION_ALLOW : PackageManager.VERIFICATION_REJECT);
            return true;
        } catch (Exception e) {
            Log.e(VerificationReceiver.TAG, "id=" + id + " verifyPendingInstall failed", e);
            return false;
        }
    }

    private void verify(int id, Intent intent, long deadlineAt) {
        final PackageManager pm = getPackageManager();
        final boolean incremental = intent.getBooleanExtra(EXTRA_INCREMENTAL, false);
        final String installer = intent.getStringExtra(EXTRA_INSTALLER);
        final String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        final long version = intent.getLongExtra(EXTRA_VERSION, -1);
        final Uri stage = intent.getParcelableExtra(EXTRA_STAGE, Uri.class);

        // 1. Unsupported sessions are rejected before anything is read or asked.
        if (incremental) {
            ApkFacts facts = ApkFacts.streaming(pkg, version, installer, stage);
            Log.w(VerificationReceiver.TAG, "id=" + id + " streaming install: no authenticated APK identity; rejecting");
            conclude(id, facts, new ArrayList<>(), false, facts.unsupported
                    + " Install it again with a complete APK (for adb: --no-incremental) to have it assessed.",
                    "streaming install");
            return;
        }
        ApkFacts facts = ApkFacts.gather(pm, pkg, version, installer, stage);
        if (facts.unsupported != null) {
            Log.w(VerificationReceiver.TAG, "id=" + id + " unsupported session: " + facts.unsupported);
            conclude(id, facts, new ArrayList<>(), false, facts.unsupported, "unsupported session");
            return;
        }

        // 2. The configuration and the facts.
        ProviderRegistry registry = ProviderRegistry.discover(this);
        ProviderRegistry.Active active = registry.active(this, installer);
        Log.i(VerificationReceiver.TAG, "id=" + id + " label=\"" + facts.label + "\" apk sha256="
                + facts.apkSha256 + " signer sha256=" + facts.signerSha256
                + " installed notaries=" + ProviderRegistry.describe(registry.installed));

        // 3. Nothing to check.
        if (active.providers.isEmpty()) {
            if (active.advExempt) {
                Log.i(VerificationReceiver.TAG, "id=" + id + " no checks remain after the ADV exemption; allowing");
                decide(id, true, "no checks remain");
                return;
            }
            Policy.Decision none = Policy.decide(new ArrayList<>());
            Log.i(VerificationReceiver.TAG, "id=" + id + " policy=" + none.outcome + " (no notaries) " + none.headline);
            review(id, facts, new ArrayList<>(), none, deadlineAt);
            return;
        }

        // 4./5. Ask the participants.
        Assessor.Result result = mAssessor.assess(id, active.providers, facts, installer, deadlineAt);
        List<Assessment> assessments = result.assessments;

        if (active.advOnly()) {
            // The ADV-only baseline: no community review. Registered proceeds; an unregistered or
            // mismatched developer is rejected (the advanced flow lies outside this attempt), and
            // so is a check that could not be completed, with a report saying why.
            Assessment adv = assessments.get(0);
            boolean allow = adv.verdict == Assessment.Verdict.APPROVE;
            String headline = allow ? "The developer is registered." : adv.verdict.denies()
                    ? "The developer is not registered for this app. Installing it requires Google's advanced flow."
                    : "Developer registration could not be checked (" + adv.verdictLabel().toLowerCase() + ").";
            Log.i(VerificationReceiver.TAG, "id=" + id + " policy=" + (allow ? "ALLOW" : "BLOCK") + " (ADV-only baseline) " + headline);
            conclude(id, facts, assessments, allow, headline, "ADV-only baseline");
            return;
        }

        Policy.Decision decision = Policy.decide(assessments);
        Log.i(VerificationReceiver.TAG, "id=" + id + " policy=" + decision.outcome + " (" + decision.tally()
                + ") " + decision.headline);
        if (decision.outcome != Policy.Outcome.REVIEW) {
            conclude(id, facts, assessments, decision.outcome == Policy.Outcome.ALLOW, decision.headline, "policy");
            return;
        }
        review(id, facts, assessments, decision, deadlineAt);
    }

    /** Opens the review for at most ten minutes, ending before the deadline; rejects otherwise. */
    private void review(int id, ApkFacts facts, List<Assessment> assessments, Policy.Decision decision, long deadlineAt) {
        long until = Math.min(SystemClock.elapsedRealtime() + VerificationReceiver.REVIEW_TIMEOUT_MS, deadlineAt - 60_000);
        Log.i(VerificationReceiver.TAG, "id=" + id + " asking the user (review ends in "
                + (until - SystemClock.elapsedRealtime()) / 1000 + " s)");
        Intent ask = ReviewActivity.ask(this, id, facts, assessments, decision, until);
        try {
            startActivity(ask);
        } catch (Exception e) {
            // Specification: if the activity cannot open, post a notification the user can tap
            // while the install waits.
            Log.w(VerificationReceiver.TAG, "id=" + id + " review activity could not open: " + e);
            Notifications.post(this, id, "Review " + facts.label + " before it is installed",
                    decision.headline + " Tap to review.", ask);
        }
        mHandler.postDelayed(() -> {
            if (decide(id, false, "review deadline")) {
                sendBroadcast(new Intent(VerificationReceiver.ACTION_CONCLUDED).setPackage(getPackageName())
                        .putExtra(PackageManager.EXTRA_VERIFICATION_ID, id));
                String title = facts.label + " was not installed";
                String text = "The review was not completed in time. " + decision.headline;
                Notifications.post(this, id, title, text,
                        ReviewActivity.report(this, facts, assessments, title, text, false));
            }
        }, until - SystemClock.elapsedRealtime());
    }

    /** An automatic outcome: decide, then report, since the installer shows only a generic error. */
    private void conclude(int id, ApkFacts facts, List<Assessment> assessments, boolean allow,
                          String headline, String how) {
        if (!decide(id, allow, how)) return;
        String title = facts.label + (allow ? " was installed" : " was blocked");
        Notifications.post(this, id, title, headline,
                ReviewActivity.report(this, facts, assessments, title, headline, allow));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

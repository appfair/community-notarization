package org.appfair.notarization;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * Entry point. For every install session, PackageManagerService
 * (frameworks/base/services/core/java/com/android/server/pm/VerifyingSession.java) holds the
 * install and sends ACTION_PACKAGE_NEEDS_VERIFICATION to the required verifier, which is
 * discovered at boot among system receivers holding PACKAGE_VERIFICATION_AGENT. This is the
 * package verification hook that has existed since API 14 and that Play Protect uses on
 * certified devices; the coordinator is that verifier on this image.
 *
 * The receiver requests the timeout extension immediately (specification, "Handle short
 * deadlines and failures": eleven minutes, rejection as the timeout result) and hands the work
 * to {@link VerificationService} with one monotonic deadline. The answer reaches the system
 * through {@link PackageManager#verifyPendingInstall(int, int)} with ALLOW or REJECT, at most
 * once per verification ID.
 */
public class VerificationReceiver extends BroadcastReceiver {
    public static final String TAG = "CommunityNotarization";

    /** The extension requested before anything else; the system caps extensions at one hour. */
    static final long DECISION_TIMEOUT_MS = 11 * 60 * 1000;
    /** A review lasts at most this long and ends before the deadline. */
    static final long REVIEW_TIMEOUT_MS = 10 * 60 * 1000;

    /** Internal broadcast telling an open review dialog that the system already concluded. */
    static final String ACTION_CONCLUDED = "org.appfair.notarization.CONCLUDED";

    // These extras are @hide in the public SDK; values from frameworks/base PackageManager.java.
    static final String EXTRA_VERIFICATION_PACKAGE_NAME = "android.content.pm.extra.VERIFICATION_PACKAGE_NAME";
    static final String EXTRA_VERIFICATION_LONG_VERSION_CODE = "android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE";
    static final String EXTRA_VERIFICATION_INSTALLER_PACKAGE = "android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE";
    static final String EXTRA_VERIFICATION_INSTALLER_UID = "android.content.pm.extra.VERIFICATION_INSTALLER_UID";
    static final String EXTRA_VERIFICATION_INSTALL_FLAGS = "android.content.pm.extra.VERIFICATION_INSTALL_FLAGS";
    static final String EXTRA_DATA_LOADER_TYPE = "android.content.pm.extra.DATA_LOADER_TYPE";
    static final int DATA_LOADER_TYPE_INCREMENTAL = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final int id = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_ID, -1);

        if (Intent.ACTION_PACKAGE_VERIFIED.equals(action)) {
            int result = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_RESULT, 0);
            Log.i(TAG, "PACKAGE_VERIFIED id=" + id + " result=" + codeName(result));
            context.sendBroadcast(new Intent(ACTION_CONCLUDED).setPackage(context.getPackageName())
                    .putExtra(PackageManager.EXTRA_VERIFICATION_ID, id));
            return;
        }
        if (!Intent.ACTION_PACKAGE_NEEDS_VERIFICATION.equals(action)) return;

        // One monotonic deadline covers identity checks, assessment, review and restarts.
        final long deadlineAt = SystemClock.elapsedRealtime() + DECISION_TIMEOUT_MS;
        // The system's default budget is 10 s and expires to REJECT. Ask for the extension first.
        context.getPackageManager().extendVerificationTimeout(id,
                PackageManager.VERIFICATION_REJECT, DECISION_TIMEOUT_MS);

        String pkg = intent.getStringExtra(EXTRA_VERIFICATION_PACKAGE_NAME);
        long version = intent.getLongExtra(EXTRA_VERIFICATION_LONG_VERSION_CODE, -1);
        String installer = intent.getStringExtra(EXTRA_VERIFICATION_INSTALLER_PACKAGE);
        int installerUid = intent.getIntExtra(EXTRA_VERIFICATION_INSTALLER_UID, -1);
        int installFlags = intent.getIntExtra(EXTRA_VERIFICATION_INSTALL_FLAGS, 0);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int loader = intent.getIntExtra(EXTRA_DATA_LOADER_TYPE, 0);
        boolean incremental = loader == DATA_LOADER_TYPE_INCREMENTAL;

        Log.i(TAG, "PACKAGE_NEEDS_VERIFICATION id=" + id + " session=" + sessionId + " pkg=" + pkg
                + " version=" + version + " installer=" + installer + " (uid " + installerUid + ")"
                + " flags=0x" + Integer.toHexString(installFlags) + (incremental ? " streaming" : "")
                + " stage=" + intent.getData());
        Log.d(TAG, "extras: " + describe(intent.getExtras()));

        Intent work = new Intent(context, VerificationService.class)
                .putExtra(VerificationService.EXTRA_ID, id)
                .putExtra(VerificationService.EXTRA_PACKAGE, pkg)
                .putExtra(VerificationService.EXTRA_VERSION, version)
                .putExtra(VerificationService.EXTRA_INSTALLER, installer)
                .putExtra(VerificationService.EXTRA_STAGE, intent.getData())
                .putExtra(VerificationService.EXTRA_INCREMENTAL, incremental)
                .putExtra(VerificationService.EXTRA_DEADLINE, deadlineAt);
        try {
            context.startService(work);
        } catch (Exception e) {
            Log.e(TAG, "could not start verification service; rejecting id=" + id, e);
            context.getPackageManager().verifyPendingInstall(id, PackageManager.VERIFICATION_REJECT);
        }
    }

    static String codeName(int code) {
        switch (code) {
            case PackageManager.VERIFICATION_ALLOW: return "ALLOW";
            case PackageManager.VERIFICATION_REJECT: return "REJECT";
            default: return "code " + code;
        }
    }

    private static String describe(Bundle extras) {
        if (extras == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (String key : extras.keySet()) {
            if (sb.length() > 1) sb.append(", ");
            Object v = extras.get(key);
            sb.append(key.replace("android.content.pm.extra.", "")).append('=').append(v);
        }
        return sb.append('}').toString();
    }
}

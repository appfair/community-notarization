package org.appfair.notarization.provider;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for a provider app's bound service. Subclasses implement {@link #assess} with
 * their own logic, data and network client; this class does the contract work:
 *
 * <ul>
 *   <li>authenticates the caller: only a holder of PACKAGE_VERIFICATION_AGENT (the platform's
 *       verifier, which on this image is the coordinator) may ask;</li>
 *   <li>runs assessments off the Binder thread and answers through the callback once;</li>
 *   <li>honours {@code cancel};</li>
 *   <li>closes the APK descriptor when one was attached;</li>
 *   <li>applies the demonstration overrides of {@link NotaryContract#OVERRIDE_SETTING_PREFIX},
 *       so that scripted scenarios can make a real provider time out, crash, go offline or
 *       answer invalidly without changing its code.</li>
 * </ul>
 */
public abstract class NotaryService extends Service {
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Set<String> mCancelled = Collections.synchronizedSet(new HashSet<>());

    /** The provider ID declared in the manifest metadata. */
    protected abstract String providerId();

    /**
     * Produces the assessment. Runs on a worker thread. {@code request.apk}, when present, is
     * open for reading and will be closed by the caller. Throwing makes the provider answer
     * nothing (the coordinator records a timeout or, if the process dies, a crash).
     */
    protected abstract NotaryAssessment assess(NotaryRequest request, DemoOverride override) throws Exception;

    /** Thrown by {@link #assess} when the provider cannot answer now: reported as unavailable. */
    public static final class Unavailable extends Exception {
        public Unavailable(String explanation) {
            super(explanation);
        }
    }

    /** Parsed demonstration override; {@link #none} when the setting is absent. */
    public static final class DemoOverride {
        public final String verdict, reason;
        public final long delayMs;
        public final boolean silent, crash, invalid, offline;

        DemoOverride(JSONObject o) {
            verdict = o.optString("verdict", null);
            reason = o.optString("reason", null);
            delayMs = o.optLong("delay_ms", 0);
            silent = o.optBoolean("silent", false);
            crash = o.optBoolean("crash", false);
            invalid = o.optBoolean("invalid", false);
            offline = o.optBoolean("offline", false);
        }

        static DemoOverride none() {
            return new DemoOverride(new JSONObject());
        }

        public boolean overridesVerdict() {
            return verdict != null;
        }
    }

    protected String tag() {
        return "Notary." + NotaryContract.shortId(providerId());
    }

    protected DemoOverride currentOverride() {
        String json = Settings.Global.getString(getContentResolver(),
                NotaryContract.OVERRIDE_SETTING_PREFIX + NotaryContract.shortId(providerId()));
        if (json == null || json.trim().isEmpty()) return DemoOverride.none();
        try {
            return new DemoOverride(new JSONObject(json));
        } catch (Exception e) {
            Log.w(tag(), "ignoring malformed override: " + e);
            return DemoOverride.none();
        }
    }

    private final INotaryService.Stub mBinder = new INotaryService.Stub() {
        @Override
        public void assess(NotaryRequest request, INotaryCallback callback) {
            // The platform already enforced android:permission on the service; check again here
            // so the contract holds even for a provider that forgot the manifest attribute.
            if (checkCallingPermission(NotaryContract.COORDINATOR_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag(), "rejecting request from uid " + getCallingUid() + ": not the platform verifier");
                closeQuietly(request);
                throw new SecurityException("caller is not the package verifier");
            }
            Log.i(tag(), "request " + request);
            mExecutor.execute(() -> run(request, callback));
        }

        @Override
        public void cancel(String requestId) {
            Log.i(tag(), "cancel " + requestId);
            mCancelled.add(requestId);
        }
    };

    private void run(NotaryRequest request, INotaryCallback callback) {
        try {
            DemoOverride override = currentOverride();
            if (override.delayMs > 0) {
                Log.i(tag(), "override: delaying " + override.delayMs + " ms");
                Thread.sleep(override.delayMs);
            }
            if (override.crash) {
                Log.w(tag(), "override: crashing the provider process");
                Process.killProcess(Process.myPid());
            }
            if (override.silent) {
                Log.w(tag(), "override: never answering " + request.requestId);
                return;
            }
            NotaryAssessment a;
            try {
                a = assess(request, override);
            } catch (Unavailable u) {
                Log.w(tag(), "unavailable for " + request.requestId + ": " + u.getMessage());
                if (!mCancelled.remove(request.requestId)) callback.onUnavailable(request.requestId, u.getMessage());
                return;
            }
            if (override.overridesVerdict()) {
                a = NotaryAssessment.answer(providerId(), request, override.verdict, "override",
                        override.reason != null ? override.reason : "Scripted verdict for the demonstration.");
            }
            if (override.invalid) {
                Log.w(tag(), "override: answering for the wrong app");
                a = a.withPackageName("com.example.other");
            }
            if (mCancelled.remove(request.requestId)) {
                Log.i(tag(), "request " + request.requestId + " was cancelled; dropping " + a);
                return;
            }
            Log.i(tag(), "answer " + a);
            callback.onAssessment(a);
        } catch (RemoteException e) {
            Log.w(tag(), "coordinator went away before the answer: " + e);
        } catch (Throwable t) {
            Log.e(tag(), "assessment failed for " + request.requestId, t);
        } finally {
            closeQuietly(request);
        }
    }

    private static void closeQuietly(NotaryRequest request) {
        if (request != null && request.apk != null) {
            try { request.apk.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return NotaryContract.ACTION_SERVICE.equals(intent.getAction()) ? mBinder : null;
    }
}

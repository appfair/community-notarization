package org.appfair.notarization;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.appfair.notarization.provider.INotaryCallback;
import org.appfair.notarization.provider.INotaryService;
import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bound-service adapter (specification, "Bound service"): binds to the explicit component
 * a provider declared, after the registry confirmed the package and signer, hands over the
 * request with a callback, waits at most the deadline, cancels, and unbinds. The provider's
 * process dying is reported as CRASHED, a refused or failed binding as UNAVAILABLE, an answer
 * that fails {@link Assessor#validate} as INVALID. Duplicate or late answers are ignored.
 */
final class BinderAdapter {
    private static final class Died extends Exception { }
    private static final class Refused extends Exception {
        Refused(String m) { super(m); }
    }

    static Assessor.Live assess(Context context, ProviderRegistry.Provider p, NotaryRequest request, ApkFacts facts) {
        long t0 = System.currentTimeMillis();
        if (!ProviderRegistry.isInstalled(context.getPackageManager(), p.packageName)) {
            return Assessor.Live.problem(p, Assessment.Verdict.UNAVAILABLE, "The provider app is not installed.", t0);
        }
        CompletableFuture<NotaryAssessment> result = new CompletableFuture<>();
        AtomicReference<INotaryService> service = new AtomicReference<>();
        INotaryCallback.Stub callback = new INotaryCallback.Stub() {
            @Override
            public void onAssessment(NotaryAssessment assessment) {
                if (!result.complete(assessment)) {
                    Log.w(VerificationReceiver.TAG, p.id + ": ignoring a duplicate or late answer");
                }
            }

            @Override
            public void onUnavailable(String requestId, String explanation) {
                if (!request.requestId.equals(requestId)
                        || !result.completeExceptionally(new Refused(explanation == null ? "" : explanation))) {
                    Log.w(VerificationReceiver.TAG, p.id + ": ignoring a duplicate or late answer");
                }
            }
        };
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    INotaryService s = INotaryService.Stub.asInterface(binder);
                    service.set(s);
                    s.assess(request, callback);
                } catch (SecurityException e) {
                    result.completeExceptionally(new Refused("The provider rejected the coordinator: " + e.getMessage()));
                } catch (RemoteException e) {
                    result.completeExceptionally(new Died());
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                result.completeExceptionally(new Died());
            }

            @Override
            public void onBindingDied(ComponentName name) {
                result.completeExceptionally(new Died());
            }

            @Override
            public void onNullBinding(ComponentName name) {
                result.completeExceptionally(new Refused("The provider service refused the binding."));
            }
        };

        Intent intent = new Intent(NotaryContract.ACTION_SERVICE).setComponent(p.service);
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            try { context.unbindService(connection); } catch (Exception ignored) { }
            return Assessor.Live.problem(p, Assessment.Verdict.UNAVAILABLE,
                    "The provider service " + p.service.flattenToShortString() + " could not be bound.", t0);
        }
        try {
            NotaryAssessment a = result.get(NotaryContract.DEADLINE_MS, TimeUnit.MILLISECONDS);
            String error = Assessor.validate(p, request, facts, a, false);
            if (error != null) {
                Log.w(VerificationReceiver.TAG, p.id + ": invalid response: " + error);
                return Assessor.Live.problem(p, Assessment.Verdict.INVALID, null, t0);
            }
            return Assessor.accept(p, a, t0);
        } catch (TimeoutException e) {
            INotaryService s = service.get();
            if (s != null) {
                try { s.cancel(request.requestId); } catch (Exception ignored) { }
            }
            return Assessor.Live.problem(p, Assessment.Verdict.TIMEOUT, null, t0);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Died) return Assessor.Live.problem(p, Assessment.Verdict.CRASHED, null, t0);
            return Assessor.Live.problem(p, Assessment.Verdict.UNAVAILABLE, cause.getMessage(), t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Assessor.Live.problem(p, Assessment.Verdict.TIMEOUT, null, t0);
        } finally {
            try { context.unbindService(connection); } catch (Exception ignored) { }
        }
    }

    private BinderAdapter() { }
}

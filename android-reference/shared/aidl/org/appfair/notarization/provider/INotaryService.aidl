package org.appfair.notarization.provider;

import org.appfair.notarization.provider.INotaryCallback;
import org.appfair.notarization.provider.NotaryRequest;

/**
 * The versioned bound-service contract of the specification ("Provider contract and trust
 * rules", "Bound service"): a request, a callback and a cancel call. The coordinator binds to
 * the explicit component a provider declares in its manifest metadata, after checking the
 * package and its signer; the service must in turn authenticate the coordinator (see
 * NotaryService, which only accepts callers holding PACKAGE_VERIFICATION_AGENT).
 */
interface INotaryService {
    /** Starts one assessment. The result arrives through the callback, on any thread. */
    void assess(in NotaryRequest request, INotaryCallback callback);

    /** The coordinator no longer wants the result (deadline passed, install cancelled). */
    void cancel(String requestId);
}

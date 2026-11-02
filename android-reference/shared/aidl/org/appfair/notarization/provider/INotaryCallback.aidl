package org.appfair.notarization.provider;

import org.appfair.notarization.provider.NotaryAssessment;

/**
 * Handed to a provider service with each request. The provider calls exactly one of these
 * methods once; the coordinator ignores duplicate and late results (specification, "Bound
 * service").
 */
oneway interface INotaryCallback {
    /** The provider's answer: one of the five verdicts, possibly as a signed attestation. */
    void onAssessment(in NotaryAssessment assessment);

    /**
     * The provider cannot answer right now (its own network or data source is unavailable).
     * This is not an abstention: the coordinator records it as a missing assessment and may
     * fall back to unexpired cached evidence for this provider and app.
     */
    void onUnavailable(String requestId, String explanation);
}

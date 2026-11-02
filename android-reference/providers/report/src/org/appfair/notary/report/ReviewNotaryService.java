package org.appfair.notary.report;

import org.appfair.notarization.provider.Attestation;
import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;
import org.appfair.notarization.provider.NotaryService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Simulates a consumer-review notary that answers from attestations it holds on the device.
 * Each answer is a portable attestation signed with the organisation's ECDSA P-256 key (the
 * private key is bundled here only because this is a simulation; a real provider would
 * carry attestations signed elsewhere). The coordinator verifies the signature against the
 * key declared in this app's notary metadata. A package without a review -> abstain, unsigned.
 */
public class ReviewNotaryService extends NotaryService {
    @Override
    protected String providerId() {
        return "org.appfair.notary.report";
    }

    @Override
    protected NotaryAssessment assess(NotaryRequest request, DemoOverride override) throws Exception {
        JSONObject review = lookup(request.packageName);
        if (review == null) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_ABSTAIN, "UNREVIEWED",
                    "This app has not been reviewed for consumer-friendliness.");
        }
        NotaryAssessment a = NotaryAssessment.answer(providerId(), request, review.getString("verdict"),
                review.optString("reasonCode", ""), review.optString("explanation", ""));
        PrivateKey key;
        try (InputStream in = getAssets().open("signing.key")) {
            key = Attestation.privateKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return Attestation.sign(a, key, false);
    }

    private JSONObject lookup(String packageName) throws Exception {
        try (InputStream in = getAssets().open("reviews.json")) {
            JSONArray list = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("reviews");
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (packageName.equals(r.optString("package"))) return r;
            }
        }
        return null;
    }
}

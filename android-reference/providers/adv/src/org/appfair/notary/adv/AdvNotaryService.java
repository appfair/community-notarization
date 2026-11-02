package org.appfair.notary.adv;

import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;
import org.appfair.notarization.provider.NotaryService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simulates Android Developer Verification as a notary: looks the package name and signing
 * certificate up in a developer registry. The real service does this with an online lookup
 * keyed on exactly these two values; here the registry is a bundled file and the "network"
 * is a 300 ms pause that the demonstration override can turn into an outage.
 *
 * Mapping to the specification's verdicts: registered to this certificate -> approve;
 * registered to another certificate or not registered -> disapprove; revoked -> revoke.
 * Registration cannot be checked -> unavailable (never a finding against the developer).
 */
public class AdvNotaryService extends NotaryService {
    @Override
    protected String providerId() {
        return "org.appfair.notary.adv";
    }

    @Override
    protected NotaryAssessment assess(NotaryRequest request, DemoOverride override) throws Exception {
        if (override.offline) {
            throw new Unavailable("Registration could not be checked: the verification service is unreachable. "
                    + "This is not a finding that the developer is unverified.");
        }
        Thread.sleep(300);   // the simulated round trip
        JSONObject entry = lookup(request.packageName);
        String signer = request.primarySigner();
        if (entry == null) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_DENY, "UNREGISTERED",
                    "This package and signing certificate are not registered to a verified developer.");
        }
        if (signer == null || !signer.equalsIgnoreCase(entry.optString("signer_sha256"))) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_DENY, "CERTIFICATE_MISMATCH",
                    "The package is registered with a different signing certificate.");
        }
        if ("revoked".equals(entry.optString("state"))) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_REVOKE, "REVOKED",
                    "The developer’s registration has been revoked.");
        }
        return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_APPROVE, "REGISTERED",
                "This package and signing certificate are registered to " + entry.optString("developer", "a verified developer") + ".");
    }

    private JSONObject lookup(String packageName) throws Exception {
        try (InputStream in = getAssets().open("registry.json")) {
            JSONArray devs = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("developers");
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.getJSONObject(i);
                if (packageName.equals(d.optString("package"))) return d;
            }
        }
        return null;
    }
}

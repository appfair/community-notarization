package org.appfair.notary.media;

import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;
import org.appfair.notarization.provider.NotaryService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simulates an age-appropriateness notary with an on-device ratings database: no network,
 * so it answers offline and during streaming installs. A rating of "age16" is advice that
 * requires the user's review; it is not an age check.
 */
public class RatingNotaryService extends NotaryService {
    @Override
    protected String providerId() {
        return "org.appfair.notary.media";
    }

    @Override
    protected NotaryAssessment assess(NotaryRequest request, DemoOverride override) throws Exception {
        JSONObject best = null;
        try (InputStream in = getAssets().open("ratings.json")) {
            JSONArray list = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("ratings");
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (!request.packageName.equals(r.optString("package"))) continue;
                if (r.has("version_code") && r.optLong("version_code") != request.versionCode) continue;
                if (best == null || r.has("version_code")) best = r;
            }
        }
        if (best == null) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_ABSTAIN, "UNRATED",
                    "No age-appropriateness review is available.");
        }
        return NotaryAssessment.answer(providerId(), request, best.getString("verdict"),
                best.optString("reasonCode", ""), best.optString("explanation", ""), best.optInt("minAge", 0));
    }
}

package org.appfair.notarization;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Set;

/**
 * The "system settings" page of the specification ("the user must separately approve the
 * notary and its data access in system settings"), as part of the coordinator because it is
 * the platform component on this image. It lists every installed app that declares a notary,
 * with the question it answers, the package and signer it binds to, how it is reached and
 * what data it reads. Switching one on records the accepted identity and data access;
 * switching it off forgets them. Either change clears cached evidence, because evidence is
 * bound to the trust configuration. The developer-verification notary cannot be switched off.
 */
public class SettingsActivity extends Activity {
    private ProviderStore mStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Notaries");
        mStore = new ProviderStore(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        ProviderRegistry registry = ProviderRegistry.discover(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView intro = new TextView(this);
        intro.setText("Apps are checked with the notaries you enable before they are installed. Each notary "
                + "answers one question about the exact file. Enabling one trusts the app below, as "
                + "currently installed and signed, to answer it.");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setTextColor(0xFF424242);
        list.addView(intro);

        if (registry.installed.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("\nNo installed app declares a notary.");
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            list.addView(none);
        }
        for (ProviderRegistry.Provider p : registry.installed) list.addView(row(p));

        TextView footer = new TextView(this);
        footer.setText("\nCached assessments: " + new EvidenceStore(this).count()
                + "\nEnabled: " + ProviderRegistry.enabledIds(this));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(0xFF757575);
        list.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        setContentView(scroll);
    }

    private LinearLayout row(ProviderRegistry.Provider p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, 0);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(p.name + (p.required ? "  (required)" : ""));
        name.setTypeface(null, Typeface.BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(0xFF212121);
        TextView detail = new TextView(this);
        String state = p.identityChanged()
                ? "\nThe app's signing identity changed since it was enabled: disabled until enabled again."
                : "";
        detail.setText(p.question + "\n" + p.packageName + " · signer " + ApkFacts.abbreviate(p.signerSha256)
                + "\n" + p.routeText() + "\n" + p.dataText() + state);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(p.identityChanged() ? ReviewActivity.RED : 0xFF616161);
        text.addView(name);
        text.addView(detail);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(p.required || (p.enabled && !p.identityChanged()));
        sw.setEnabled(!p.required);
        sw.setContentDescription("enable " + p.id);
        sw.setOnCheckedChangeListener((v, on) -> toggle(p, on));
        row.addView(sw);
        return row;
    }

    private void toggle(ProviderRegistry.Provider p, boolean on) {
        Set<String> ids = ProviderRegistry.enabledIds(this);
        if (on) {
            ids.add(p.id);
            mStore.accept(p.id, p.packageName, p.signerSha256, p.wantsApk());
        } else {
            ids.remove(p.id);
            mStore.forget(p.id);
        }
        ProviderRegistry.setEnabledIds(this, ids);
        new EvidenceStore(this).clear();   // specification: changing trust settings clears evidence
        Log.i(VerificationReceiver.TAG, "settings: " + p.id + (on ? " enabled (" : " disabled (")
                + p.packageName + ", signer " + ApkFacts.abbreviate(p.signerSha256) + "); evidence cleared");
        render();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

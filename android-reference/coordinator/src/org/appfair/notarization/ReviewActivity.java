package org.appfair.notarization;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The review dialog the specification describes ("Decide on the device"): it names each
 * notary and shows its status, reason and evidence source as text, then offers Install and
 * Cancel. Install applies to this attempt only; Cancel or dismissal rejects it. Nothing is
 * remembered: the next install of the same file asks again. The same screen, in report mode,
 * shows an automatic outcome from a notification with a Close button.
 *
 * The dialog is an activity started from the background by the verifier (it holds
 * START_ACTIVITIES_FROM_BACKGROUND) and appears over whatever installer UI is on screen.
 */
public class ReviewActivity extends Activity {
    private static final String EXTRA_MODE = "mode", EXTRA_ID = "id", EXTRA_PACKAGE = "package",
            EXTRA_LABEL = "label", EXTRA_VERSION = "version", EXTRA_APK_PATH = "apk",
            EXTRA_SHA256 = "sha256", EXTRA_SIGNER = "signer", EXTRA_INSTALLER = "installer",
            EXTRA_ASSESSMENTS = "assessments", EXTRA_HEADLINE = "headline", EXTRA_TITLE = "title",
            EXTRA_ALLOWED = "allowed", EXTRA_UNTIL = "until";

    static final int GREEN = 0xFF2E7D32, RED = 0xFFC62828, GREY = 0xFF607D8B, AMBER = 0xFFEF6C00,
            BLUE = 0xFF1E5AA8, PURPLE = 0xFF6A1B9A;

    /** @param until when the review ends ({@link SystemClock#elapsedRealtime}); the install is rejected then */
    static Intent ask(Context context, int id, ApkFacts facts, List<Assessment> assessments, Policy.Decision decision,
                      long until) {
        return base(context, facts, assessments).putExtra(EXTRA_MODE, "ask").putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_HEADLINE, decision.headline).putExtra(EXTRA_UNTIL, until)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent report(Context context, ApkFacts facts, List<Assessment> assessments, String title,
                         String headline, boolean allowed) {
        return base(context, facts, assessments).putExtra(EXTRA_MODE, "report").putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADLINE, headline).putExtra(EXTRA_ALLOWED, allowed)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    private static Intent base(Context context, ApkFacts facts, List<Assessment> assessments) {
        return new Intent(context, ReviewActivity.class)
                .setAction("org.appfair.notarization.SHOW/" + facts.apkSha256)   // distinct PendingIntents
                .putExtra(EXTRA_PACKAGE, facts.packageName).putExtra(EXTRA_LABEL, facts.label)
                .putExtra(EXTRA_VERSION, facts.versionCode).putExtra(EXTRA_APK_PATH, facts.apkPath)
                .putExtra(EXTRA_SHA256, facts.apkSha256).putExtra(EXTRA_SIGNER, facts.signerSha256)
                .putExtra(EXTRA_INSTALLER, facts.installer)
                .putExtra(EXTRA_ASSESSMENTS, Assessment.toJsonArray(assessments));
    }

    private boolean mAsk;
    private int mId = -1;
    private boolean mAnswered;
    private String mPackage, mLabel, mSha256;
    private List<Assessment> mAssessments;
    private AlertDialog mDialog;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mExpire = () -> {
        if (mAnswered) return;
        Log.i(VerificationReceiver.TAG, "id=" + mId + " review deadline reached; rejecting");
        answer(false);
        finish();
    };

    private final BroadcastReceiver mConcluded = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_ID, -2) == mId && !mAnswered) {
                Log.i(VerificationReceiver.TAG, "id=" + mId + " concluded by the system while the review was open");
                mAnswered = true;
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        mAsk = "ask".equals(in.getStringExtra(EXTRA_MODE));
        mId = in.getIntExtra(EXTRA_ID, -1);
        mPackage = in.getStringExtra(EXTRA_PACKAGE);
        mLabel = in.getStringExtra(EXTRA_LABEL);
        mSha256 = in.getStringExtra(EXTRA_SHA256);
        mAssessments = Assessment.fromJsonArray(in.getStringExtra(EXTRA_ASSESSMENTS));
        Policy.Decision decision = Policy.decide(mAssessments);

        if (mAsk) {
            registerReceiver(mConcluded, new IntentFilter(VerificationReceiver.ACTION_CONCLUDED),
                    Context.RECEIVER_NOT_EXPORTED);
            long until = in.getLongExtra(EXTRA_UNTIL, SystemClock.elapsedRealtime() + VerificationReceiver.REVIEW_TIMEOUT_MS);
            mHandler.postDelayed(mExpire, Math.max(0, until - SystemClock.elapsedRealtime()));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setCustomTitle(buildTitle(mAsk ? "Install " + mLabel + "?" : in.getStringExtra(EXTRA_TITLE)))
                .setView(buildBody(decision, in.getStringExtra(EXTRA_HEADLINE), in.getBooleanExtra(EXTRA_ALLOWED, false)));
        if (mAsk) {
            b.setPositiveButton("Install", (d, w) -> answer(true))
             .setNegativeButton("Cancel", (d, w) -> answer(false))
             .setOnDismissListener(d -> { answer(false); finish(); });   // back key etc.
        } else {
            b.setPositiveButton("Close", (d, w) -> finish()).setOnDismissListener(d -> finish());
        }
        mDialog = b.create();
        mDialog.setCanceledOnTouchOutside(false);
        mDialog.show();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacks(mExpire);
        if (mAsk) {
            try { unregisterReceiver(mConcluded); } catch (Exception ignored) { }
            answer(false);   // the activity went away without a choice: never leave the install hanging
        }
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        super.onDestroy();
    }

    /** Answers the system exactly once. */
    private void answer(boolean allow) {
        if (mAnswered) return;
        mAnswered = true;
        Log.i(VerificationReceiver.TAG, "id=" + mId + " user chose " + (allow ? "Install" : "Cancel")
                + " for " + mPackage);
        try {
            getPackageManager().verifyPendingInstall(mId,
                    allow ? PackageManager.VERIFICATION_ALLOW : PackageManager.VERIFICATION_REJECT);
        } catch (Exception e) {
            Log.e(VerificationReceiver.TAG, "verifyPendingInstall failed", e);
        }
    }

    // ---------------------------------------------------------------- view building

    private View buildTitle(String titleText) {
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(24), dp(20), dp(24), dp(8));

        ImageView icon = new ImageView(this);
        Drawable appIcon = ApkFacts.loadIcon(getPackageManager(), getIntent().getStringExtra(EXTRA_APK_PATH));
        if (appIcon == null) {
            try { appIcon = getPackageManager().getApplicationIcon(mPackage); } catch (Exception ignored) { }
        }
        if (appIcon == null) appIcon = getDrawable(R.drawable.ic_notary);
        icon.setImageDrawable(appIcon);
        title.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText(titleText);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t1.setTypeface(null, Typeface.BOLD);
        t1.setTextColor(0xFF212121);
        TextView t2 = new TextView(this);
        t2.setText(mPackage + "  ·  version " + getIntent().getLongExtra(EXTRA_VERSION, -1));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t2.setTextColor(0xFF757575);
        text.addView(t1);
        text.addView(t2);
        title.addView(text);
        return title;
    }

    private View buildBody(Policy.Decision decision, String headline, boolean allowed) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(4), dp(24), dp(8));

        TextView intro = new TextView(this);
        intro.setText(mAssessments.isEmpty()
                ? "No notaries are enabled on this device."
                : mAssessments.size() + (mAssessments.size() == 1 ? " enabled notary was" : " enabled notaries were")
                        + " asked about this exact file:");
        intro.setTextColor(0xFF424242);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.addView(intro);

        for (int i = 0; i < mAssessments.size(); i++) body.addView(buildRow(i, mAssessments.get(i)));

        if (!mAssessments.isEmpty()) {
            TextView tally = new TextView(this);
            tally.setText(decision.tally());
            tally.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tally.setTypeface(null, Typeface.BOLD);
            tally.setTextColor(0xFF616161);
            tally.setPadding(0, dp(12), 0, dp(4));
            body.addView(tally);
        }

        int fill, stroke;
        if (mAsk) {
            if (decision.denials > 0) { fill = 0xFFFFEBEE; stroke = RED; }
            else if (decision.approvals == 0) { fill = 0xFFFFF3E0; stroke = AMBER; }
            else { fill = 0xFFE3F2FD; stroke = BLUE; }
        } else {
            fill = allowed ? 0xFFE8F5E9 : 0xFFFFEBEE; stroke = allowed ? GREEN : RED;
        }
        LinearLayout banner = new LinearLayout(this);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setBackground(rounded(fill, stroke));
        TextView bannerText = new TextView(this);
        bannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bannerText.setTypeface(null, Typeface.BOLD);
        bannerText.setTextColor(stroke);
        bannerText.setText(headline);
        banner.addView(bannerText);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        body.addView(banner, blp);

        TextView identity = new TextView(this);
        identity.setText("APK SHA-256 " + ApkFacts.abbreviate(mSha256) + "\nSigner SHA-256 "
                + ApkFacts.abbreviate(getIntent().getStringExtra(EXTRA_SIGNER))
                + (getIntent().getStringExtra(EXTRA_INSTALLER) != null
                    ? "\nInstaller " + getIntent().getStringExtra(EXTRA_INSTALLER) : ""));
        identity.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        identity.setTypeface(Typeface.MONOSPACE);
        identity.setTextColor(0xFF757575);
        identity.setPadding(0, dp(10), 0, 0);
        body.addView(identity);

        if (mAsk) {
            TextView note = new TextView(this);
            note.setText("Install applies to this attempt only. Cancel stops it. The review ends automatically after "
                    + "ten minutes.");
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            note.setTextColor(0xFF757575);
            note.setPadding(0, dp(8), 0, 0);
            body.addView(note);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        return scroll;
    }

    private View buildRow(int index, Assessment a) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(10), 0, 0);

        FrameLayout iconHolder = new FrameLayout(this);
        iconHolder.addView(statusIcon(a));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
        ilp.topMargin = dp(2);
        line.addView(iconHolder, ilp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(a.notaryName + " · " + a.verdictLabel());
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(0xFF212121);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TextView detail = new TextView(this);
        detail.setText((a.reason.isEmpty() ? "" : a.reason + "\n") + "Source: " + a.sourceText());
        detail.setTextColor(0xFF37474F);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.addView(name);
        text.addView(detail);
        line.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    /** A coloured circle with a glyph per verdict. */
    private View statusIcon(Assessment a) {
        int color; String glyph;
        switch (a.verdict) {
            case APPROVE: color = GREEN; glyph = "✓"; break;
            case DISAPPROVE: case REVOKE: color = RED; glyph = "✕"; break;
            case ABSTAIN: color = GREY; glyph = "–"; break;
            case ADVISORY: color = PURPLE; glyph = a.minAge > 0 ? String.valueOf(a.minAge) : "!"; break;
            case INVALID: color = RED; glyph = "!"; break;
            default: color = AMBER; glyph = "?";
        }
        TextView tv = new TextView(this);
        tv.setText(glyph);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, glyph.length() > 1 ? 13 : 18);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        tv.setBackground(circle);
        tv.setLayoutParams(new FrameLayout.LayoutParams(dp(32), dp(32)));
        return tv;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

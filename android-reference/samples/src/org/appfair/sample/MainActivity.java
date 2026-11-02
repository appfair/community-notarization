package org.appfair.sample;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** A trivial app: its only purpose is to be installed through the notarization flow. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Notarization Sample\n\nThis app was installed after the Community Notarization "
                + "coordinator reviewed it.");
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}

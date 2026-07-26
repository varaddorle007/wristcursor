/*
 * Copyright 2026 WristCursor
 */

package com.wristcursor.app.ui.input;

import android.os.Bundle;
import android.support.wearable.preference.WearablePreferenceActivity;

/** Hosts the full input-mode picker when it is opened from the air-mouse control. */
public class ModeSelectActivity extends WearablePreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAmbientEnabled();

        if (savedInstanceState == null) {
            startPreferenceFragment(new ModeSelectFragment(), false);
        }
    }
}

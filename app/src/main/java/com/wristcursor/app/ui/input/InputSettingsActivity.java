/*
 * Copyright 2026 WristCursor
 */

package com.wristcursor.app.ui.input;

import android.os.Bundle;
import android.support.wearable.preference.WearablePreferenceActivity;

/** Hosts input settings when they are opened directly from the air-mouse screen. */
public class InputSettingsActivity extends WearablePreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAmbientEnabled();

        if (savedInstanceState == null) {
            startPreferenceFragment(new InputSettingsFragment(), false);
        }
    }
}

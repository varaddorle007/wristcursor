/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wristcursor.app;

import android.app.Application;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.wristcursor.app.bluetooth.HidDataSender;
import com.wristcursor.app.input.SettingsUtil;
import com.wristcursor.app.input.SettingsUtil.SettingKey;

public class WristCursorApp extends Application {

    private HidDataSender hidDataSender;
    private SettingsUtil settingsUtil;

    private final LifecycleObserver lifecycleObserver =
            new LifecycleObserver() {
                @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
                public void onMoveToBackground() {
                    // Never drop a pending pair/connect when a system dialog (discoverable /
                    // pairing) covers the app — that was killing connections on Wear.
                    if (hidDataSender.isConnectingOrConnected()) {
                        return;
                    }
                    if (!settingsUtil.getBoolean(SettingKey.STAY_CONNECTED)) {
                        hidDataSender.requestConnect(null);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        hidDataSender = HidDataSender.getInstance();
        settingsUtil = new SettingsUtil(this);
        // Force stay-connected for this install so old false prefs don't kill HID.
        settingsUtil.setBoolean(SettingKey.STAY_CONNECTED, true);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(lifecycleObserver);
    }
}

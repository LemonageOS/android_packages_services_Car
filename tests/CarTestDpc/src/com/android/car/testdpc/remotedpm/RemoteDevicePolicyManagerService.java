/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.testdpc.remotedpm;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * A service to facilitate cross-user calls to the other user's DevicePolicyManager methods
 *
 * <p> implements an IPC that binds to the same application on a different user to make cross-user
 * calls </p>
 */
public final class RemoteDevicePolicyManagerService extends Service {

    private static final String TAG = RemoteDevicePolicyManagerService.class.getSimpleName();

    private Binder mBinder;

    @Override
    public void onCreate() {
        mBinder = new RemoteDevicePolicyManagerServiceImpl(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}

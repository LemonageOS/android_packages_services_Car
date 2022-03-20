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

package com.google.android.car.kitchensink.backup;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.backup.BackupManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import java.util.Arrays;

public final class BackupAndRestoreFragment extends Fragment {

    private static final String TAG = BackupAndRestoreFragment.class.getSimpleName();
    private static final String TRANSPORT_DIR_NAME =
            "com.google.android.car.kitchensink.backup.KitchenSinkBackupTransport";

    private BackupManager mBackupManager;

    private Button mBackupButton;
    private Button mRestoreButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBackupManager = new BackupManager(getContext());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.backup_restore_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mBackupButton = view.findViewById(R.id.backup);
        mRestoreButton = view.findViewById(R.id.restore);

        mBackupButton.setOnClickListener((v) -> backup());
        mRestoreButton.setOnClickListener((v) -> restore());
    }


    private void backup() {
        boolean isEnabled = mBackupManager.isBackupEnabled();
        Log.v(TAG, "backup is enabled: " + isEnabled);
        if (!isEnabled) {
            mBackupManager.setBackupEnabled(true);
        }
        String[] allTransports = mBackupManager.listAllTransports();
        Log.v(TAG, "All transports: " + Arrays.toString(allTransports));
        String currentTransport = mBackupManager.getCurrentTransport();
        Log.v(TAG, "Current Transport: " + currentTransport);
        String selectedTransport = mBackupManager.selectBackupTransport(TRANSPORT_DIR_NAME);
        Log.v(TAG, "Selected transport: " + selectedTransport);

        StringBuilder sb = new StringBuilder();
        Arrays.stream(allTransports).forEach(t -> sb.append('\n').append(t));
        showMessage("Backup button clicked.\nAll transports: " + sb
                + "\nCurrent Transport: " + currentTransport);
    }

    private void restore() {
        showMessage("restore button clicked.");
    }

    private void showMessage(String pattern, Object... args) {
        String message = String.format(pattern, args);
        Log.v(TAG, "showMessage(): " + message);
        new AlertDialog.Builder(getContext()).setMessage(message).show();
    }
}


/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.cluster;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.cluster.ClusterHomeManager;
import android.car.cluster.ClusterState;
import android.car.cluster.IClusterHomeCallback;
import android.car.cluster.IClusterHomeService;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.Executor;

/** Unit tests for {@link ClusterHomeManager} */
@RunWith(MockitoJUnitRunner.class)
public final class ClusterHomeManagerUnitTest {
    @Mock
    private Car mCar;

    @Mock
    private IBinder mBinder;

    @Mock
    private IClusterHomeService.Stub mService;

    @Mock
    private ClusterHomeManager.ClusterHomeCallback mClusterHomeCallback;

    private ClusterHomeManager mClusterHomeManager;
    private final Executor mCurrentThreadExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Before
    public void setup() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mService);
        mClusterHomeManager = new ClusterHomeManager(mCar, mBinder);
    }

    @Test
    public void getClusterState_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        when(mService.getClusterState()).thenThrow(thrownException);

        ClusterState clusterState = mClusterHomeManager.getClusterState();

        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
        assertThat(clusterState).isNull();
    }

    @Test
    public void registerClusterHomeCallback_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doThrow(thrownException)
                .when(mService).registerCallback(any());

        mClusterHomeManager.registerClusterHomeCallback(mCurrentThreadExecutor,
                mClusterHomeCallback);

        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
    }

    @Test
    public void onNavigationStateChanged_callsCallbacks() throws Exception {
        byte[] newNavigationState = new byte[]{1};
        doAnswer(invocation -> {
            IClusterHomeCallback.Stub clusterHomeManagerHomeCallback =
                    (IClusterHomeCallback.Stub) invocation.getArgument(0);
            clusterHomeManagerHomeCallback.onNavigationStateChanged(newNavigationState);
            return null;
        }).when(mService).registerCallback(any());

        mClusterHomeManager.registerClusterHomeCallback(mCurrentThreadExecutor,
                mClusterHomeCallback);

        verify(mClusterHomeCallback).onNavigationState(eq(newNavigationState));
    }

    @Test
    public void reportState_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doThrow(thrownException)
                .when(mService).reportState(anyInt(), anyInt(), any(byte[].class));

        mClusterHomeManager.reportState(1, 1, new byte[]{1});

        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
    }

    @Test
    public void requestDisplay_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doThrow(thrownException).when(mService).requestDisplay(anyInt());

        mClusterHomeManager.requestDisplay(1);
        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
    }

    @Test
    public void startFixedActivityMode_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doThrow(thrownException)
                .when(mService).startFixedActivityModeAsUser(any(), any(), anyInt());

        boolean launchedAsFixedActivity =
                mClusterHomeManager.startFixedActivityModeAsUser(new Intent(), new Bundle(), 1);
        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
        assertThat(launchedAsFixedActivity).isFalse();
    }

    @Test
    public void stopFixedActivityMode_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doThrow(thrownException).when(mService).stopFixedActivityMode();

        mClusterHomeManager.stopFixedActivityMode();

        verify(mCar).handleRemoteExceptionFromCarService(thrownException);
    }

    @Test
    public void unregisterClusterHomeCallback_serviceFailure() throws Exception {
        RemoteException thrownException = new RemoteException();
        doNothing().when(mService).registerCallback(any());
        doThrow(thrownException).when(mService).unregisterCallback(any());
        mClusterHomeManager.registerClusterHomeCallback(mCurrentThreadExecutor,
                mClusterHomeCallback);

        mClusterHomeManager.unregisterClusterHomeCallback(mClusterHomeCallback);

        verifyNoMoreInteractions(mCar);
    }
}

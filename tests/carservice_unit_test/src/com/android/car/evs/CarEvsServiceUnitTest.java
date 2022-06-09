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

package com.android.car.evs;

import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE;
import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_ERROR;
import static android.car.evs.CarEvsManager.ERROR_BUSY;
import static android.car.evs.CarEvsManager.ERROR_NONE;
import static android.car.evs.CarEvsManager.ERROR_UNAVAILABLE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_ACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_INACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_REQUESTED;
import static android.car.evs.CarEvsManager.SERVICE_STATE_UNAVAILABLE;
import static android.car.evs.CarEvsManager.STREAM_EVENT_STREAM_STOPPED;

import static android.car.test.mocks.AndroidMockitoHelper.mockAmGetCurrentUser;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUserHandles;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmIsUserRunning;

import static com.android.car.CarLog.TAG_EVS;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsError;
import android.car.evs.CarEvsManager.CarEvsServiceState;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsManager.CarEvsStreamEvent;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.HardwareBuffer;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.view.Display;

import com.android.car.BuiltinPackageDependency;
import com.android.car.CarLocalServices;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceUtils;
import com.android.car.admin.NotificationHelper;
import com.android.car.evs.CarEvsService;
import com.android.car.hal.EvsHalService;
import com.android.car.internal.evs.EvsHalWrapper;
import com.android.car.evs.EvsHalWrapperImpl;
import com.android.car.systeminterface.SystemInterface;

import com.google.common.truth.Correspondence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * <p>This class contains unit tests for the {@link CarEvsService}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarEvsServiceUnitTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarEvsServiceUnitTest.class.getSimpleName();

    private static final String COMMAND_TO_USE_DEFAULT_CAMERA = "default";
    private static final String CAMERA_TO_USE = "/dev/video10";
    private static final String DEFAULT_CAMERA_ID = "/dev/default";
    private static final int INVALID_ARG = -1;
    private static final int SERVICE_STATE_UNKNOWN = -1;
    private static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";
    private static final CarPropertyValue<Integer> GEAR_SELECTION_PROPERTY_NEUTRAL =
        new CarPropertyValue<>(VehicleProperty.GEAR_SELECTION, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_NEUTRAL);
    private static final CarPropertyValue<Integer> GEAR_SELECTION_PROPERTY_REVERSE =
        new CarPropertyValue<>(VehicleProperty.GEAR_SELECTION, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_REVERSE);
    private static final CarPropertyValue<Integer> CURRENT_GEAR_PROPERTY_REVERSE =
        new CarPropertyValue<>(VehicleProperty.CURRENT_GEAR, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_REVERSE);

    @Mock private CarPropertyService mMockCarPropertyService;
    @Mock private ClassLoader mMockClassLoader;
    @Mock private Context mMockContext;
    @Mock private Context mMockBuiltinPackageContext;
    @Mock private DisplayManager mMockDisplayManager;
    @Mock private EvsHalService mMockEvsHalService;
    @Mock private EvsHalWrapperImpl mMockEvsHalWrapper;
    @Mock private PackageManager mMockPackageManager;
    @Mock private Resources mMockResources;

    @Captor private ArgumentCaptor<ICarPropertyEventListener> mGearSelectionListenerCaptor;
    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;

    private CarEvsService mCarEvsService;

    public CarEvsServiceUnitTest() {
        super(TAG_EVS);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ServiceManager.class)
            .spyStatic(Binder.class)
            .spyStatic(CarServiceUtils.class)
            .spyStatic(CarEvsService.class)
            .spyStatic(BuiltinPackageDependency.class);
    }

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageName()).thenReturn(SYSTEMUI_PACKAGE_NAME);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getString(com.android.car.R.string.config_evsRearviewCameraId))
                .thenReturn(DEFAULT_CAMERA_ID);
        when(mMockContext.getSystemService(DisplayManager.class)).thenReturn(mMockDisplayManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);

        when(mMockResources.getString(com.android.car.R.string.config_evsCameraActivity))
                .thenReturn("evsCameraActivity");
        when(mMockResources.getString(
                com.android.internal.R.string.config_systemUIServiceComponent))
                .thenReturn("com.android.systemui/com.android.systemui.SystemUIService");

        when(mMockBuiltinPackageContext.getClassLoader()).thenReturn(mMockClassLoader);
        doReturn(EvsHalWrapperImpl.class).when(mMockClassLoader)
                .loadClass(BuiltinPackageDependency.EVS_HAL_WRAPPER_CLASS);

        mockEvsHalService();
        mockEvsHalWrapper();
        doReturn(mMockEvsHalWrapper).when(() -> CarEvsService.createHalWrapper(any(), any()));

        // Get the property listener
        doNothing().when(mMockCarPropertyService)
                .registerListener(anyInt(), anyFloat(), mGearSelectionListenerCaptor.capture());

        mCarEvsService = spy(new CarEvsService(mMockContext, mMockBuiltinPackageContext,
                        mMockEvsHalService, mMockCarPropertyService));
        mCarEvsService.init();
    }

    @After
    public void tearDown() throws Exception {
        mCarEvsService = null;
    }

    @Test
    public void testIsSupported() throws Exception {
        assertThat(mCarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_REARVIEW)).isTrue();
        assertThat(mCarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW)).isFalse();
        assertThrows(IllegalArgumentException.class, () -> mCarEvsService.isSupported(INVALID_ARG));
    }

    @Test
    public void testSessionTokenGeneration() throws Exception {
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(UserHandle.USER_SYSTEM);
        assertThat(mCarEvsService.generateSessionToken()).isNotNull();

        int uid = Binder.getCallingUid();
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(uid);
        assertThat(mCarEvsService.generateSessionToken()).isNotNull();
    }

    @Test
    public void testGetCurrentStatus() {
        CarEvsStatus status = mCarEvsService.getCurrentStatus();
        assertThat(status.getServiceType()).isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(status.getState()).isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testRegisterAndUnregisterStatusListener() {
        EvsStatusListenerImpl testListener = new EvsStatusListenerImpl();
        mCarEvsService.registerStatusListener(testListener);
        mCarEvsService.unregisterStatusListener(testListener);
    }

    @Test
    public void testServiceInit() {
        when(mMockEvsHalWrapper.init()).thenReturn(false);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(2)).init();

        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(3)).init();

        when(mMockCarPropertyService.getProperty(anyInt(), anyInt())).thenReturn(null);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(4)).init();

        when(mMockCarPropertyService.getProperty(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_NEUTRAL);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(5)).init();
    }

    @Test
    public void testServiceRelease() {
        mCarEvsService.release();
        verify(mMockEvsHalWrapper).release();

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.release();
        verify(mMockCarPropertyService).unregisterListener(anyInt(), any());
    }

    @Test
    public void testOnEvent() {
        mCarEvsService.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW, /* on= */ true);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        mCarEvsService.onEvent(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW, /* on= */ true);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        mCarEvsService.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW, /* on= */ false);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        mCarEvsService.onEvent(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW, /* on= */ false);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testInvalidStreamCallback() {
        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = 0;
        mCarEvsService.setStreamCallback(null);
        mCarEvsService.onFrameEvent(bufferId, buffer);
        verify(mMockEvsHalWrapper).doneWithFrame(anyInt());

        // Nothing to verify from below line but added to increase the code coverage.
        mCarEvsService.onHalEvent(/* eventType= */ 0);
    }

    @Test
    public void testHalDeath() {
        mCarEvsService.onHalDeath();
        verify(mMockEvsHalWrapper, times(2)).connectToHalServiceIfNecessary();
    }

    @Test
    public void testTransitionToInactive() {
        // Transition from SERVICE_STATE_UNAVAILABLE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
        verify(mMockEvsHalWrapper, times(2)).connectToHalServiceIfNecessary();

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.setStreamCallback(null);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        mCarEvsService.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW, /* on= */ false);
        verify(mMockEvsHalWrapper, times(3)).connectToHalServiceIfNecessary();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.stopVideoStream(spiedCallback);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        verify(spiedCallback, times(2)).asBinder();

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        when(spiedCallback.asBinder()).thenReturn(null);
        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.stopVideoStream(spiedCallback);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        verify(spiedCallback, times(4)).asBinder();

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);

        // Transition from SERVICE_STATE_INACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);

        // Transition from SERVICE_STATE_ACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(null);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        // Transition from unknown states
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testTransitionToActive() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);

        // Transition from SERVICE_STATE_UNAVAILABLE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);

        // Transition from SERVICE_STATE_INACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(false);
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);

        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(false);
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);

        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(true);
        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(true);

        // Transition from SERVICE_STATE_REQUESTED
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_REQUESTED);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_REQUESTED);
        assertThrows(NullPointerException.class,
                () -> mCarEvsService.startVideoStream(
                        CarEvsManager.SERVICE_TYPE_REARVIEW, null, null));

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_REQUESTED);
        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(false);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        // Transition from SERVICE_STATE_ACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(spiedCallback);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        // Transition from unknown states
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testTransitionToRequested() {
        // Transition from SERVICE_STATE_UNAVAILABLE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);

        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(true);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        // Transition from SERVICE_STATE_INACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        // Transition from SERVICE_STATE_ACTIVE
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        // Transition from unknown states
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testCarPropertyEvent() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                                                  GEAR_SELECTION_PROPERTY_REVERSE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getProperty(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testEmptyCarPropertyEvent() throws Exception {
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getProperty(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList());

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testNonPropertyChangeEvent() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_ERROR,
                                                  GEAR_SELECTION_PROPERTY_REVERSE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getProperty(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testNonGearSelectionProperty() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                                                      CURRENT_GEAR_PROPERTY_REVERSE);
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getProperty(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testStartActivity() {
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
    }

    @Test
    public void testStartActivityFromUnavailableState() {
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        verify(mMockEvsHalWrapper, times(2)).connectToHalServiceIfNecessary();
    }

    @Test
    public void testStartActivityFromInactiveState() {
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_INACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartActivityFromActiveState() {
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartActivityFromRequestedState() {
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_REQUESTED);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testRequestDifferentServiceInRequestedState() {
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);

        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartAndStopActivity() {
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_REQUESTED);
        assertThat(mCarEvsService.getCurrentStatus().getServiceType())
                .isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);

        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testStopVideoStream() {
        EvsStreamCallbackImpl spiedCallback0 = spy(new EvsStreamCallbackImpl());
        EvsStreamCallbackImpl spiedCallback1 = spy(new EvsStreamCallbackImpl());
        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(null);
        mCarEvsService.stopVideoStream(spiedCallback1);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(spiedCallback0);
        mCarEvsService.stopVideoStream(spiedCallback1);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_ACTIVE);
    }

    @Test
    public void testRequestToStartVideoTwice() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
    }

    @Test
    public void testStartAndStopVideoStreamWithoutSessionToken() {
        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = 0;
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.onFrameEvent(bufferId, buffer);
        verify(spiedCallback)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
        mCarEvsService.stopVideoStream(spiedCallback);
    }

    @Test
    public void testStartAndStopVideoStreamWithSessionToken() throws Exception {
        int uid = Binder.getCallingUid();
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(uid);

        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();

        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = 0;
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, token, spiedCallback))
                .isEqualTo(ERROR_NONE);

        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.onFrameEvent(bufferId, buffer);
        doAnswer(args -> {
                mCarEvsService.returnFrameBuffer(new CarEvsBufferDescriptor(bufferId, buffer));
                return true;
        }).when(spiedCallback).onNewFrame(any());
        mCarEvsService.stopVideoStream(spiedCallback);
        verify(spiedCallback)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
    }

    @Test
    public void testRequestSurroundView() {
        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW, token, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
    }

    @Test
    public void testRequestRearviewButNoHal() {
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, token, spiedCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
    }

    @Test
    public void testCommandToSetRearviewCameraId() {
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(DEFAULT_CAMERA_ID);
        assertThat(mCarEvsService.setRearviewCameraIdFromCommand(CAMERA_TO_USE)).isTrue();
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(CAMERA_TO_USE);
        assertThat(mCarEvsService.setRearviewCameraIdFromCommand(COMMAND_TO_USE_DEFAULT_CAMERA))
                .isTrue();
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(DEFAULT_CAMERA_ID);
    }

    @Test
    public void testHalEvents() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        mCarEvsService.setStreamCallback(spiedCallback);

        mCarEvsService.onHalEvent(0);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STARTED);

        mCarEvsService.onHalEvent(1);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);

        mCarEvsService.onHalEvent(2);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_FRAME_DROPPED);

        mCarEvsService.onHalEvent(3);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_TIMEOUT);

        mCarEvsService.onHalEvent(4);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_PARAMETER_CHANGED);

        mCarEvsService.onHalEvent(5);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_PRIMARY_OWNER_CHANGED);

        mCarEvsService.onHalEvent(6);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_OTHER_ERRORS);

        mCarEvsService.onHalEvent(7);
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_NONE);
    }

    @Test
    public void testHandleStreamCallbackCrash() throws Exception {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        verify(spiedCallback, atLeastOnce()).asBinder();
        verify(spiedCallback, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testHandleStreamCallbackCrashAndRequestActivity() throws Exception {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        verify(spiedCallback, atLeastOnce()).asBinder();
        verify(spiedCallback, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        mCarEvsService.setServiceState(CarEvsManager.SERVICE_STATE_ACTIVE);
        mCarEvsService.setLastEvsHalEvent(/* timestamp= */ 0, CarEvsManager.SERVICE_TYPE_REARVIEW,
                                          /* on= */ true);
        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testStatusListenerCrash() throws Exception {
        EvsStatusListenerImpl spiedListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedListener);

        verify(spiedListener, atLeastOnce()).asBinder();
        verify(spiedListener, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
        mCarEvsService.unregisterStatusListener(spiedListener);
    }

    private void mockEvsHalService() throws Exception {
        when(mMockEvsHalService.isEvsServiceRequestSupported())
                .thenReturn(true);
    }

    private void mockEvsHalWrapper() throws Exception {
        when(mMockEvsHalWrapper.init()).thenReturn(true);
        when(mMockEvsHalWrapper.isConnected()).thenReturn(true);
        when(mMockEvsHalWrapper.openCamera(any())).thenReturn(true);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(true);
        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(true);

        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        doAnswer(args -> {
                mCarEvsService.returnFrameBuffer(
                        new CarEvsBufferDescriptor(args.getArgument(0), buffer));
                return true;
        }).when(mMockEvsHalWrapper).doneWithFrame(anyInt());
    }

    /**
     * Class that implements the listener interface and gets called back from
     * {@link android.car.evs.CarEvsManager.CarEvsStatusListener}.
     */
    private final static class EvsStatusListenerImpl extends ICarEvsStatusListener.Stub {
        @Override
        public void onStatusChanged(CarEvsStatus status) {
            Log.i(TAG, "Received a status change notification: " + status.getState());
        }
    }

    /**
     * Class that implements the listener interface and gets called back from
     * {@link android.hardware.automotive.evs.IEvsCameraStream}.
     */
    private final static class EvsStreamCallbackImpl extends ICarEvsStreamCallback.Stub {
        @Override
        public void onStreamEvent(@CarEvsStreamEvent int event) {
            Log.i(TAG, "Received stream event " + event);
        }

        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            // Return a buffer immediately
            Log.i(TAG_EVS, "Received buffer " + buffer.getId());
        }
    }
}

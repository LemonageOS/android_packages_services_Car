/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.car.CarOccupantZoneManager.INVALID_USER_ID;
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_FRONT_PASSENGER;
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER;
import static android.car.CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_INSTALLED;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_IN_FOREGROUND;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_RUNNING;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_SAME_SIGNATURE;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_SAME_VERSION;
import static android.car.CarRemoteDeviceManager.FLAG_OCCUPANT_ZONE_CONNECTION_READY;
import static android.car.CarRemoteDeviceManager.FLAG_OCCUPANT_ZONE_POWER_ON;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_RIGHT;
import static android.car.VehicleAreaSeat.SEAT_ROW_2_RIGHT;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCreateContextAsUser;

import static com.android.car.CarRemoteDeviceService.INITIAL_APP_STATE;
import static com.android.car.CarRemoteDeviceService.INITIAL_OCCUPANT_ZONE_STATE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.ICarOccupantZoneCallback;
import android.car.builtin.app.ActivityManagerHelper.ProcessObserverCallback;
import android.car.occupantconnection.IStateCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.car.CarRemoteDeviceService.PerUserInfo;
import com.android.car.internal.util.BinderKeyValueContainer;
import com.android.car.occupantconnection.ClientId;
import com.android.car.power.CarPowerManagementService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class CarRemoteDeviceServiceTest {

    private static final String PACKAGE_NAME = "my_package_name";
    private static final String FAKE_PACKAGE_NAME = "fake_package_name";

    private static final int OCCUPANT_ZONE_ID = 321;
    private static final int USER_ID = 123;
    private static final int PID = 456;

    // This value is copied from android.os.UserHandle#PER_USER_RANGE.
    private static final int PER_USER_RANGE = 100000;
    // This value is copied from android.os.UserHandle#USER_SYSTEM.
    private static final int USER_SYSTEM = 0;

    @Mock
    private Context mContext;
    @Mock
    private CarOccupantZoneService mOccupantZoneService;
    @Mock
    private CarPowerManagementService mPowerManagementService;
    @Mock
    private SystemActivityMonitoringService mSystemActivityMonitoringService;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private IStateCallback mCallback;
    @Mock
    private IBinder mCallbackBinder;

    private final SparseArray<PerUserInfo> mPerUserInfoMap = new SparseArray<>();
    private final BinderKeyValueContainer<ClientId, IStateCallback> mCallbackMap =
            new BinderKeyValueContainer<>();
    private final ArrayMap<ClientId, Integer> mAppStateMap = new ArrayMap<>();
    private final ArrayMap<OccupantZoneInfo, Integer> mOccupantZoneStateMap = new ArrayMap<>();

    private final OccupantZoneInfo mOccupantZone =
            new OccupantZoneInfo(OCCUPANT_ZONE_ID, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);

    private CarRemoteDeviceService mService;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        // Stored as static: Other tests can leave things behind and fail this test in add call.
        // So just remove as safety guard.
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mOccupantZoneService);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class, mPowerManagementService);
        CarLocalServices.removeServiceForTest(SystemActivityMonitoringService.class);
        CarLocalServices.addService(SystemActivityMonitoringService.class,
                mSystemActivityMonitoringService);

        mService = new CarRemoteDeviceService(mContext, mOccupantZoneService,
                mPowerManagementService, mSystemActivityMonitoringService, mActivityManager,
                mUserManager, mPerUserInfoMap, mCallbackMap, mAppStateMap, mOccupantZoneStateMap);
        mService.init();
        mockPackageName();
        when(mCallback.asBinder()).thenReturn(mCallbackBinder);
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(SystemActivityMonitoringService.class);
    }

    @Test
    public void testInit() {
        // There are three occupant zones: zone1 is assigned with a foreground user, zone2 is not
        // assigned a user yet, and zone3 is assigned with the system user.
        OccupantZoneInfo zone1 = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        OccupantZoneInfo zone2 = new OccupantZoneInfo(/* zoneId= */ 1,
                OCCUPANT_TYPE_FRONT_PASSENGER, SEAT_ROW_1_RIGHT);
        OccupantZoneInfo zone3 = new OccupantZoneInfo(/* zoneId= */ 2,
                OCCUPANT_TYPE_REAR_PASSENGER, SEAT_ROW_2_RIGHT);
        List<OccupantZoneInfo> allZones = Arrays.asList(zone1, zone2, zone3);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);
        when(mOccupantZoneService.getUserForOccupant(zone1.zoneId)).thenReturn(USER_ID);
        when(mOccupantZoneService.getUserForOccupant(zone2.zoneId)).thenReturn(INVALID_USER_ID);
        when(mOccupantZoneService.getUserForOccupant(zone3.zoneId)).thenReturn(USER_SYSTEM);

        Context userContext1 = mock(Context.class);
        when(mContext.createContextAsUser(eq(UserHandle.of(USER_ID)), anyInt()))
                .thenReturn(userContext1);
        PackageManager pm1 = mock(PackageManager.class);
        when(userContext1.getPackageManager()).thenReturn(pm1);

        mService.init();

        verify(userContext1).registerReceiver(any(), any());
        assertThat(mPerUserInfoMap.size()).isEqualTo(1);
        assertThat(mAppStateMap.size()).isEqualTo(0);
    }

    @Test
    public void testPeerClientInstallUninstall() {
        // There are two occupant zone: my zone, peer zone.
        int myUserId = Binder.getCallingUserHandle().getIdentifier();
        mockPerUserInfo(myUserId, mOccupantZone);

        OccupantZoneInfo peerZone = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        PerUserInfo peerUserInfo = mockPerUserInfo(USER_ID, peerZone);

        List<OccupantZoneInfo> allZones = Arrays.asList(mOccupantZone, peerZone);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);

        // Intercept the real receiver.
        BroadcastReceiver[] peerReceiver = new BroadcastReceiver[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            peerReceiver[0] = (BroadcastReceiver) args[0];
            return null;
        }).when(peerUserInfo.context)
                .registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));

        mService.init();
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        // Pretend that the peer app is installed in the beginning.
        ClientId peerClient = new ClientId(peerZone, USER_ID, PACKAGE_NAME);
        mAppStateMap.put(peerClient,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);

        // Then the peer app is uninstalled.
        Uri uri = mock(Uri.class);
        when(uri.getSchemeSpecificPart()).thenReturn(PACKAGE_NAME);
        Intent intent = mock(Intent.class);
        when(intent.getData()).thenReturn(uri);
        when(intent.getAction()).thenReturn(Intent.ACTION_PACKAGE_REMOVED);
        peerReceiver[0].onReceive(mock(Context.class), intent);

        assertThat(mAppStateMap.get(peerClient)).isEqualTo(INITIAL_APP_STATE);

        // Then the peer app is installed.
        PackageInfo packageInfo = mock(PackageInfo.class);
        try {
            when(peerUserInfo.pm.getPackageInfo(eq(PACKAGE_NAME), any())).thenReturn(packageInfo);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        when(intent.getAction()).thenReturn(Intent.ACTION_PACKAGE_ADDED);
        peerReceiver[0].onReceive(mock(Context.class), intent);

        assertThat(mAppStateMap.get(peerClient)).isEqualTo(
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);
    }

    @Test
    public void testNonPeerClientUninstall() {
        // There are two occupant zone: my zone, peer zone.
        int myUserId = Binder.getCallingUserHandle().getIdentifier();
        mockPerUserInfo(myUserId, mOccupantZone);

        OccupantZoneInfo peerZone = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        PerUserInfo peerUserInfo = mockPerUserInfo(USER_ID, peerZone);

        List<OccupantZoneInfo> allZones = Arrays.asList(mOccupantZone, peerZone);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);

        // Intercept the real receiver.
        BroadcastReceiver[] peerReceiver = new BroadcastReceiver[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            peerReceiver[0] = (BroadcastReceiver) args[0];
            return null;
        }).when(peerUserInfo.context)
                .registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));

        mService.init();

        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        // Pretend that the peer app is installed in the beginning.
        ClientId peerClient = new ClientId(peerZone, USER_ID, PACKAGE_NAME);
        mAppStateMap.put(peerClient,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);

        // Nothing should happen if an app with another package name is uninstalled.
        String anotherPackageName = PACKAGE_NAME + "abc";
        Uri uri = mock(Uri.class);
        when(uri.getSchemeSpecificPart()).thenReturn(anotherPackageName);
        Intent intent = mock(Intent.class);
        when(intent.getData()).thenReturn(uri);
        when(intent.getAction()).thenReturn(Intent.ACTION_PACKAGE_REMOVED);
        peerReceiver[0].onReceive(mock(Context.class), intent);

        assertThat(mAppStateMap.get(peerClient)).isEqualTo(
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);
    }

    @Test
    public void testGetEndpointPackageInfoWithoutPermission_throwsException() {
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.getEndpointPackageInfo(OCCUPANT_ZONE_ID, PACKAGE_NAME));
    }

    @Test
    public void testGetEndpointPackageInfoWithFakePackageName_throwsException() {
        assertThrows(SecurityException.class,
                () -> mService.getEndpointPackageInfo(OCCUPANT_ZONE_ID, FAKE_PACKAGE_NAME));
    }

    @Test
    public void testGetEndpointPackageInfoWithInvalidUserId() {
        when(mOccupantZoneService.getUserForOccupant(OCCUPANT_ZONE_ID)).thenReturn(
                INVALID_USER_ID);

        assertThat(mService.getEndpointPackageInfo(OCCUPANT_ZONE_ID, PACKAGE_NAME)).isNull();
    }

    @Test
    public void testGetEndpointPackageInfo() throws PackageManager.NameNotFoundException {
        PackageInfo packageInfo = mock(PackageInfo.class);
        PerUserInfo perUserInfo = mockPerUserInfo(USER_ID, mOccupantZone);
        when(perUserInfo.pm.getPackageInfo(eq(PACKAGE_NAME), any())).thenReturn(packageInfo);

        assertThat(mService.getEndpointPackageInfo(mOccupantZone.zoneId, PACKAGE_NAME))
                .isEqualTo(packageInfo);
    }

    @Test
    public void testChangePowerStateOn() {
        int displayId = 1;
        int[] displays = {displayId};
        when(mOccupantZoneService.getAllDisplaysForOccupantZone(OCCUPANT_ZONE_ID))
                .thenReturn(displays);

        mService.setOccupantZonePower(mOccupantZone, true);
        verify(mPowerManagementService).setDisplayPowerState(displayId, true);
    }

    @Test
    public void testChangePowerStateOff() {
        int displayId = 1;
        int[] displays = {displayId};
        when(mOccupantZoneService.getAllDisplaysForOccupantZone(OCCUPANT_ZONE_ID))
                .thenReturn(displays);

        mService.setOccupantZonePower(mOccupantZone, false);
        verify(mPowerManagementService).setDisplayPowerState(displayId, false);
    }

    @Test
    public void testGetPowerStateOn() {
        when(mOccupantZoneService.areDisplaysOnForOccupantZone(OCCUPANT_ZONE_ID))
                .thenReturn(true);

        assertThat(mService.isOccupantZonePowerOn(mOccupantZone)).isTrue();
    }

    @Test
    public void testGetPowerStateOff() {
        when(mOccupantZoneService.areDisplaysOnForOccupantZone(OCCUPANT_ZONE_ID))
                .thenReturn(false);

        assertThat(mService.isOccupantZonePowerOn(mOccupantZone)).isFalse();
    }

    @Test
    public void testCalculateAppStateLocked_notInstalled() {
        ClientId clientId = new ClientId(mOccupantZone, USER_ID, PACKAGE_NAME);

        assertThat(mService.calculateAppState(clientId)).isEqualTo(0);
    }

    @Test
    public void testCalculateAppStateLocked_installedNotRunning() {
        ClientId clientId = new ClientId(mOccupantZone, USER_ID, PACKAGE_NAME);
        mockAppInstalledAsUser(USER_ID, mOccupantZone);

        assertThat(mService.calculateAppState(clientId)).isEqualTo(
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);
    }

    @Test
    public void testCalculateAppStateLocked_runningInBackground() {
        ClientId clientId = new ClientId(mOccupantZone, USER_ID, PACKAGE_NAME);
        mockAppRunningAsUser(USER_ID, PID, mOccupantZone, IMPORTANCE_CACHED);

        assertThat(mService.calculateAppState(clientId))
                .isEqualTo(FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION
                        | FLAG_CLIENT_SAME_SIGNATURE | FLAG_CLIENT_RUNNING);
    }

    @Test
    public void testCalculateAppStateLocked_runningInForeground() {
        ClientId clientId = new ClientId(mOccupantZone, USER_ID, PACKAGE_NAME);
        mockAppRunningAsUser(USER_ID, PID, mOccupantZone, IMPORTANCE_FOREGROUND);

        assertThat(mService.calculateAppState(clientId))
                .isEqualTo(FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION
                        | FLAG_CLIENT_SAME_SIGNATURE | FLAG_CLIENT_RUNNING
                        | FLAG_CLIENT_IN_FOREGROUND);
    }

    @Test
    public void testCalculateOccupantZoneState_notPowerOn() {
        assertThat(mService.calculateOccupantZoneState(mOccupantZone))
                .isEqualTo(INITIAL_OCCUPANT_ZONE_STATE);
    }

    @Test
    public void testCalculateOccupantZoneState_powerOn() {
        mockOccupantZonePowerOn(mOccupantZone);

        assertThat(mService.calculateOccupantZoneState(mOccupantZone))
                .isEqualTo(FLAG_OCCUPANT_ZONE_POWER_ON);
    }

    @Test
    public void testCalculateOccupantZoneState_connectionReady() {
        mockOccupantZonePowerOn(mOccupantZone);
        mockOccupantZoneConnectionReady(mOccupantZone, USER_ID);

        assertThat(mService.calculateOccupantZoneState(mOccupantZone))
                .isEqualTo(FLAG_OCCUPANT_ZONE_POWER_ON | FLAG_OCCUPANT_ZONE_CONNECTION_READY);
    }

    @Test
    public void testRegisterStateCallbackWithoutPermission_throwsException() {
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.registerStateCallback(PACKAGE_NAME, any(IStateCallback.class)));
    }

    @Test
    public void testRegisterStateCallbackWithFakePackageName_throwsException() {
        assertThrows(SecurityException.class,
                () -> mService.registerStateCallback(FAKE_PACKAGE_NAME, any(IStateCallback.class)));
    }

    @Test
    public void testRegisterDuplicateStateCallback_throwsException() {
        UserHandle userHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(userHandle)).thenReturn(mOccupantZone);
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        assertThrows(IllegalStateException.class,
                () -> mService.registerStateCallback(PACKAGE_NAME, any(IStateCallback.class)));
    }

    @Test
    public void testRegisterStateCallback() throws RemoteException {
        // There are three occupant zones assigned with a foreground user.
        OccupantZoneInfo myZone = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        OccupantZoneInfo peerZone1 = new OccupantZoneInfo(/* zoneId= */ 1,
                OCCUPANT_TYPE_FRONT_PASSENGER, SEAT_ROW_1_RIGHT);
        OccupantZoneInfo peerZone2 = new OccupantZoneInfo(/* zoneId= */ 2,
                OCCUPANT_TYPE_REAR_PASSENGER, SEAT_ROW_2_RIGHT);
        List<OccupantZoneInfo> allZones = Arrays.asList(myZone, peerZone1, peerZone2);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);

        UserHandle myUserHandle = Binder.getCallingUserHandle();
        int myUserId = myUserHandle.getIdentifier();
        int peerUserId1 = myUserId + 10;
        int peerUserId2 = myUserId + 11;

        // The caller zone is powered on and ready for connection.
        // Peer zone 1 is powered on, and peer zone 2 is not powered on.
        mockOccupantZonePowerOn(myZone);
        mockOccupantZoneConnectionReady(myZone, myUserId);
        mockOccupantZonePowerOn(peerZone1);

        // The discovering client is running in the foreground. Its peer client1 is installed but
        // not running, and peer client2 is not installed.
        mockAppRunningAsUser(myUserId, PID, myZone, IMPORTANCE_FOREGROUND);
        mockAppInstalledAsUser(peerUserId1, peerZone1);
        mockPerUserInfo(peerUserId2, peerZone2);

        mService.init();
        // The app state and occupant zone state are up-to-date before registering the callback.
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        verify(mCallback).onAppStateChanged(peerZone1,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);
        verify(mCallback).onAppStateChanged(peerZone2, INITIAL_APP_STATE);

        verify(mCallback).onOccupantZoneStateChanged(peerZone1, FLAG_OCCUPANT_ZONE_POWER_ON);
        verify(mCallback).onOccupantZoneStateChanged(peerZone2, INITIAL_OCCUPANT_ZONE_STATE);
    }

    @Test
    public void testAppStateChanged() throws RemoteException {
        ProcessObserverCallback[] processObserver = new ProcessObserverCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            processObserver[0] = (ProcessObserverCallback) args[0];
            return null;
        }).when(mSystemActivityMonitoringService).registerProcessObserverCallback(any());

        // There are three occupant zones assigned with a foreground user.
        OccupantZoneInfo myZone = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        OccupantZoneInfo peerZone1 = new OccupantZoneInfo(/* zoneId= */ 1,
                OCCUPANT_TYPE_FRONT_PASSENGER, SEAT_ROW_1_RIGHT);
        OccupantZoneInfo peerZone2 = new OccupantZoneInfo(/* zoneId= */ 2,
                OCCUPANT_TYPE_REAR_PASSENGER, SEAT_ROW_2_RIGHT);
        List<OccupantZoneInfo> allZones = Arrays.asList(myZone, peerZone1, peerZone2);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);

        UserHandle myUserHandle = Binder.getCallingUserHandle();
        int myUserId = myUserHandle.getIdentifier();
        int peerUserId1 = myUserId + 10;
        int peerUserId2 = myUserId + 11;
        mockPerUserInfo(myUserId, myZone);
        mockPerUserInfo(peerUserId1, peerZone1);
        mockPerUserInfo(peerUserId2, peerZone2);

        mService.init();
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        verify(mCallback).onAppStateChanged(eq(peerZone1), anyInt());
        verify(mCallback).onAppStateChanged(eq(peerZone2), anyInt());

        // Peer app1 is running in foreground.
        int myPid = PID;
        int peerPid1 = myPid + 1;
        mockAppRunningAsUser(peerUserId1, peerPid1, peerZone1, IMPORTANCE_FOREGROUND);
        processObserver[0].onForegroundActivitiesChanged(peerPid1, userIdToUid(peerUserId1),
                /* foregroundActivities= */ true);

        verify(mCallback).onAppStateChanged(peerZone1,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE
                        | FLAG_CLIENT_RUNNING | FLAG_CLIENT_IN_FOREGROUND);

        // Peer app2 is running in background.
        int peerPid2 = myPid + 2;
        mockAppRunningAsUser(peerUserId2, peerPid2, peerZone2, IMPORTANCE_CACHED);
        processObserver[0].onForegroundActivitiesChanged(peerPid2, userIdToUid(peerUserId2),
                /* foregroundActivities= */ false);

        verify(mCallback).onAppStateChanged(peerZone2,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE
                        | FLAG_CLIENT_RUNNING);

        // Peer app1 is dead.
        mockAppInstalledAsUser(peerUserId1, peerZone1);
        processObserver[0].onProcessDied(peerPid1, userIdToUid(peerUserId1));

        verify(mCallback).onAppStateChanged(peerZone1,
                FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION | FLAG_CLIENT_SAME_SIGNATURE);
    }

    @Test
    public void testOccupantZoneStateChanged() throws RemoteException {
        ICarOccupantZoneCallback[] zoneStateCallback = new ICarOccupantZoneCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            zoneStateCallback[0] = (ICarOccupantZoneCallback) args[0];
            return null;
        }).when(mOccupantZoneService).registerCallback(any());

        // There are three occupant zones assigned with a foreground user.
        OccupantZoneInfo myZone = new OccupantZoneInfo(/* zoneId= */ 0,
                OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        OccupantZoneInfo peerZone1 = new OccupantZoneInfo(/* zoneId= */ 1,
                OCCUPANT_TYPE_FRONT_PASSENGER, SEAT_ROW_1_RIGHT);
        OccupantZoneInfo peerZone2 = new OccupantZoneInfo(/* zoneId= */ 2,
                OCCUPANT_TYPE_REAR_PASSENGER, SEAT_ROW_2_RIGHT);
        List<OccupantZoneInfo> allZones = Arrays.asList(myZone, peerZone1, peerZone2);
        when(mOccupantZoneService.getAllOccupantZones()).thenReturn(allZones);

        UserHandle myUserHandle = Binder.getCallingUserHandle();
        int myUserId = myUserHandle.getIdentifier();
        int peerUserId1 = myUserId + 10;
        int peerUserId2 = myUserId + 11;
        mockPerUserInfo(myUserId, myZone);
        mockPerUserInfo(peerUserId1, peerZone1);
        mockPerUserInfo(peerUserId2, peerZone2);

        mService.init();
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        verify(mCallback).onOccupantZoneStateChanged(eq(peerZone1), anyInt());
        verify(mCallback).onOccupantZoneStateChanged(eq(peerZone2), anyInt());

        // Peer zone 1 has a user change. It is powered on and is ready for connection.
        int newPeerUserId1 = peerUserId1 + 10;
        mockPerUserInfo(newPeerUserId1, peerZone1);
        mockOccupantZonePowerOn(peerZone1);
        mockOccupantZoneConnectionReady(peerZone1, newPeerUserId1);
        zoneStateCallback[0].onOccupantZoneConfigChanged(ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mCallback).onOccupantZoneStateChanged(peerZone1,
                FLAG_OCCUPANT_ZONE_POWER_ON | FLAG_OCCUPANT_ZONE_CONNECTION_READY);

        // Peer zone 2 has a user change too, and it is powered on.
        int newPeerUserId2 = peerUserId2 + 10;
        mockPerUserInfo(newPeerUserId2, peerZone2);
        mockOccupantZonePowerOn(peerZone2);
        zoneStateCallback[0].onOccupantZoneConfigChanged(ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mCallback).onOccupantZoneStateChanged(peerZone2, FLAG_OCCUPANT_ZONE_POWER_ON);
    }

    @Test
    public void testUserAssigned() throws RemoteException {
        ICarOccupantZoneCallback[] callback = new ICarOccupantZoneCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            callback[0] = (ICarOccupantZoneCallback) args[0];
            return null;
        }).when(mOccupantZoneService).registerCallback(any());

        mService.init();
        mOccupantZoneStateMap.put(mOccupantZone, FLAG_OCCUPANT_ZONE_POWER_ON);

        mockPerUserInfo(USER_ID, mOccupantZone);
        // Remove the item added by previous line, then check whether it can be added back
        // after onOccupantZoneConfigChanged().
        mPerUserInfoMap.remove(USER_ID);
        callback[0].onOccupantZoneConfigChanged(ZONE_CONFIG_CHANGE_FLAG_USER);

        assertThat(mPerUserInfoMap.get(USER_ID).zone).isEqualTo(mOccupantZone);
    }

    @Test
    public void testUserUnassigned() throws RemoteException {
        ICarOccupantZoneCallback[] callback = new ICarOccupantZoneCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            callback[0] = (ICarOccupantZoneCallback) args[0];
            return null;
        }).when(mOccupantZoneService).registerCallback(any());

        mService.init();
        mOccupantZoneStateMap.put(mOccupantZone, FLAG_OCCUPANT_ZONE_POWER_ON);

        mockPerUserInfo(USER_ID, mOccupantZone);
        assertThat(mPerUserInfoMap.size()).isEqualTo(1);

        when(mOccupantZoneService.getUserForOccupant(mOccupantZone.zoneId))
                .thenReturn(INVALID_USER_ID);
        callback[0].onOccupantZoneConfigChanged(ZONE_CONFIG_CHANGE_FLAG_USER);

        assertThat(mPerUserInfoMap.size()).isEqualTo(0);
    }

    @Test
    public void testUserSwitched() throws RemoteException {
        ICarOccupantZoneCallback[] callback = new ICarOccupantZoneCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            callback[0] = (ICarOccupantZoneCallback) args[0];
            return null;
        }).when(mOccupantZoneService).registerCallback(any());

        mService.init();
        mOccupantZoneStateMap.put(mOccupantZone, FLAG_OCCUPANT_ZONE_POWER_ON);
        mockPerUserInfo(USER_ID, mOccupantZone);

        assertThat(mPerUserInfoMap.get(USER_ID).zone).isEqualTo(mOccupantZone);

        int userId2 = USER_ID + 10;
        when(mOccupantZoneService.getUserForOccupant(mOccupantZone.zoneId)).thenReturn(userId2);
        mockPerUserInfo(userId2, mOccupantZone);
        // Remove the item added by previous line, then check whether it can be added back
        // after onOccupantZoneConfigChanged().
        mPerUserInfoMap.remove(userId2);
        callback[0].onOccupantZoneConfigChanged(ZONE_CONFIG_CHANGE_FLAG_USER);

        assertThat(mPerUserInfoMap.get(userId2).zone).isEqualTo(mOccupantZone);
    }

    @Test
    public void testUnregisterStateCallbackWithoutPermission_throwsException() {
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class, () -> mService.unregisterStateCallback(PACKAGE_NAME));
    }

    @Test
    public void testUnregisterStateCallbackWithFakePackageName_throwsException() {
        assertThrows(SecurityException.class,
                () -> mService.unregisterStateCallback(FAKE_PACKAGE_NAME));
    }

    @Test
    public void testUnregisterNonexistentStateCallback_throwsException() {
        UserHandle userHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(userHandle)).thenReturn(mOccupantZone);

        assertThrows(IllegalStateException.class,
                () -> mService.unregisterStateCallback(PACKAGE_NAME));
    }

    @Test
    public void testUnregisterStateCallbackWithoutOtherDiscoverers() {
        // There is only one discoverer.
        int userId = Binder.getCallingUserHandle().getIdentifier();
        mockPerUserInfo(userId, mOccupantZone);
        ClientId discoveringClient = new ClientId(mOccupantZone, userId, PACKAGE_NAME);
        mService.registerStateCallback(PACKAGE_NAME, mCallback);

        assertThat(mCallbackMap.containsKey(discoveringClient)).isTrue();
        assertThat(mAppStateMap.containsKey(discoveringClient)).isTrue();

        // Unregister the only discoverer.
        mService.unregisterStateCallback(PACKAGE_NAME);

        assertThat(mCallbackMap.containsKey(discoveringClient)).isFalse();
        for (int i = 0; i < mAppStateMap.size(); i++) {
            ClientId anotherDiscoveringClient = mAppStateMap.keyAt(i);
            assertThat(anotherDiscoveringClient.packageName).isNotEqualTo(PACKAGE_NAME);
        }
    }

    @Test
    public void testUnregisterStateCallbackWithOtherDiscoverers() {
        // There are two discoverers.
        UserHandle userHandle = Binder.getCallingUserHandle();
        int userId = userHandle.getIdentifier();
        mockPerUserInfo(userId, mOccupantZone);
        OccupantZoneInfo zone2 = new OccupantZoneInfo(/* zoneId= */ 1,
                OCCUPANT_TYPE_FRONT_PASSENGER, SEAT_ROW_1_RIGHT);
        int userId2 = 456;
        mockPerUserInfo(userId2, zone2);
        ClientId discoveringClient = new ClientId(mOccupantZone, userId, PACKAGE_NAME);
        ClientId discoveringClient2 = new ClientId(zone2, userId2, PACKAGE_NAME);
        mService.registerStateCallback(PACKAGE_NAME, mCallback);
        IStateCallback callback2 = mock(IStateCallback.class);
        IBinder callbackBinder = mock(IBinder.class);
        when(callback2.asBinder()).thenReturn(callbackBinder);
        mCallbackMap.put(discoveringClient2, callback2);

        assertThat(mCallbackMap.containsKey(discoveringClient)).isTrue();
        assertThat(mCallbackMap.containsKey(discoveringClient2)).isTrue();
        assertThat(mAppStateMap.containsKey(discoveringClient)).isTrue();
        assertThat(mAppStateMap.containsKey(discoveringClient2)).isTrue();

        // Unregister the first discoverer.
        mService.unregisterStateCallback(PACKAGE_NAME);

        assertThat(mCallbackMap.containsKey(discoveringClient)).isFalse();
        assertThat(mCallbackMap.containsKey(discoveringClient2)).isTrue();
        assertThat(mAppStateMap.containsKey(discoveringClient)).isTrue();
        assertThat(mAppStateMap.containsKey(discoveringClient2)).isTrue();
    }

    private void mockPackageName() throws PackageManager.NameNotFoundException {
        PackageManager pm = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(pm);
        when(pm.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt())).thenReturn(Binder.getCallingUid());
    }

    private void mockAppInstalledAsUser(int userId, OccupantZoneInfo occupantZone) {
        PerUserInfo userInfo = mockPerUserInfo(userId, occupantZone);
        PackageInfo packageInfo = mock(PackageInfo.class);
        try {
            when(userInfo.pm.getPackageInfo(eq(PACKAGE_NAME), any())).thenReturn(packageInfo);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        String[] packageNames = {PACKAGE_NAME};
        int uid = userIdToUid(userId);
        when(userInfo.pm.getPackagesForUid(uid)).thenReturn(packageNames);
    }

    private void mockAppRunningAsUser(int userId, int pid, OccupantZoneInfo occupantZone,
            int importance) {
        mockAppInstalledAsUser(userId, occupantZone);
        RunningAppProcessInfo process = new RunningAppProcessInfo();
        process.processName = PACKAGE_NAME;
        process.uid = userIdToUid(userId);
        process.pid = pid;
        process.importance = importance;
        List<RunningAppProcessInfo> processList = Arrays.asList(process);
        when(mActivityManager.getRunningAppProcesses()).thenReturn(processList);
    }

    private void mockOccupantZonePowerOn(OccupantZoneInfo occupantZone) {
        when(mOccupantZoneService.areDisplaysOnForOccupantZone(occupantZone.zoneId))
                .thenReturn(true);
    }

    private void mockOccupantZoneConnectionReady(OccupantZoneInfo occupantZone, int userId) {
        when(mOccupantZoneService.getUserForOccupant(occupantZone.zoneId)).thenReturn(userId);
        UserHandle userHandle = UserHandle.of(userId);
        when(mUserManager.isUserRunning(userHandle)).thenReturn(true);
        when(mUserManager.isUserUnlocked(userHandle)).thenReturn(true);
    }

    private PerUserInfo mockPerUserInfo(int userId, OccupantZoneInfo occupantZone) {
        when(mOccupantZoneService.getUserForOccupant(occupantZone.zoneId)).thenReturn(userId);
        when(mOccupantZoneService.getOccupantZoneForUser(UserHandle.of(userId)))
                .thenReturn(occupantZone);

        Context userContext = mock(Context.class);
        mockContextCreateContextAsUser(mContext, userContext, userId);
        PackageManager pm = mock(PackageManager.class);
        when(userContext.getPackageManager()).thenReturn(pm);
        BroadcastReceiver receiver = mock(BroadcastReceiver.class);
        PerUserInfo userInfo = new PerUserInfo(occupantZone, userContext, pm, receiver);
        mPerUserInfoMap.put(userId, userInfo);
        return userInfo;
    }

    private static int userIdToUid(int userId) {
        return userId * PER_USER_RANGE;
    }
}

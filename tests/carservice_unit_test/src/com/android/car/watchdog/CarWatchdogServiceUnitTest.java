/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.watchdog;

import static android.app.StatsManager.PULL_SKIP;
import static android.app.StatsManager.PULL_SUCCESS;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
import static android.car.settings.CarSettings.Secure.KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUserHandles;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmIsUserRunning;
import static android.car.test.util.AndroidHelper.assertFilterHasActions;
import static android.car.test.util.AndroidHelper.assertFilterHasDataScheme;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;
import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_CRITICAL;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_IO_OVERUSE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_UID_IO_USAGE_SUMMARY;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP;
import static com.android.car.internal.NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID;
import static com.android.car.internal.NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
import static com.android.car.watchdog.CarWatchdogService.ACTION_GARAGE_MODE_OFF;
import static com.android.car.watchdog.CarWatchdogService.ACTION_GARAGE_MODE_ON;
import static com.android.car.watchdog.CarWatchdogService.MISSING_ARG_VALUE;
import static com.android.car.watchdog.TimeSource.ZONE_OFFSET;
import static com.android.car.watchdog.WatchdogPerfHandler.INTENT_EXTRA_NOTIFICATION_ID;
import static com.android.car.watchdog.WatchdogPerfHandler.PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR;
import static com.android.car.watchdog.WatchdogPerfHandler.USER_PACKAGE_SEPARATOR;
import static com.android.car.watchdog.WatchdogProcessHandler.MISSING_INT_PROPERTY_VALUE;
import static com.android.car.watchdog.WatchdogProcessHandler.PROPERTY_RO_CLIENT_HEALTHCHECK_INTERVAL;
import static com.android.car.watchdog.WatchdogStorage.RETENTION_PERIOD;
import static com.android.car.watchdog.WatchdogStorage.WatchdogDbHelper.DATABASE_NAME;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.StatsManager;
import android.app.StatsManager.PullAtomMetadata;
import android.app.StatsManager.StatsPullAtomCallback;
import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.ICarWatchdog;
import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.IoUsageStats;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageInfo;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageMetadata;
import android.automotive.watchdog.internal.PerStateIoOveruseThreshold;
import android.automotive.watchdog.internal.PowerCycle;
import android.automotive.watchdog.internal.ResourceSpecificConfiguration;
import android.automotive.watchdog.internal.ResourceStats;
import android.automotive.watchdog.internal.StateType;
import android.automotive.watchdog.internal.UidType;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.automotive.watchdog.internal.UserState;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.ICarPowerPolicyListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.car.hardware.power.PowerComponent;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.MockSettings;
import android.car.user.CarUserManager;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.ICarWatchdogServiceCallback;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseAlertThreshold;
import android.car.watchdog.IoOveruseConfiguration;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.view.Display;

import androidx.test.filters.FlakyTest;

import com.android.car.BuiltinPackageDependency;
import com.android.car.CarLocalServices;
import com.android.car.CarServiceHelperWrapper;
import com.android.car.CarServiceUtils;
import com.android.car.CarStatsLog;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.admin.NotificationHelper;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;

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
 * <p>This class contains unit tests for the {@link CarWatchdogService}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarWatchdogServiceUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String CAR_WATCHDOG_DAEMON_INTERFACE =
            "android.automotive.watchdog.internal.ICarWatchdog/default";
    private static final int MAX_WAIT_TIME_MS = 3000;
    private static final int OVERUSE_HANDLING_DELAY_MILLS = 1000;
    private static final int RECURRING_OVERUSE_TIMES = 2;
    private static final int RECURRING_OVERUSE_PERIOD_IN_DAYS = 2;
    private static final int UID_IO_USAGE_SUMMARY_TOP_COUNT = 3;
    private static final int IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES = 500 * 1024 * 1024;
    private static final long STATS_DURATION_SECONDS = 3 * 60 * 60;
    private static final long SYSTEM_DAILY_IO_USAGE_SUMMARY_MULTIPLIER = 10_000;
    private static final int PACKAGE_KILLABLE_STATE_RESET_DAYS = 90;
    private static final int CARWATCHDOG_DAEMON_INTERFACE_VERSION_UDC = 3;

    @Mock private Context mMockContext;
    @Mock private Context mMockBuiltinPackageContext;
    @Mock private ClassLoader mMockClassLoader;
    @Mock private PackageManager mMockPackageManager;
    @Mock private StatsManager mMockStatsManager;
    @Mock private UserManager mMockUserManager;
    @Mock private SystemInterface mMockSystemInterface;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private CarUserService mMockCarUserService;
    @Mock private CarUxRestrictionsManagerService mMockCarUxRestrictionsManagerService;
    @Mock private Resources mMockResources;
    @Mock private IBinder mMockBinder;
    @Mock private ICarWatchdog mMockCarWatchdogDaemon;
    @Mock private NotificationHelper mMockNotificationHelper;
    @Mock private ICarServiceHelper.Stub mMockCarServiceHelper;
    @Mock private WatchdogProcessHandler mMockWatchdogProcessHandler;

    @Captor private ArgumentCaptor<ICarPowerStateListener> mICarPowerStateListenerCaptor;
    @Captor private ArgumentCaptor<ICarPowerPolicyListener> mICarPowerPolicyListenerCaptor;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;
    @Captor private ArgumentCaptor<IntentFilter> mIntentFilterCaptor;
    @Captor private ArgumentCaptor<ICarUxRestrictionsChangeListener>
            mICarUxRestrictionsChangeListener;
    @Captor private ArgumentCaptor<CarUserManager.UserLifecycleListener>
            mUserLifecycleListenerCaptor;
    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;
    @Captor private ArgumentCaptor<ICarWatchdogServiceForSystem>
            mICarWatchdogServiceForSystemCaptor;
    @Captor private ArgumentCaptor<List<
            android.automotive.watchdog.internal.ResourceOveruseConfiguration>>
            mResourceOveruseConfigurationsCaptor;
    @Captor private ArgumentCaptor<SparseArray<List<String>>> mPackagesByUserIdCaptor;
    @Captor private ArgumentCaptor<StatsPullAtomCallback> mStatsPullAtomCallbackCaptor;
    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<byte[]> mOveruseStatsCaptor;
    @Captor private ArgumentCaptor<byte[]> mKilledStatsCaptor;
    @Captor private ArgumentCaptor<Integer> mOverusingUidCaptor;
    @Captor private ArgumentCaptor<Integer> mKilledUidCaptor;
    @Captor private ArgumentCaptor<Integer> mUidStateCaptor;
    @Captor private ArgumentCaptor<Integer> mSystemStateCaptor;
    @Captor private ArgumentCaptor<Integer> mKillReasonCaptor;

    private CarWatchdogService mCarWatchdogService;
    private ICarWatchdogServiceForSystem mWatchdogServiceForSystemImpl;
    private IBinder.DeathRecipient mCarWatchdogDaemonBinderDeathRecipient;
    private WatchdogStorage mSpiedWatchdogStorage;
    private BroadcastReceiver mBroadcastReceiver;
    private boolean mIsDaemonCrashed;
    private ICarPowerStateListener mCarPowerStateListener;
    private ICarPowerPolicyListener mCarPowerPolicyListener;
    private CarUserManager.UserLifecycleListener mUserLifecycleListener;
    private ICarUxRestrictionsChangeListener mCarUxRestrictionsChangeListener;
    private StatsPullAtomCallback mStatsPullAtomCallback;
    private File mTempSystemCarDir;
    // Not used directly, but sets proper mockStatic() expectations on Settings
    @SuppressWarnings("UnusedVariable")
    private MockSettings mMockSettings;

    private final TestTimeSource mTimeSource = new TestTimeSource();
    private final SparseArray<String> mGenericPackageNameByUid = new SparseArray<>();
    private final SparseArray<List<String>> mPackagesBySharedUid = new SparseArray<>();
    private final ArrayMap<String, android.content.pm.PackageInfo> mPmPackageInfoByUserPackage =
            new ArrayMap<>();
    private final ArraySet<String> mDisabledUserPackages = new ArraySet<>();
    private final SparseArray<String> mDisabledPackagesSettingsStringByUserid = new SparseArray<>();
    private final Set<WatchdogStorage.UserPackageSettingsEntry> mUserPackageSettingsEntries =
            new ArraySet<>();
    private final List<WatchdogStorage.IoUsageStatsEntry> mIoUsageStatsEntries = new ArrayList<>();
    private final List<AtomsProto.CarWatchdogSystemIoUsageSummary> mPulledSystemIoUsageSummaries =
            new ArrayList<>();
    private final List<AtomsProto.CarWatchdogUidIoUsageSummary> mPulledUidIoUsageSummaries =
            new ArrayList<>();
    private final IPackageManager mSpiedPackageManager = spy(ActivityThread.getPackageManager());

    public CarWatchdogServiceUnitTest() {
        super(CarWatchdogService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        mMockSettings = new MockSettings(builder);
        builder
            .spyStatic(ServiceManager.class)
            .spyStatic(Binder.class)
            .spyStatic(ActivityManager.class)
            .spyStatic(ActivityThread.class)
            .spyStatic(CarLocalServices.class)
            .spyStatic(CarStatsLog.class)
            .spyStatic(CarServiceUtils.class)
            .spyStatic(BuiltinPackageDependency.class)
            .spyStatic(SystemProperties.class);
    }

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getSystemService(StatsManager.class)).thenReturn(mMockStatsManager);
        when(mMockContext.getPackageName()).thenReturn(
                CarWatchdogServiceUnitTest.class.getCanonicalName());
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(
                com.android.car.R.integer.watchdogUserPackageSettingsResetDays))
                .thenReturn(PACKAGE_KILLABLE_STATE_RESET_DAYS);
        when(mMockResources.getInteger(
                com.android.car.R.integer.recurringResourceOverusePeriodInDays))
                .thenReturn(RECURRING_OVERUSE_PERIOD_IN_DAYS);
        when(mMockResources.getInteger(
                com.android.car.R.integer.recurringResourceOveruseTimes))
                .thenReturn(RECURRING_OVERUSE_TIMES);
        when(mMockResources.getInteger(
                com.android.car.R.integer.uidIoUsageSummaryTopCount))
                .thenReturn(UID_IO_USAGE_SUMMARY_TOP_COUNT);
        when(mMockResources.getInteger(
                com.android.car.R.integer.ioUsageSummaryMinSystemTotalWrittenBytes))
                .thenReturn(IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES);
        doReturn(mMockSystemInterface)
                .when(() -> CarLocalServices.getService(SystemInterface.class));
        doReturn(mMockCarPowerManagementService)
                .when(() -> CarLocalServices.getService(CarPowerManagementService.class));
        doReturn(mMockCarUserService)
                .when(() -> CarLocalServices.getService(CarUserService.class));
        doReturn(mMockCarUxRestrictionsManagerService)
                .when(() -> CarLocalServices.getService(CarUxRestrictionsManagerService.class));
        doReturn(mSpiedPackageManager).when(() -> ActivityThread.getPackageManager());
        when(mMockBuiltinPackageContext.getClassLoader()).thenReturn(mMockClassLoader);
        doReturn(NotificationHelper.class).when(mMockClassLoader).loadClass(any());
        doReturn(mMockNotificationHelper)
                .when(() -> BuiltinPackageDependency.createNotificationHelper(
                        mMockBuiltinPackageContext));
        doReturn(MISSING_INT_PROPERTY_VALUE).when(
                () -> SystemProperties.getInt(PROPERTY_RO_CLIENT_HEALTHCHECK_INTERVAL,
                        MISSING_INT_PROPERTY_VALUE));

        CarServiceHelperWrapper wrapper = CarServiceHelperWrapper.create();
        wrapper.setCarServiceHelper(mMockCarServiceHelper);

        when(mMockCarUxRestrictionsManagerService.getCurrentUxRestrictions())
                .thenReturn(new CarUxRestrictions.Builder(/* reqOpt= */ false,
                        UX_RESTRICTIONS_BASELINE, /* time= */ 0).build());

        mTempSystemCarDir = Files.createTempDirectory("watchdog_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);

        File tempDbFile = new File(mTempSystemCarDir.getPath(), DATABASE_NAME);
        when(mMockContext.createDeviceProtectedStorageContext()).thenReturn(mMockContext);
        when(mMockContext.getDatabasePath(DATABASE_NAME)).thenReturn(tempDbFile);
        mSpiedWatchdogStorage =
                spy(new WatchdogStorage(mMockContext, /* useDataSystemCarDir= */ false,
                        mTimeSource));

        setupUsers();
        mockWatchdogDaemon();
        mockWatchdogStorage();
        mockPackageManager();
        mockBuildStatsEventCalls();
        mockSettingsStringCalls();

        mTimeSource.updateNow(/* numDaysAgo= */ 0);
        mCarWatchdogService = new CarWatchdogService(mMockContext, mMockBuiltinPackageContext,
                mSpiedWatchdogStorage, mTimeSource, mMockWatchdogProcessHandler);
        initService(/* wantedInvocations= */ 1);
    }

    /**
     * Releases resources.
     */
    @After
    public void tearDown() throws Exception {
        if (mIsDaemonCrashed) {
            /* Note: On daemon crash, CarWatchdogService retries daemon connection on the main
             * thread. This retry outlives the test and impacts other test runs. Thus always call
             * restartWatchdogDaemonAndAwait after crashing the daemon and before completing
             * teardown.
             */
            restartWatchdogDaemonAndAwait();
        }
        if (mTempSystemCarDir != null) {
            FileUtils.deleteContentsAndDir(mTempSystemCarDir);
        }
        CarLocalServices.removeServiceForTest(CarServiceHelperWrapper.class);
    }

    @Test
    public void testCarWatchdogServiceHealthCheck() throws Exception {
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);

        verify(mMockWatchdogProcessHandler).postHealthCheckMessage(eq(123456));
    }

    @Test
    public void testTellClientAlive() throws Exception {
        TestClient client = new TestClient();

        mCarWatchdogService.tellClientAlive(client, TIMEOUT_CRITICAL);

        verify(mMockWatchdogProcessHandler).tellClientAlive(eq(client), eq(TIMEOUT_CRITICAL));
    }

    @Test
    public void testRegisterClient() throws Exception {
        TestClient client = new TestClient();

        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);

        verify(mMockWatchdogProcessHandler).registerClient(eq(client), eq(TIMEOUT_CRITICAL));
    }

    @Test
    public void testUnregisterClient() throws Exception {
        TestClient client = new TestClient();

        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);
        mCarWatchdogService.unregisterClient(client);

        verify(mMockWatchdogProcessHandler).unregisterClient(eq(client));
    }

    @Test
    public void testRequestAidlVhalPid() throws Exception {
        mWatchdogServiceForSystemImpl.requestAidlVhalPid();

        verify(mMockWatchdogProcessHandler, timeout(MAX_WAIT_TIME_MS)).asyncFetchAidlVhalPid();
    }

    // TODO(b/262301082): Add a unit test to verify the race condition that caused watchdog to
    //  incorrectly terminate clients that were recently unregistered - b/261766872.

    @Test
    public void testGarageModeStateChangeToOn() throws Exception {
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(ACTION_GARAGE_MODE_ON));
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.GARAGE_MODE,
                GarageMode.GARAGE_MODE_ON, MISSING_ARG_VALUE);
        verify(mSpiedWatchdogStorage).shrinkDatabase();
    }

    @Test
    public void testGarageModeStateChangeToOff() throws Exception {
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(ACTION_GARAGE_MODE_OFF));
        // GARAGE_MODE_OFF is notified twice: Once during the initial daemon connect and once when
        // the ACTION_GARAGE_MODE_OFF intent is received.
        verify(mMockCarWatchdogDaemon, times(2)).notifySystemStateChange(StateType.GARAGE_MODE,
                GarageMode.GARAGE_MODE_OFF, MISSING_ARG_VALUE);
        verify(mSpiedWatchdogStorage, never()).shrinkDatabase();
    }

    @Test
    @FlakyTest(bugId = 294137799)
    public void testWatchdogDaemonRestart() throws Exception {
        crashWatchdogDaemon();

        mockUmGetUserHandles(mMockUserManager, /* excludeDying= */ false, 101, 102);
        mockUmIsUserRunning(mMockUserManager, /* userId= */ 101, /* isRunning= */ false);
        mockUmIsUserRunning(mMockUserManager, /* userId= */ 102, /* isRunning= */ true);
        setCarPowerState(CarPowerManager.STATE_SHUTDOWN_ENTER);
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(ACTION_GARAGE_MODE_ON));

        restartWatchdogDaemonAndAwait();

        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 101,
                UserState.USER_STATE_STOPPED);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 102,
                UserState.USER_STATE_STARTED);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, MISSING_ARG_VALUE);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.GARAGE_MODE,
                GarageMode.GARAGE_MODE_ON, MISSING_ARG_VALUE);
    }

    @Test
    public void testUserSwitchingLifecycleEvents() throws Exception {
        mUserLifecycleListener.onEvent(
                new CarUserManager.UserLifecycleEvent(
                        USER_LIFECYCLE_EVENT_TYPE_SWITCHING, 100, 101));
        mUserLifecycleListener.onEvent(
                new CarUserManager.UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, 101));
        mUserLifecycleListener.onEvent(
                new CarUserManager.UserLifecycleEvent(
                        USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED, 101));

        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 101,
                UserState.USER_STATE_SWITCHING);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 101,
                UserState.USER_STATE_UNLOCKING);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 101,
                UserState.USER_STATE_POST_UNLOCKED);
    }

    @Test
    public void testUserRemovedBroadcast() throws Exception {
        mockUmGetUserHandles(mMockUserManager, /* excludeDying= */ true, 101, 102);
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(Intent.ACTION_USER_REMOVED)
                        .putExtra(Intent.EXTRA_USER, UserHandle.of(100)));
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.USER_STATE, 100,
                UserState.USER_STATE_REMOVED);
        verify(mSpiedWatchdogStorage).syncUsers(new int[] {101, 102});
    }

    @Test
    public void testPowerCycleStateChangesDuringSuspend() throws Exception {
        setCarPowerState(CarPowerManager.STATE_SUSPEND_ENTER);
        setCarPowerState(CarPowerManager.STATE_SUSPEND_EXIT);
        setCarPowerState(CarPowerManager.STATE_ON);

        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, MISSING_ARG_VALUE);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SUSPEND_EXIT, MISSING_ARG_VALUE);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_RESUME, MISSING_ARG_VALUE);
    }

    @Test
    public void testPowerCycleStateChangesDuringHibernation() throws Exception {
        setCarPowerState(CarPowerManager.STATE_HIBERNATION_ENTER);
        setCarPowerState(CarPowerManager.STATE_HIBERNATION_EXIT);
        setCarPowerState(CarPowerManager.STATE_ON);

        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, MISSING_ARG_VALUE);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SUSPEND_EXIT, MISSING_ARG_VALUE);
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_RESUME, MISSING_ARG_VALUE);
    }

    @Test
    public void testDeviceRebootBroadcast() throws Exception {
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(Intent.ACTION_REBOOT)
                        .setFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, /* arg2= */ 0);
    }

    @Test
    public void testDeviceShutdownBroadcast() throws Exception {
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(Intent.ACTION_SHUTDOWN)
                        .setFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        verify(mMockCarWatchdogDaemon).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, /* arg2= */ 0);
    }

    @Test
    public void testDeviceShutdownBroadcastWithoutFlagReceiverForeground() throws Exception {
        mBroadcastReceiver.onReceive(mMockContext,
                new Intent().setAction(Intent.ACTION_SHUTDOWN));
        verify(mMockCarWatchdogDaemon, never()).notifySystemStateChange(StateType.POWER_CYCLE,
                PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, /* arg2= */ 0);
    }

    @Test
    public void testDisableAppBroadcast() throws Exception {
        String packageName = "system_package";
        UserHandle userHandle = UserHandle.of(100);

        mBroadcastReceiver.onReceive(mMockContext, new Intent(
                CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP).putExtra(
                Intent.EXTRA_PACKAGE_NAME, packageName).putExtra(Intent.EXTRA_USER,
                userHandle).putExtra(INTENT_EXTRA_NOTIFICATION_ID,
                RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));

        verify(mSpiedPackageManager).getApplicationEnabledSetting(packageName,
                userHandle.getIdentifier());

        verify(mSpiedPackageManager).setApplicationEnabledSetting(eq(packageName),
                eq(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED), eq(0),
                eq(userHandle.getIdentifier()), anyString());

        verifyDisabledPackages(/* userPackagesCsv= */ "100:system_package");

        verifyNoMoreInteractions(mSpiedPackageManager);
        captureAndVerifyCancelNotificationAsUser(userHandle, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);
    }

    @Test
    public void testDisableAppBroadcastWithDisabledPackage() throws Exception {
        String packageName = "system_package";
        UserHandle userHandle = UserHandle.of(100);

        doReturn(COMPONENT_ENABLED_STATE_DISABLED).when(mSpiedPackageManager)
                .getApplicationEnabledSetting(packageName, userHandle.getIdentifier());

        mBroadcastReceiver.onReceive(mMockContext, new Intent(
                CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER, userHandle)
                .putExtra(INTENT_EXTRA_NOTIFICATION_ID, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));

        verify(mSpiedPackageManager).getApplicationEnabledSetting(packageName,
                userHandle.getIdentifier());

        verifyNoMoreInteractions(mSpiedPackageManager);
        captureAndVerifyCancelNotificationAsUser(userHandle, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);
    }

    @Test
    public void testLaunchAppSettingsBroadcast() throws Exception {
        String packageName = "system_package";
        UserHandle userHandle = UserHandle.of(100);

        mBroadcastReceiver.onReceive(mMockContext, new Intent(
                CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER, userHandle)
                .putExtra(INTENT_EXTRA_NOTIFICATION_ID, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));

        verify(mMockBuiltinPackageContext)
                .startActivityAsUser(mIntentCaptor.capture(), eq(userHandle));

        Intent actualIntent = mIntentCaptor.getValue();

        assertWithMessage("Launch app settings intent action").that(actualIntent.getAction())
                .isEqualTo(ACTION_APPLICATION_DETAILS_SETTINGS);

        assertWithMessage("Launch app settings intent data string")
                .that(actualIntent.getDataString()).contains(packageName);

        assertWithMessage("Launch app settings intent flags").that(actualIntent.getFlags())
                .isEqualTo(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);

        verifyNoMoreInteractions(mSpiedPackageManager);
        captureAndVerifyCancelNotificationAsUser(userHandle, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);
    }

    @Test
    public void testDismissUserNotificationBroadcast() throws Exception {
        String packageName = "system_package";
        UserHandle userHandle = UserHandle.of(100);

        mBroadcastReceiver.onReceive(mMockContext,
                new Intent(CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION)
                        .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                        .putExtra(Intent.EXTRA_USER, userHandle)
                        .putExtra(INTENT_EXTRA_NOTIFICATION_ID,
                                RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));

        verifyNoMoreInteractions(mSpiedPackageManager);
        captureAndVerifyCancelNotificationAsUser(userHandle, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);
    }

    @Test
    public void testUserNotificationActionBroadcastsWithNullPackageName() throws Exception {
        List<String> actions = Arrays.asList(CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP,
                CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS,
                CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION);

        for (String action : actions) {
            mBroadcastReceiver.onReceive(mMockContext, new Intent(action)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(100))
                    .putExtra(INTENT_EXTRA_NOTIFICATION_ID, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));
        }

        verify(mMockBuiltinPackageContext, never()).startActivityAsUser(any(), any());
        verifyNoMoreInteractions(mSpiedPackageManager);
        verify(mMockNotificationHelper, never()).cancelNotificationAsUser(any(), anyInt());
    }

    @Test
    public void testUserNotificationActionBroadcastsWithInvalidUserId() throws Exception {
        List<String> actions = Arrays.asList(CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP,
                CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS,
                CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION);

        for (String action : actions) {
            mBroadcastReceiver.onReceive(mMockContext, new Intent(action)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, "system_package")
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(-1))
                    .putExtra(INTENT_EXTRA_NOTIFICATION_ID, RESOURCE_OVERUSE_NOTIFICATION_BASE_ID));
        }

        verify(mMockBuiltinPackageContext, never()).startActivityAsUser(any(), any());
        verifyNoMoreInteractions(mSpiedPackageManager);
        verify(mMockNotificationHelper, never()).cancelNotificationAsUser(any(), anyInt());
    }

    @Test
    public void testUserNotificationActionBroadcastsWithMissingNotificationId() throws Exception {
        String packageName = "system_package";
        UserHandle userHandle = UserHandle.of(100);

        List<String> actions = Arrays.asList(CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP,
                CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS,
                CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION);

        for (String action : actions) {
            mBroadcastReceiver.onReceive(mMockContext, new Intent(action)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(Intent.EXTRA_USER, userHandle));
        }

        verify(mSpiedPackageManager).getApplicationEnabledSetting(packageName,
                userHandle.getIdentifier());

        verify(mSpiedPackageManager).setApplicationEnabledSetting(eq(packageName),
                eq(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED), eq(0),
                eq(userHandle.getIdentifier()), anyString());

        verifyDisabledPackages(/* userPackagesCsv= */ "100:system_package");

        verify(mMockBuiltinPackageContext).startActivityAsUser(any(), any());

        verify(mMockNotificationHelper, never()).cancelNotificationAsUser(any(), anyInt());
    }

    @Test
    public void testHandlePackageChangedBroadcastForEnabledPackage() throws Exception {
        String packageName = "system_package";
        int userId = 100;

        disableUserPackage("system_package", 100, 101);
        disableUserPackage("vendor_package", 100);
        disableUserPackage("third_party_package", 100);

        doReturn(COMPONENT_ENABLED_STATE_ENABLED).when(mSpiedPackageManager)
                .getApplicationEnabledSetting(or(eq("system_package"),
                        eq("irrelevant_random_package")), eq(100));

        mBroadcastReceiver.onReceive(mMockContext, new Intent(ACTION_PACKAGE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, userId)
                .setData(Uri.parse("package:" + packageName)));

        mBroadcastReceiver.onReceive(mMockContext, new Intent(ACTION_PACKAGE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, userId)
                .setData(Uri.parse("package:irrelevant_random_package")));

        verifyDisabledPackagesSettingsKey(
                /* message= */ " after enabling system_package for user 100",
                /* userPackagesCsv= */
                "100:vendor_package,100:third_party_package,101:system_package");
    }

    @Test
    public void testHandlePackageChangedBroadcastForDisabledPackage() throws Exception {
        String packageName = "system_package";
        int userId = 100;

        disableUserPackage("system_package", 100, 101);
        disableUserPackage("vendor_package", 100);

        doReturn(COMPONENT_ENABLED_STATE_DISABLED).when(mSpiedPackageManager)
                .getApplicationEnabledSetting("system_package", 100);

        mBroadcastReceiver.onReceive(mMockContext, new Intent(ACTION_PACKAGE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, userId)
                .setData(Uri.parse("package:" + packageName)));

        verifyDisabledPackagesSettingsKey(
                /* message= */ "",
                /* userPackagesCsv= */ "100:vendor_package,100:system_package,101:system_package");
    }

    @Test
    public void testGetResourceOveruseStats() throws Exception {
        int uid = Binder.getCallingUid();
        injectPackageInfos(Collections.singletonList(
                constructPackageManagerPackageInfo(
                        mMockContext.getPackageName(), uid, null, ApplicationInfo.FLAG_SYSTEM, 0)));

        SparseArray<PackageIoOveruseStats> packageIoOveruseStatsByUid =
                injectIoOveruseStatsForPackages(
                        mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                        /* shouldNotifyPackages= */ new ArraySet<>());

        ResourceOveruseStats expectedStats =
                constructResourceOveruseStats(uid, mMockContext.getPackageName(),
                        packageIoOveruseStatsByUid.get(uid).ioOveruseStats);

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);

        verifyNoMoreInteractions(mSpiedWatchdogStorage);
    }

    @Test
    public void testGetResourceOveruseStatsForPast7days() throws Exception {
        int uid = Binder.getCallingUid();
        String packageName = mMockContext.getPackageName();
        injectPackageInfos(Collections.singletonList(constructPackageManagerPackageInfo(
                packageName, uid, null, ApplicationInfo.FLAG_SYSTEM, 0)));

        long startTime = mTimeSource.getCurrentDateTime().minusDays(4).toEpochSecond();
        long duration = mTimeSource.now().getEpochSecond() - startTime;
        doReturn(new IoOveruseStats.Builder(startTime, duration).setTotalOveruses(5)
                .setTotalTimesKilled(2).setTotalBytesWritten(24_000).build())
                .when(mSpiedWatchdogStorage)
                .getHistoricalIoOveruseStats(UserHandle.getUserId(uid), packageName, 6);

        injectIoOveruseStatsForPackages(mGenericPackageNameByUid,
                /* killablePackages= */ Collections.singleton(packageName),
                /* shouldNotifyPackages= */ new ArraySet<>());

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_PAST_7_DAYS);

        IoOveruseStats ioOveruseStats =
                new IoOveruseStats.Builder(startTime, duration + STATS_DURATION_SECONDS)
                        .setKillableOnOveruse(true).setTotalOveruses(8).setTotalBytesWritten(24_600)
                        .setTotalTimesKilled(2)
                        .setRemainingWriteBytes(new PerStateBytes(20, 20, 20)).build();

        ResourceOveruseStats expectedStats =
                new ResourceOveruseStats.Builder(packageName, UserHandle.getUserHandleForUid(uid))
                        .setIoOveruseStats(ioOveruseStats).build();

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);
    }

    @Test
    public void testGetResourceOveruseStatsForPast7daysWithNoHistory() throws Exception {
        int uid = Binder.getCallingUid();
        String packageName = mMockContext.getPackageName();
        injectPackageInfos(Collections.singletonList(constructPackageManagerPackageInfo(
                packageName, uid, null, ApplicationInfo.FLAG_SYSTEM, 0)));

        doReturn(null).when(mSpiedWatchdogStorage)
                .getHistoricalIoOveruseStats(UserHandle.getUserId(uid), packageName, 6);

        injectIoOveruseStatsForPackages(mGenericPackageNameByUid,
                /* killablePackages= */ Collections.singleton(packageName),
                /* shouldNotifyPackages= */ new ArraySet<>());

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_PAST_7_DAYS);

        ResourceOveruseStats expectedStats =
                new ResourceOveruseStats.Builder(packageName, UserHandle.getUserHandleForUid(uid))
                        .setIoOveruseStats(new IoOveruseStats.Builder(
                                mTimeSource.now().getEpochSecond(), STATS_DURATION_SECONDS)
                                .setKillableOnOveruse(true).setTotalOveruses(3)
                                .setTotalBytesWritten(600)
                                .setRemainingWriteBytes(new PerStateBytes(20, 20, 20)).build())
                        .build();

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);
    }

    @Test
    public void testGetResourceOveruseStatsForPast7daysWithNoCurrentStats() throws Exception {
        int uid = Binder.getCallingUid();
        String packageName = mMockContext.getPackageName();
        injectPackageInfos(Collections.singletonList(constructPackageManagerPackageInfo(
                packageName, uid, null, ApplicationInfo.FLAG_SYSTEM, 0)));

        long startTime = mTimeSource.getCurrentDateTime().minusDays(4).toEpochSecond();
        long duration = mTimeSource.now().getEpochSecond() - startTime;
        doReturn(new IoOveruseStats.Builder(startTime, duration).setTotalOveruses(5)
                .setTotalTimesKilled(2).setTotalBytesWritten(24_000).build())
                .when(mSpiedWatchdogStorage)
                .getHistoricalIoOveruseStats(UserHandle.getUserId(uid), packageName, 6);

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_PAST_7_DAYS);

        ResourceOveruseStats expectedStats =
                new ResourceOveruseStats.Builder(packageName, UserHandle.getUserHandleForUid(uid))
                .build();

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);
    }

    @Test
    public void testGetResourceOveruseStatsForSharedUid() throws Exception {
        int sharedUid = Binder.getCallingUid();
        injectPackageInfos(Collections.singletonList(
                constructPackageManagerPackageInfo(
                        mMockContext.getPackageName(), sharedUid, "system_shared_package",
                        ApplicationInfo.FLAG_SYSTEM, 0)));

        SparseArray<PackageIoOveruseStats> packageIoOveruseStatsByUid =
                injectIoOveruseStatsForPackages(
                        mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                        /* shouldNotifyPackages= */ new ArraySet<>());

        ResourceOveruseStats expectedStats =
                constructResourceOveruseStats(sharedUid, "shared:system_shared_package",
                        packageIoOveruseStatsByUid.get(sharedUid).ioOveruseStats);

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);
    }

    // TODO(b/262301082): Add a test for testGetAllResourceOveruseStats when tests have all been
    // moved to WatchdogPerfHandlerUnitTest.

    // TODO(b/262301082): Update with WatchdogPerfHandler.getResourceOveruseStats
    // when tests have all been moved to WatchdogPerfHandlerUnitTest.
    @Test
    public void testFailsGetResourceOveruseStatsWithInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStats(/* resourceOveruseFlag= */ 0,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStats(
                        FLAG_RESOURCE_OVERUSE_IO, /* maxStatsPeriod= */ 0));
    }

    // TODO(b/262301082): Add a test for addResourceOveruseListener,
    //  addResourceOveruseListenerForSystem, removeResourceOveruseListenerForSystem and
    //  removeResourceOveruseListener using mMockWatchdogPerfHandler when all relevant tests have
    //  been moved to WatchdogPerfHandlerUnitTest.

    // TODO(b/262301082): Add a test for onLatestResourceStats when all relevant tests have
    // been moved to WatchdogPerfHandlerUnitTest.

    // TODO(b/262301082): Update with mMockWatchdogPerfHandler when tests have all been moved to
    // WatchdogPerfHandlerUnitTest.
    @Test
    public void testSetKillablePackageAsUser() throws Exception {
        mockUmGetUserHandles(mMockUserManager, /* excludeDying= */ true, 101, 102);
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("third_party_package", 10103456, null),
                constructPackageManagerPackageInfo("vendor_package.critical", 10101278, null),
                constructPackageManagerPackageInfo("third_party_package", 10203456, null),
                constructPackageManagerPackageInfo("vendor_package.critical", 10201278, null)));

        UserHandle userHandle = UserHandle.of(101);
        mCarWatchdogService.setKillablePackageAsUser("third_party_package", userHandle,
                /* isKillable= */ false);
        mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                userHandle, /* isKillable= */ false);

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 101,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 101,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 102,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 102,
                        PackageKillableState.KILLABLE_STATE_NEVER));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        userHandle, /* isKillable= */ true));

        mockUmGetUserHandles(mMockUserManager, /* excludeDying= */ true, 101, 102, 103);
        injectPackageInfos(Collections.singletonList(
                constructPackageManagerPackageInfo("third_party_package", 10303456, null)));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 101,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 101,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 102,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 102,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 103,
                        PackageKillableState.KILLABLE_STATE_YES));

        verify(mSpiedWatchdogStorage, times(11)).markDirty();
    }

    // TODO(b/262301082): Update with a verify on WatchdogPerfHandler.getPackageKillableStatesAsUser
    // when tests have all been moved to WatchdogPerfHandlerUnitTest.
    @Test
    public void testGetPackageKillableStatesAsUser() throws Exception {
        mockUmGetUserHandles(mMockUserManager, /* excludeDying= */ true, 101, 102);
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("third_party_package", 10103456, null),
                constructPackageManagerPackageInfo("vendor_package.critical", 10101278, null),
                constructPackageManagerPackageInfo("third_party_package", 10203456, null),
                constructPackageManagerPackageInfo("vendor_package.critical", 10201278, null)));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.of(101)))
                .containsExactly(
                        new PackageKillableState("third_party_package", 101,
                                PackageKillableState.KILLABLE_STATE_YES),
                        new PackageKillableState("vendor_package.critical", 101,
                                PackageKillableState.KILLABLE_STATE_NEVER));

        verify(mSpiedWatchdogStorage, times(2)).markDirty();
    }

    // TODO(b/262301082): Update with mMockWatchdogPerfHandler when tests have all been moved to
    // WatchdogPerfHandlerUnitTest.
    @Test
    public void testSetResourceOveruseConfigurations() throws Exception {
        assertThat(mCarWatchdogService.setResourceOveruseConfigurations(
                sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(CarWatchdogManager.RETURN_CODE_SUCCESS);

        InternalResourceOveruseConfigurationSubject
                .assertThat(captureOnSetResourceOveruseConfigurations())
                .containsExactlyElementsIn(sampleInternalResourceOveruseConfigurations());

        // CarService fetches and syncs resource overuse configuration on the main thread by posting
        // a new message. Wait until this completes.
        CarServiceUtils.runOnMainSync(() -> {});

        /* Expect two calls, the first is made at car watchdog service init */
        verify(mMockCarWatchdogDaemon, times(2)).getResourceOveruseConfigurations();
    }

    // TODO(b/262301082): Update with mMockWatchdogPerfHandler when tests have all been moved to
    // WatchdogPerfHandlerUnitTest.
    @Test
    public void testGetResourceOveruseConfigurations() throws Exception {
        List<ResourceOveruseConfiguration> actualConfigs =
                mCarWatchdogService.getResourceOveruseConfigurations(
                        FLAG_RESOURCE_OVERUSE_IO);

        ResourceOveruseConfigurationSubject.assertThat(actualConfigs)
                .containsExactlyElementsIn(sampleResourceOveruseConfigurations());
    }

    @Test
    public void testFailsGetResourceOveruseConfigurationsOnInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseConfigurations(0));
    }

    //TODO(b/262301082): When using a mocked WatchdogPerfHandler, verify that a call to
    // asyncFetchTodayIoUsageStats is made.
    @Test
    public void testRequestTodayIoUsageStats() throws Exception {
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = Arrays.asList(
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package", /* startTime */ 0,
                        /* duration= */1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* forgivenOveruses= */ 0,
                        /* totalTimesKilled= */ 1),
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 101, "vendor_package", /* startTime */ 0,
                        /* duration= */ 1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(500, 600, 700),
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4, /* forgivenOveruses= */ 1,
                        /* totalTimesKilled= */ 10));
        when(mSpiedWatchdogStorage.getTodayIoUsageStats()).thenReturn(ioUsageStatsEntries);
        when(mMockCarWatchdogDaemon.getInterfaceVersion())
                .thenReturn(CARWATCHDOG_DAEMON_INTERFACE_VERSION_UDC);

        mWatchdogServiceForSystemImpl.requestTodayIoUsageStats();

        ArgumentCaptor<List<UserPackageIoUsageStats>> userPackageIoUsageStatsCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(mMockCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS))
                .onTodayIoUsageStatsFetched(userPackageIoUsageStatsCaptor.capture());

        List<UserPackageIoUsageStats> expectedStats = Arrays.asList(
                constructUserPackageIoUsageStats(/* userId= */ 100, "system_package",
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2),
                constructUserPackageIoUsageStats(/* userId= */ 101, "vendor_package",
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4));

        assertThat(userPackageIoUsageStatsCaptor.getValue())
                .comparingElementsUsing(Correspondence.from(
                        CarWatchdogServiceUnitTest::isUserPackageIoUsageStatsEquals,
                        "is user package I/O usage stats equal to"))
                        .containsExactlyElementsIn(expectedStats);
    }

    //TODO(b/262301082): Replace with a verify of getTodayIoUsageStats when a mock
    // WatchdogPerfHandler is used.
    @Test
    public void testGetTodayIoUsageStats() throws Exception {
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = Arrays.asList(
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package", /* startTime */ 0,
                        /* duration= */1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* forgivenOveruses= */ 0,
                        /* totalTimesKilled= */ 1),
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 101, "vendor_package", /* startTime */ 0,
                        /* duration= */ 1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(500, 600, 700),
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4, /* forgivenOveruses= */ 1,
                        /* totalTimesKilled= */ 10));
        when(mSpiedWatchdogStorage.getTodayIoUsageStats()).thenReturn(ioUsageStatsEntries);

        List<UserPackageIoUsageStats> actualStats =
                mWatchdogServiceForSystemImpl.getTodayIoUsageStats();

        List<UserPackageIoUsageStats> expectedStats = Arrays.asList(
                constructUserPackageIoUsageStats(/* userId= */ 100, "system_package",
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2),
                constructUserPackageIoUsageStats(/* userId= */ 101, "vendor_package",
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4));

        assertThat(actualStats).comparingElementsUsing(Correspondence.from(
                CarWatchdogServiceUnitTest::isUserPackageIoUsageStatsEquals,
                "is user package I/O usage stats equal to"))
                .containsExactlyElementsIn(expectedStats);
    }

    //TODO(b/262301082): Replace with a verify of WatchdogPerfHandler.resetResourceOveruseStats when
    // a mock WatchdogPerfHandler is used.
    @Test
    public void testResetResourceOveruseStatsResetsStats() throws Exception {
        UserHandle user = UserHandle.getUserHandleForUid(10003346);
        String packageName = mMockContext.getPackageName();
        mGenericPackageNameByUid.put(10003346, packageName);
        mGenericPackageNameByUid.put(10101278, "vendor_package.critical");
        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>());

        mWatchdogServiceForSystemImpl.resetResourceOveruseStats(
                Collections.singletonList(packageName));

        // Resetting resource overuse stats is done on the CarWatchdogService service handler
        // thread. Wait until the below message is processed before returning, so the resource
        // overuse stats resetting is completed.
        CarServiceUtils.runEmptyRunnableOnLooperSync(CarWatchdogService.class.getSimpleName());

        ResourceOveruseStats actualStats =
                mCarWatchdogService.getResourceOveruseStatsForUserPackage(
                        packageName, user,
                        FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStats expectedStats = new ResourceOveruseStats.Builder(
                packageName, user).build();

        ResourceOveruseStatsSubject.assertEquals(actualStats, expectedStats);

        verify(mSpiedWatchdogStorage).deleteUserPackage(eq(user.getIdentifier()), eq(packageName));
    }

    @Test
    public void testPullSystemIoUsageSummaryAtomsWithRestart() throws Exception {
        List<StatsEvent> events = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        List<AtomsProto.CarWatchdogSystemIoUsageSummary> expectedSummaries =
                verifyAndGetSystemIoUsageSummaries(
                        mTimeSource.getCurrentDate().minus(RETENTION_PERIOD));

        assertWithMessage("First pulled system I/O usage summary atoms")
                .that(mPulledSystemIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);
        mPulledSystemIoUsageSummaries.clear();

        restartService(/* totalRestarts= */ 1, /* wantedDbWrites= */ 0);

        assertWithMessage("Status of stats pull atom callback after restart")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        assertWithMessage("Pulled system I/O usage summary atoms after restart")
                .that(mPulledSystemIoUsageSummaries).isEmpty();

        verifyNoMoreInteractions(mSpiedWatchdogStorage);
    }

    @Test
    public void testPullSystemIoUsageSummaryAtomsWithDateChange() throws Exception {
        mTimeSource.updateNow(/* numDaysAgo= */ 7);

        List<StatsEvent> events = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        List<AtomsProto.CarWatchdogSystemIoUsageSummary> expectedSummaries =
                verifyAndGetSystemIoUsageSummaries(
                        mTimeSource.getCurrentDate().minus(RETENTION_PERIOD));

        assertWithMessage("First pulled system I/O usage summary atoms")
                .that(mPulledSystemIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);
        mPulledSystemIoUsageSummaries.clear();

        mTimeSource.updateNow(/* numDaysAgo= */ 6);

        assertWithMessage("Status of stats pull atom callback within the same week")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        assertWithMessage("Pulled system I/O usage summary atoms within the same week")
                .that(mPulledSystemIoUsageSummaries).isEmpty();

        mTimeSource.updateNow(/* numDaysAgo= */ 0);

        assertWithMessage("Status of stats pull atom callback after a week")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        expectedSummaries = verifyAndGetSystemIoUsageSummaries(
                mTimeSource.getCurrentDate().minus(1, ChronoUnit.WEEKS));

        assertWithMessage("Pulled system I/O usage summary atoms after a week")
                .that(mPulledSystemIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);

        verifyNoMoreInteractions(mSpiedWatchdogStorage);
    }

    @Test
    public void testPullUidIoUsageSummaryAtomsForTopUids() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("system_package.critical.A", 10000345, null),
                constructPackageManagerPackageInfo("third_party_package.B", 10004675, null),
                constructPackageManagerPackageInfo("system_package.critical.B", 10010001, null),
                constructPackageManagerPackageInfo("vendor_package.non_critical", 10110004, null),
                constructPackageManagerPackageInfo("third_party_package.A", 10110005,
                        "third_party_shared_package")));

        List<StatsEvent> events = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        List<AtomsProto.CarWatchdogUidIoUsageSummary> expectedSummaries =
                verifyAndGetUidIoUsageSummaries(
                        mTimeSource.getCurrentDate().minus(RETENTION_PERIOD),
                        /* expectUids= */ Arrays.asList(10010001, 10110004, 10110005));

        assertWithMessage(String.format("Pulled uid I/O usage summary atoms for top %d UIDs",
                UID_IO_USAGE_SUMMARY_TOP_COUNT)).that(mPulledUidIoUsageSummaries)
                .containsExactlyElementsIn(expectedSummaries);
    }

    @Test
    public void testPullUidIoUsageSummaryAtomsWithRestart() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("system_package.critical", 10010001, null),
                constructPackageManagerPackageInfo("vendor_package.non_critical", 10110004, null),
                constructPackageManagerPackageInfo("third_party_package.A", 10110005,
                        "third_party_shared_package")));

        List<StatsEvent> events = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        List<AtomsProto.CarWatchdogUidIoUsageSummary> expectedSummaries =
                verifyAndGetUidIoUsageSummaries(
                        mTimeSource.getCurrentDate().minus(RETENTION_PERIOD),
                        /* expectUids= */ Arrays.asList(10010001, 10110004, 10110005));

        assertWithMessage("First pulled uid I/O usage summary atoms")
                .that(mPulledUidIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);
        mPulledUidIoUsageSummaries.clear();

        restartService(/* totalRestarts= */ 1, /* wantedDbWrites= */ 0);

        assertWithMessage("Status of stats pull atom callback after restart")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        assertWithMessage("Pulled uid I/O usage summary atoms after restart")
                .that(mPulledUidIoUsageSummaries).isEmpty();

        verifyNoMoreInteractions(mSpiedWatchdogStorage);
    }

    @Test
    public void testPullUidIoUsageSummaryAtomsWithDateChange() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("system_package.critical", 10010001, null),
                constructPackageManagerPackageInfo("vendor_package.non_critical", 10110004, null),
                constructPackageManagerPackageInfo("third_party_package.A", 10110005,
                        "third_party_shared_package")));

        mTimeSource.updateNow(/* numDaysAgo= */ 7);

        List<StatsEvent> events = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        List<AtomsProto.CarWatchdogUidIoUsageSummary> expectedSummaries =
                verifyAndGetUidIoUsageSummaries(
                        mTimeSource.getCurrentDate().minus(RETENTION_PERIOD),
                        /* expectUids= */ Arrays.asList(10010001, 10110004, 10110005));

        assertWithMessage("First pulled uid I/O usage summary atoms")
                .that(mPulledUidIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);
        mPulledUidIoUsageSummaries.clear();

        mTimeSource.updateNow(/* numDaysAgo= */ 6);

        assertWithMessage("Status of stats pull atom callback within the same week")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        assertWithMessage("Pulled uid I/O usage summary atoms within the same week")
                .that(mPulledUidIoUsageSummaries).isEmpty();

        mTimeSource.updateNow(/* numDaysAgo= */ 0);

        assertWithMessage("Status of stats pull atom callback after a week")
                .that(mStatsPullAtomCallback.onPullAtom(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                        events)).isEqualTo(PULL_SUCCESS);

        expectedSummaries = verifyAndGetUidIoUsageSummaries(
                mTimeSource.getCurrentDate().minus(1, ChronoUnit.WEEKS),
                /* expectUids= */ Arrays.asList(10010001, 10110004, 10110005));

        assertWithMessage("Pulled uid I/O usage summary atoms after a week")
                .that(mPulledUidIoUsageSummaries).containsExactlyElementsIn(expectedSummaries);

        verifyNoMoreInteractions(mSpiedWatchdogStorage);
    }

    @Test
    public void testPullInvalidAtoms() throws Exception {
        List<StatsEvent> actualEvents = new ArrayList<>();
        assertWithMessage("Stats pull atom callback status").that(mStatsPullAtomCallback.onPullAtom(
                0, actualEvents)).isEqualTo(PULL_SKIP);
        assertWithMessage("Pulled stats events").that(actualEvents).isEmpty();
    }

    @Test
    public void testGetPackageInfosForUids() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo(
                        "system_package.A", 6001, null, ApplicationInfo.FLAG_SYSTEM, 0),
                constructPackageManagerPackageInfo(
                        "vendor_package.B", 10005100, null, 0, ApplicationInfo.PRIVATE_FLAG_OEM),
                constructPackageManagerPackageInfo(
                        "vendor_package.C", 10345678, null, 0, ApplicationInfo.PRIVATE_FLAG_ODM),
                constructPackageManagerPackageInfo("third_party_package.D", 10200056, null)));

        int[] uids = new int[]{6001, 10005100, 10200056, 10345678};
        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        List<PackageInfo> expectedPackageInfos = Arrays.asList(
                constructPackageInfo("system_package.A", 6001, new ArrayList<>(),
                        UidType.NATIVE, ComponentType.SYSTEM, ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor_package.B", 10005100, new ArrayList<>(),
                        UidType.NATIVE, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party_package.D", 10200056, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor_package.C", 10345678, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR,
                        ApplicationCategoryType.OTHERS));

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testGetPackageInfosWithSharedUids() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo("system_package.A", 6050,
                        "system_shared_package", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, 0),
                constructPackageManagerPackageInfo("system_package.B", 10100035,
                        "vendor_shared_package", 0, ApplicationInfo.PRIVATE_FLAG_PRODUCT),
                constructPackageManagerPackageInfo("vendor_package.C", 10100035,
                        "vendor_shared_package", 0, ApplicationInfo.PRIVATE_FLAG_VENDOR),
                constructPackageManagerPackageInfo(
                        "third_party_package.D", 6050, "system_shared_package"),
                constructPackageManagerPackageInfo(
                        "third_party_package.E", 10100035, "vendor_shared_package"),
                constructPackageManagerPackageInfo(
                        "third_party_package.F", 10200078, "third_party_shared_package")));

        int[] uids = new int[]{6050, 10100035, 10200078};
        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        List<PackageInfo> expectedPackageInfos = Arrays.asList(
                constructPackageInfo("shared:system_shared_package", 6050,
                        Arrays.asList("system_package.A", "third_party_package.D"),
                        UidType.NATIVE, ComponentType.SYSTEM, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor_shared_package", 10100035,
                        Arrays.asList("vendor_package.C", "system_package.B",
                                "third_party_package.E"), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:third_party_shared_package", 10200078,
                        Collections.singletonList("third_party_package.F"),
                        UidType.APPLICATION,  ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS));

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testGetPackageInfosForUidsWithVendorPackagePrefixes() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo(
                        "vendor_package.A", 10010034, null, 0,
                        ApplicationInfo.PRIVATE_FLAG_PRODUCT),
                constructPackageManagerPackageInfo("vendor_pkg.B", 10010035,
                        "vendor_shared_package", ApplicationInfo.FLAG_SYSTEM, 0),
                constructPackageManagerPackageInfo(
                        "third_party_package.C", 10010035, "vendor_shared_package"),
                constructPackageManagerPackageInfo(
                        "third_party_package.D", 10010035, "vendor_shared_package"),
                constructPackageManagerPackageInfo(
                        "third_party_package.F", 10200078, "third_party_shared_package"),
                constructPackageManagerPackageInfo("vndr_pkg.G", 10206345, "vendor_package.shared",
                        ApplicationInfo.FLAG_SYSTEM, 0),
                /*
                 * A 3p package pretending to be a vendor package because 3p packages won't have the
                 * required flags.
                 */
                constructPackageManagerPackageInfo("vendor_package.imposter", 10203456, null, 0,
                        0)));

        int[] uids = new int[]{10010034, 10010035, 10200078, 10206345, 10203456};
        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, Arrays.asList("vendor_package.", "vendor_pkg.", "shared:vendor_package."));

        List<PackageInfo> expectedPackageInfos = Arrays.asList(
                constructPackageInfo("vendor_package.A", 10010034, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor_shared_package", 10010035,
                        Arrays.asList("vendor_pkg.B", "third_party_package.C",
                                "third_party_package.D"), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:third_party_shared_package", 10200078,
                        Collections.singletonList("third_party_package.F"), UidType.APPLICATION,
                        ComponentType.THIRD_PARTY, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor_package.shared", 10206345,
                        Collections.singletonList("vndr_pkg.G"), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor_package.imposter", 10203456,
                        new ArrayList<>(), UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS));

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testGetPackageInfosForUidsWithMissingApplicationInfos() throws Exception {
        injectPackageInfos(Arrays.asList(
                constructPackageManagerPackageInfo(
                        "vendor_package.A", 10100034, null, 0, ApplicationInfo.PRIVATE_FLAG_OEM),
                constructPackageManagerPackageInfo("vendor_package.B", 10100035,
                        "vendor_shared_package", 0, ApplicationInfo.PRIVATE_FLAG_VENDOR),
                constructPackageManagerPackageInfo(
                        "third_party_package.C", 10100035, "vendor_shared_package")));

        BiConsumer<Integer, String> addPackageToSharedUid = (uid, packageName) -> {
            List<String> packages = mPackagesBySharedUid.get(uid);
            if (packages == null) {
                packages = new ArrayList<>();
            }
            packages.add(packageName);
            mPackagesBySharedUid.put(uid, packages);
        };

        addPackageToSharedUid.accept(10100035, "third_party_package.G");
        mGenericPackageNameByUid.put(10200056, "third_party_package.H");
        mGenericPackageNameByUid.put(10200078, "shared:third_party_shared_package");
        addPackageToSharedUid.accept(10200078, "third_party_package.I");


        int[] uids = new int[]{10100034, 10100035, 10200056, 10200078};

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        List<PackageInfo> expectedPackageInfos = Arrays.asList(
                constructPackageInfo("vendor_package.A", 10100034, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor_shared_package", 10100035,
                        Arrays.asList("vendor_package.B", "third_party_package.C",
                                "third_party_package.G"),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party_package.H", 10200056, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.UNKNOWN,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:third_party_shared_package", 10200078,
                        Collections.singletonList("third_party_package.I"),
                        UidType.APPLICATION, ComponentType.UNKNOWN,
                        ApplicationCategoryType.OTHERS));

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testControlProcessHealthCheckEnabled() throws Exception {
        mCarWatchdogService.controlProcessHealthCheck(true);

        verify(mMockWatchdogProcessHandler).controlProcessHealthCheck(eq(true));
    }

    @Test
    public void testControlProcessHealthCheckDisabled() throws Exception {
        mCarWatchdogService.controlProcessHealthCheck(false);

        verify(mMockWatchdogProcessHandler).controlProcessHealthCheck(eq(false));
    }

    //TODO(b/262301082): Replace with a verify of WatchdogPerfHandler.disablePackageForUser when
    // a mock WatchdogPerfHandler is used.
    @Test
    public void testDisablePackageForUser() throws Exception {
        assertWithMessage("Performed resource overuse kill")
                .that(mCarWatchdogService.performResourceOveruseKill("third_party_package",
                        /* userId= */ 100)).isTrue();

        verifyDisabledPackages(/* userPackagesCsv= */ "100:third_party_package");
    }

    @Test
    public void testOveruseConfigurationCacheGetVendorPackagePrefixes() throws Exception {
        OveruseConfigurationCache cache = new OveruseConfigurationCache();

        cache.set(sampleInternalResourceOveruseConfigurations());

        assertWithMessage("Vendor package prefixes").that(cache.getVendorPackagePrefixes())
                .containsExactly("vendor_package", "some_pkg_as_vendor_pkg");
    }

    @Test
    public void testOveruseConfigurationCacheFetchThreshold() throws Exception {
        OveruseConfigurationCache cache = new OveruseConfigurationCache();

        cache.set(sampleInternalResourceOveruseConfigurations());

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("system_package.non_critical.A", ComponentType.SYSTEM),
                "System package with generic threshold")
                .isEqualTo(constructPerStateBytes(10, 20, 30));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("system_package.A", ComponentType.SYSTEM),
                "System package with package specific threshold")
                .isEqualTo(constructPerStateBytes(40, 50, 60));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("system_package.MEDIA", ComponentType.SYSTEM),
                "System package with media category threshold")
                .isEqualTo(constructPerStateBytes(200, 400, 600));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("vendor_package.non_critical.A", ComponentType.VENDOR),
                "Vendor package with generic threshold")
                .isEqualTo(constructPerStateBytes(20, 40, 60));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("vendor_package.A", ComponentType.VENDOR),
                "Vendor package with package specific threshold")
                .isEqualTo(constructPerStateBytes(80, 100, 120));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("vendor_package.MEDIA", ComponentType.VENDOR),
                "Vendor package with media category threshold")
                .isEqualTo(constructPerStateBytes(200, 400, 600));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("third_party_package.A",
                        ComponentType.THIRD_PARTY),
                "3p package with generic threshold").isEqualTo(constructPerStateBytes(30, 60, 90));

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("third_party_package.MAPS", ComponentType.VENDOR),
                "3p package with maps category threshold")
                .isEqualTo(constructPerStateBytes(2200, 4400, 6600));
    }

    @Test
    public void testOveruseConfigurationCacheIsSafeToKill() throws Exception {
        OveruseConfigurationCache cache = new OveruseConfigurationCache();

        cache.set(sampleInternalResourceOveruseConfigurations());

        assertWithMessage("isSafeToKill non-critical system package").that(cache.isSafeToKill(
                "system_package.non_critical.A", ComponentType.SYSTEM, null)).isTrue();

        assertWithMessage("isSafeToKill shared non-critical system package")
                .that(cache.isSafeToKill("system_package.A", ComponentType.SYSTEM,
                        Collections.singletonList("system_package.non_critical.A"))).isTrue();

        assertWithMessage("isSafeToKill non-critical vendor package").that(cache.isSafeToKill(
                "vendor_package.non_critical.A", ComponentType.VENDOR, null)).isTrue();

        assertWithMessage("isSafeToKill shared non-critical vendor package")
                .that(cache.isSafeToKill("vendor_package.A", ComponentType.VENDOR,
                        Collections.singletonList("vendor_package.non_critical.A"))).isTrue();

        assertWithMessage("isSafeToKill 3p package").that(cache.isSafeToKill(
                "third_party_package.A", ComponentType.THIRD_PARTY, null)).isTrue();

        assertWithMessage("isSafeToKill critical system package").that(cache.isSafeToKill(
                "system_package.A", ComponentType.SYSTEM, null)).isFalse();

        assertWithMessage("isSafeToKill critical vendor package").that(cache.isSafeToKill(
                "vendor_package.A", ComponentType.VENDOR, null)).isFalse();
    }

    @Test
    public void testOverwriteOveruseConfigurationCache() throws Exception {
        OveruseConfigurationCache cache = new OveruseConfigurationCache();

        cache.set(sampleInternalResourceOveruseConfigurations());

        cache.set(new ArrayList<>());

        assertWithMessage("Vendor package prefixes").that(cache.getVendorPackagePrefixes())
                .isEmpty();

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("system_package.A", ComponentType.SYSTEM),
                "System package with default threshold")
                .isEqualTo(OveruseConfigurationCache.DEFAULT_THRESHOLD);

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("vendor_package.A", ComponentType.VENDOR),
                "Vendor package with default threshold")
                .isEqualTo(OveruseConfigurationCache.DEFAULT_THRESHOLD);

        InternalPerStateBytesSubject.assertWithMessage(
                cache.fetchThreshold("third_party_package.A", ComponentType.THIRD_PARTY),
                "3p package with default threshold")
                .isEqualTo(OveruseConfigurationCache.DEFAULT_THRESHOLD);

        assertWithMessage("isSafeToKill any system package").that(cache.isSafeToKill(
                "system_package.non_critical.A", ComponentType.SYSTEM, null)).isFalse();

        assertWithMessage("isSafeToKill any vendor package").that(cache.isSafeToKill(
                "vendor_package.non_critical.A", ComponentType.VENDOR, null)).isFalse();
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    public static android.automotive.watchdog.PerStateBytes constructPerStateBytes(
            long fgBytes, long bgBytes, long gmBytes) {
        android.automotive.watchdog.PerStateBytes perStateBytes =
                new android.automotive.watchdog.PerStateBytes();
        perStateBytes.foregroundBytes = fgBytes;
        perStateBytes.backgroundBytes = bgBytes;
        perStateBytes.garageModeBytes = gmBytes;
        return perStateBytes;
    }

    private void mockWatchdogDaemon() throws Exception {
        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockCarWatchdogDaemon);
        when(mMockCarWatchdogDaemon.asBinder()).thenReturn(mMockBinder);
        doReturn(mMockBinder).when(
                () -> ServiceManager.checkService(CAR_WATCHDOG_DAEMON_INTERFACE));
        when(mMockCarWatchdogDaemon.getResourceOveruseConfigurations()).thenReturn(
                sampleInternalResourceOveruseConfigurations());
        mIsDaemonCrashed = false;
    }

    private void mockWatchdogStorage() {
        doAnswer((args) -> {
            mUserPackageSettingsEntries.addAll(args.getArgument(0));
            return true;
        }).when(mSpiedWatchdogStorage).saveUserPackageSettings(any());
        doAnswer((args) -> {
            List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = args.getArgument(0);
            for (WatchdogStorage.IoUsageStatsEntry entry : ioUsageStatsEntries) {
                mIoUsageStatsEntries.add(
                        new WatchdogStorage.IoUsageStatsEntry(entry.userId, entry.packageName,
                                new WatchdogPerfHandler.PackageIoUsage(
                                        entry.ioUsage.getInternalIoOveruseStats(),
                                        entry.ioUsage.getForgivenWriteBytes(),
                                        entry.ioUsage.getForgivenOveruses(),
                                        entry.ioUsage.getTotalTimesKilled())));
            }
            return ioUsageStatsEntries.size();
        }).when(mSpiedWatchdogStorage).saveIoUsageStats(any());
        doAnswer((args) -> {
            List<WatchdogStorage.UserPackageSettingsEntry> entries =
                    new ArrayList<>(mUserPackageSettingsEntries.size());
            entries.addAll(mUserPackageSettingsEntries);
            return entries;
        }).when(mSpiedWatchdogStorage).getUserPackageSettings();
        doReturn(mIoUsageStatsEntries).when(mSpiedWatchdogStorage).getTodayIoUsageStats();
        doReturn(List.of()).when(mSpiedWatchdogStorage)
                .getNotForgivenHistoricalIoOveruses(RECURRING_OVERUSE_PERIOD_IN_DAYS);
        doAnswer(args -> sampleDailyIoUsageSummariesForAWeek(args.getArgument(1),
                SYSTEM_DAILY_IO_USAGE_SUMMARY_MULTIPLIER))
                .when(mSpiedWatchdogStorage)
                .getDailySystemIoUsageSummaries(anyLong(), anyLong(), anyLong());
        doAnswer(args -> {
            ArrayList<WatchdogStorage.UserPackageDailySummaries> summaries =
                    new ArrayList<>();
            for (int i = 0; i < mGenericPackageNameByUid.size(); ++i) {
                int uid = mGenericPackageNameByUid.keyAt(i);
                summaries.add(new WatchdogStorage.UserPackageDailySummaries(
                        UserHandle.getUserId(uid), mGenericPackageNameByUid.valueAt(i),
                        sampleDailyIoUsageSummariesForAWeek(args.getArgument(2),
                                /* sysOrUidMultiplier= */ uid)));
            }
            summaries.sort(Comparator.comparingLong(WatchdogStorage
                    .UserPackageDailySummaries::getTotalWrittenBytes).reversed());
            return summaries;
        }).when(mSpiedWatchdogStorage)
                .getTopUsersDailyIoUsageSummaries(anyInt(), anyLong(), anyLong(), anyLong());
    }

    private void setupUsers() {
        when(mMockContext.getSystemService(UserManager.class)).thenReturn(mMockUserManager);
        mockUmGetAllUsers(mMockUserManager, new UserHandle[0]);
    }

    private void initService(int wantedInvocations) throws Exception {
        // TODO(b/262301082): Remove setOveruseHandlingDelay when all relevant tests have moved
        //  to WatchdogPerfHandlerUnitTest.
        mCarWatchdogService.setOveruseHandlingDelay(OVERUSE_HANDLING_DELAY_MILLS);
        mCarWatchdogService.init();
        captureCarPowerListeners(wantedInvocations);
        captureBroadcastReceiver(wantedInvocations);
        captureUserLifecycleListener(wantedInvocations);
        captureCarUxRestrictionsChangeListener(wantedInvocations);
        captureAndVerifyRegistrationWithDaemon(/* waitOnMain= */ true);
        verifyDatabaseInit(wantedInvocations);
        captureStatsPullAtomCallback(wantedInvocations);
    }

    private void restartService(int totalRestarts, int wantedDbWrites) throws Exception {
        restartService(totalRestarts, wantedDbWrites, /* isWriteIoStats= */ true);
    }

    private void restartService(int totalRestarts, int wantedDbWrites, boolean isWriteIoStats)
            throws Exception {
        setCarPowerState(CarPowerManager.STATE_SHUTDOWN_PREPARE);
        setCarPowerState(CarPowerManager.STATE_SHUTDOWN_ENTER);
        mCarWatchdogService.release();
        verify(mSpiedWatchdogStorage, times(totalRestarts)).startWrite();
        verify(mSpiedWatchdogStorage, times(isWriteIoStats ? wantedDbWrites : 0))
                .saveIoUsageStats(any());
        verify(mSpiedWatchdogStorage, times(wantedDbWrites)).saveUserPackageSettings(any());
        verify(mSpiedWatchdogStorage, times(wantedDbWrites)).markWriteSuccessful();
        verify(mSpiedWatchdogStorage, times(wantedDbWrites)).endWrite();
        verify(mSpiedWatchdogStorage, times(totalRestarts)).release();
        mCarWatchdogService = new CarWatchdogService(mMockContext, mMockBuiltinPackageContext,
                mSpiedWatchdogStorage, mTimeSource);
        initService(/* wantedInvocations= */ totalRestarts + 1);
    }

    private void captureCarPowerListeners(int wantedInvocations) {
        verify(mMockCarPowerManagementService, times(wantedInvocations)).registerListener(
                mICarPowerStateListenerCaptor.capture());
        mCarPowerStateListener = mICarPowerStateListenerCaptor.getValue();
        assertWithMessage("Car power state listener").that(mCarPowerStateListener).isNotNull();

        verify(mMockCarPowerManagementService, times(wantedInvocations)).addPowerPolicyListener(
                any(), mICarPowerPolicyListenerCaptor.capture());
        mCarPowerPolicyListener = mICarPowerPolicyListenerCaptor.getValue();
        assertWithMessage("Car power policy listener").that(mCarPowerPolicyListener).isNotNull();
    }

    private void captureBroadcastReceiver(int wantedInvocations) {
        verify(mMockContext, times(wantedInvocations * 2))
                .registerReceiverForAllUsers(mBroadcastReceiverCaptor.capture(),
                        mIntentFilterCaptor.capture(), any(), any(), anyInt());

        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        assertWithMessage("Broadcast receiver").that(mBroadcastReceiver).isNotNull();

        List<IntentFilter> filters = mIntentFilterCaptor.getAllValues();
        int totalFilters = filters.size();
        assertThat(totalFilters).isAtLeast(2);
        // When CarWatchdogService is restarted, registerReceiverForAllUsers will be called more
        // than 2 times. Thus, verify the filters only from the latest 2 calls.
        IntentFilter filter = filters.get(totalFilters - 2);
        assertFilterHasActions(filter, CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION,
                ACTION_GARAGE_MODE_ON, ACTION_GARAGE_MODE_OFF,
                CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS,
                CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP, ACTION_USER_REMOVED);
        filter = filters.get(totalFilters - 1);
        assertFilterHasActions(filter, ACTION_PACKAGE_CHANGED);
        assertFilterHasDataScheme(filter, /* dataScheme= */ "package");
    }

    private void captureUserLifecycleListener(int wantedInvocations) {
        verify(mMockCarUserService, times(wantedInvocations)).addUserLifecycleListener(any(),
                mUserLifecycleListenerCaptor.capture());
        mUserLifecycleListener = mUserLifecycleListenerCaptor.getValue();
        assertWithMessage("User lifecycle listener").that(mUserLifecycleListener).isNotNull();
    }

    private void captureCarUxRestrictionsChangeListener(int wantedInvocations) {
        verify(mMockCarUxRestrictionsManagerService, times(wantedInvocations))
                .getCurrentUxRestrictions();
        verify(mMockCarUxRestrictionsManagerService, times(wantedInvocations))
                .registerUxRestrictionsChangeListener(mICarUxRestrictionsChangeListener.capture(),
                        eq(Display.DEFAULT_DISPLAY));
        mCarUxRestrictionsChangeListener = mICarUxRestrictionsChangeListener.getValue();
        assertWithMessage("UX restrictions change listener").that(mCarUxRestrictionsChangeListener)
                .isNotNull();
    }

    private void captureAndVerifyRegistrationWithDaemon(boolean waitOnMain) throws Exception {
        if (waitOnMain) {
            // Registering to daemon is done on the main thread. To ensure the registration
            // completes before verification, execute an empty block on the main thread.
            CarServiceUtils.runOnMainSync(() -> {});
        }

        verify(mMockCarWatchdogDaemon, atLeastOnce()).asBinder();

        verify(mMockBinder, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        mCarWatchdogDaemonBinderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("Watchdog daemon binder death recipient")
                .that(mCarWatchdogDaemonBinderDeathRecipient).isNotNull();

        verify(mMockCarWatchdogDaemon, atLeastOnce()).registerCarWatchdogService(
                mICarWatchdogServiceForSystemCaptor.capture());
        mWatchdogServiceForSystemImpl = mICarWatchdogServiceForSystemCaptor.getValue();
        assertWithMessage("Car watchdog service for system")
                .that(mWatchdogServiceForSystemImpl).isNotNull();

        verify(mMockCarWatchdogDaemon, atLeastOnce()).notifySystemStateChange(
                StateType.GARAGE_MODE, GarageMode.GARAGE_MODE_OFF, MISSING_ARG_VALUE);

        // Once registration with daemon completes, the service post a new message on the main
        // thread to fetch and sync resource overuse configs.
        CarServiceUtils.runOnMainSync(() -> {});

        verify(mMockCarWatchdogDaemon, atLeastOnce()).getResourceOveruseConfigurations();
    }

    private void verifyDatabaseInit(int wantedInvocations) throws Exception {
        /*
         * Database read is posted on a separate handler thread. Wait until the handler thread has
         * processed the database read request before verifying.
         */
        CarServiceUtils.runEmptyRunnableOnLooperSync(CarWatchdogService.class.getSimpleName());
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).syncUsers(any());
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getUserPackageSettings();
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getTodayIoUsageStats();
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getNotForgivenHistoricalIoOveruses(
                RECURRING_OVERUSE_PERIOD_IN_DAYS);
    }

    private void captureStatsPullAtomCallback(int wantedInvocations) {
        verify(mMockStatsManager, times(wantedInvocations)).setPullAtomCallback(
                eq(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY), any(PullAtomMetadata.class),
                any(Executor.class), mStatsPullAtomCallbackCaptor.capture());
        verify(mMockStatsManager, times(wantedInvocations)).setPullAtomCallback(
                eq(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY), any(PullAtomMetadata.class),
                any(Executor.class), mStatsPullAtomCallbackCaptor.capture());

        // The same callback is set in the above calls, so fetch the latest captured callback.
        mStatsPullAtomCallback = mStatsPullAtomCallbackCaptor.getValue();
        assertWithMessage("Stats pull atom callback").that(mStatsPullAtomCallback).isNotNull();
    }

    private void mockBuildStatsEventCalls() {
        when(CarStatsLog.buildStatsEvent(eq(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY),
                any(byte[].class), anyLong())).thenAnswer(args -> {
                    mPulledSystemIoUsageSummaries.add(AtomsProto.CarWatchdogSystemIoUsageSummary
                            .newBuilder()
                            .setIoUsageSummary(AtomsProto.CarWatchdogIoUsageSummary.parseFrom(
                                    (byte[]) args.getArgument(1)))
                            .setStartTimeMillis(args.getArgument(2))
                            .build());
                    // Returned event is not used in tests, so return an empty event.
                    return StatsEvent.newBuilder().build();
                });

        when(CarStatsLog.buildStatsEvent(eq(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY), anyInt(),
                any(byte[].class), anyLong())).thenAnswer(args -> {
                    mPulledUidIoUsageSummaries.add(AtomsProto.CarWatchdogUidIoUsageSummary
                            .newBuilder()
                            .setUid(args.getArgument(1))
                            .setIoUsageSummary(AtomsProto.CarWatchdogIoUsageSummary.parseFrom(
                                    (byte[]) args.getArgument(2)))
                            .setStartTimeMillis(args.getArgument(3))
                            .build());
                    // Returned event is not used in tests, so return an empty event.
                    return StatsEvent.newBuilder().build();
                });
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void mockSettingsStringCalls() {
        doAnswer(args -> {
            ContentResolver contentResolver = mock(ContentResolver.class);
            when(contentResolver.getUserId()).thenReturn(args.getArgument(1));
            return contentResolver;
        }).when(() -> CarServiceUtils.getContentResolverForUser(any(), anyInt()));

        when(Settings.Secure.getString(any(ContentResolver.class),
                eq(KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE))).thenAnswer(
                        args -> {
                            ContentResolver contentResolver = args.getArgument(0);
                            int userId = contentResolver.getUserId();
                            return mDisabledPackagesSettingsStringByUserid.get(userId);
                        });

        // Use any() instead of anyString() to consider when string arg is null.
        when(Settings.Secure.putString(any(ContentResolver.class),
                eq(KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE), any())).thenAnswer(args -> {
                    ContentResolver contentResolver = args.getArgument(0);
                    int userId = contentResolver.getUserId();
                    String packageSettings = args.getArgument(2);
                    if (packageSettings == null) {
                        mDisabledPackagesSettingsStringByUserid.remove(userId);
                    } else {
                        mDisabledPackagesSettingsStringByUserid.put(userId, args.getArgument(2));
                    }
                    return null;
                });
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void mockPackageManager() throws Exception {
        when(mMockPackageManager.getNamesForUids(any())).thenAnswer(args -> {
            int[] uids = args.getArgument(0);
            String[] names = new String[uids.length];
            for (int i = 0; i < uids.length; ++i) {
                names[i] = mGenericPackageNameByUid.get(uids[i], null);
            }
            return names;
        });
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenAnswer(args -> {
            int uid = args.getArgument(0);
            List<String> packages = mPackagesBySharedUid.get(uid);
            return packages.toArray(new String[0]);
        });
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenAnswer(args -> {
                    int userId = ((UserHandle) args.getArgument(2)).getIdentifier();
                    String userPackageId = userId + USER_PACKAGE_SEPARATOR + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo.applicationInfo;
                });
        when(mMockPackageManager.getPackageInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenAnswer(args -> {
                    String userPackageId = args.getArgument(2) + USER_PACKAGE_SEPARATOR
                            + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo;
                });
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
                .thenAnswer(args -> {
                    int userId = args.getArgument(1);
                    List<android.content.pm.PackageInfo> packageInfos = new ArrayList<>();
                    for (android.content.pm.PackageInfo packageInfo :
                            mPmPackageInfoByUserPackage.values()) {
                        if (UserHandle.getUserId(packageInfo.applicationInfo.uid) == userId) {
                            packageInfos.add(packageInfo);
                        }
                    }
                    return packageInfos;
                });
        when(mMockPackageManager.getPackageUidAsUser(anyString(), anyInt()))
                .thenAnswer(args -> {
                    String userPackageId = args.getArgument(1) + USER_PACKAGE_SEPARATOR
                            + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo.applicationInfo.uid;
                });
        doAnswer((args) -> {
            String value = args.getArgument(3) + USER_PACKAGE_SEPARATOR
                    + args.getArgument(0);
            mDisabledUserPackages.add(value);
            return null;
        }).when(mSpiedPackageManager).setApplicationEnabledSetting(
                anyString(), eq(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED), anyInt(),
                anyInt(), anyString());
        doAnswer((args) -> {
            String value = args.getArgument(3) + USER_PACKAGE_SEPARATOR
                    + args.getArgument(0);
            mDisabledUserPackages.remove(value);
            return null;
        }).when(mSpiedPackageManager).setApplicationEnabledSetting(
                anyString(), eq(COMPONENT_ENABLED_STATE_ENABLED), anyInt(),
                anyInt(), anyString());
        doReturn(COMPONENT_ENABLED_STATE_ENABLED).when(mSpiedPackageManager)
                .getApplicationEnabledSetting(anyString(), anyInt());
    }

    private void setCarPowerState(int powerState) throws Exception {
        when(mMockCarPowerManagementService.getPowerState()).thenReturn(powerState);
        mCarPowerStateListener.onStateChanged(powerState, /* timeoutMs= */ -1);
    }

    private void setDisplayStateEnabled(boolean isEnabled) throws Exception {
        int[] enabledComponents = new int[]{};
        int[] disabledComponents = new int[]{};
        if (isEnabled) {
            enabledComponents = new int[]{PowerComponent.DISPLAY};
        } else {
            disabledComponents = new int[]{PowerComponent.DISPLAY};
        }
        mCarPowerPolicyListener.onPolicyChanged(
                new CarPowerPolicy(/* policyId= */ "", enabledComponents, disabledComponents),
                /* accumulatedPolicy= */ null);
    }

    private void setRequiresDistractionOptimization(boolean isRequires) throws Exception {
        CarUxRestrictions.Builder builder = new CarUxRestrictions.Builder(
                isRequires, UX_RESTRICTIONS_BASELINE, /* time= */ 0);
        mCarUxRestrictionsChangeListener.onUxRestrictionsChanged(builder.build());
    }

    private void crashWatchdogDaemon() {
        doReturn(null).when(() -> ServiceManager.checkService(CAR_WATCHDOG_DAEMON_INTERFACE));
        mCarWatchdogDaemonBinderDeathRecipient.binderDied();
        mIsDaemonCrashed = true;
    }

    private void restartWatchdogDaemonAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(args -> {
            latch.countDown();
            return null;
        }).when(mMockBinder).linkToDeath(any(), anyInt());
        mockWatchdogDaemon();
        latch.await(MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        captureAndVerifyRegistrationWithDaemon(/* waitOnMain= */ false);
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            captureOnSetResourceOveruseConfigurations() throws Exception {
        verify(mMockCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS))
                .updateResourceOveruseConfigurations(
                        mResourceOveruseConfigurationsCaptor.capture());
        return mResourceOveruseConfigurationsCaptor.getValue();
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private SparseArray<PackageIoOveruseStats> injectIoOveruseStatsForPackages(
            SparseArray<String> genericPackageNameByUid, Set<String> killablePackages,
            Set<String> shouldNotifyPackages) throws Exception {
        SparseArray<PackageIoOveruseStats> packageIoOveruseStatsByUid = new SparseArray<>();
        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>();
        for (int i = 0; i < genericPackageNameByUid.size(); ++i) {
            String name = genericPackageNameByUid.valueAt(i);
            int uid = genericPackageNameByUid.keyAt(i);
            PackageIoOveruseStats stats = constructPackageIoOveruseStats(uid,
                    shouldNotifyPackages.contains(name),
                    constructPerStateBytes(80, 147, 213),
                    constructInternalIoOveruseStats(killablePackages.contains(name),
                            /* remainingWriteBytes= */ constructPerStateBytes(20, 20, 20),
                            /* writtenBytes= */ constructPerStateBytes(100, 200, 300),
                            /* totalOveruses= */ 3));
            packageIoOveruseStatsByUid.put(uid, stats);
            packageIoOveruseStats.add(stats);
        }
        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);
        return packageIoOveruseStatsByUid;
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void injectPackageInfos(List<android.content.pm.PackageInfo> packageInfos) {
        for (android.content.pm.PackageInfo packageInfo : packageInfos) {
            String genericPackageName = packageInfo.packageName;
            int uid = packageInfo.applicationInfo.uid;
            int userId = UserHandle.getUserId(uid);
            if (packageInfo.sharedUserId != null) {
                genericPackageName =
                        PackageInfoHandler.SHARED_PACKAGE_PREFIX + packageInfo.sharedUserId;
                List<String> packages = mPackagesBySharedUid.get(uid);
                if (packages == null) {
                    packages = new ArrayList<>();
                }
                packages.add(packageInfo.packageName);
                mPackagesBySharedUid.put(uid, packages);
            }
            String userPackageId = userId + USER_PACKAGE_SEPARATOR + packageInfo.packageName;
            assertWithMessage("Duplicate package infos provided for user package id: %s",
                    userPackageId).that(mPmPackageInfoByUserPackage.containsKey(userPackageId))
                    .isFalse();
            assertWithMessage("Mismatch generic package names for the same uid '%s'",
                    uid).that(mGenericPackageNameByUid.get(uid, genericPackageName))
                    .isEqualTo(genericPackageName);
            mPmPackageInfoByUserPackage.put(userPackageId, packageInfo);
            mGenericPackageNameByUid.put(uid, genericPackageName);
        }
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void pushLatestIoOveruseStatsAndWait(List<PackageIoOveruseStats> packageIoOveruseStats)
            throws Exception {
        ResourceStats resourceStats = new ResourceStats();
        resourceStats.resourceOveruseStats =
                new android.automotive.watchdog.internal.ResourceOveruseStats();
        resourceStats.resourceOveruseStats.packageIoOveruseStats = packageIoOveruseStats;

        mWatchdogServiceForSystemImpl.onLatestResourceStats(List.of(resourceStats));

        // Handling latest I/O overuse stats is done on the CarWatchdogService service handler
        // thread. Wait until the below message is processed before returning, so the
        // latestIoOveruseStats call is completed.
        CarServiceUtils.runEmptyRunnableOnLooperSync(CarWatchdogService.class.getSimpleName());

        // Resource overuse handling is done on the main thread by posting a new message with
        // OVERUSE_HANDLING_DELAY_MILLS delay. Wait until the below message is processed before
        // returning, so the resource overuse handling is completed.
        CarServiceUtils.runOnMainSyncDelayed(() -> {}, OVERUSE_HANDLING_DELAY_MILLS * 2);

        if (mGenericPackageNameByUid.size() > 0) {
            verify(mSpiedWatchdogStorage, atLeastOnce()).markDirty();
        }
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void disableUserPackage(String packageName, int... userIds) throws Exception {
        for (int i = 0; i < userIds.length; i++) {
            int userId = userIds[i];

            mCarWatchdogService.performResourceOveruseKill(packageName, userId);

            verify(mSpiedPackageManager, atLeastOnce())
                    .getApplicationEnabledSetting(packageName, userId);

            verify(mSpiedPackageManager).setApplicationEnabledSetting(eq(packageName),
                    eq(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED), eq(0),
                    eq(userId), anyString());

            assertThat(mDisabledUserPackages).contains(userId + ":" + packageName);

            doReturn(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED).when(mSpiedPackageManager)
                    .getApplicationEnabledSetting(eq(packageName), eq(userId));
        }
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    // Remove all related verify* methods as well.
    private void verifyDisabledPackages(String userPackagesCsv) {
        verifyDisabledPackages(/* message= */ "", userPackagesCsv);
    }

    private void verifyDisabledPackages(String message, String userPackagesCsv) {
        assertWithMessage("Disabled user packages %s", message).that(mDisabledUserPackages)
                .containsExactlyElementsIn(userPackagesCsv.split(","));

        verifyDisabledPackagesSettingsKey(message, userPackagesCsv);
    }

    private void verifyDisabledPackagesSettingsKey(String message, String userPackagesCsv) {
        List<String> userPackagesFromSettingsString = new ArrayList<>();
        for (int i = 0; i < mDisabledPackagesSettingsStringByUserid.size(); ++i) {
            int userId = mDisabledPackagesSettingsStringByUserid.keyAt(i);
            String value = mDisabledPackagesSettingsStringByUserid.valueAt(i);
            List<String> packages = TextUtils.isEmpty(value) ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(value.split(
                            PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR)));
            packages.forEach(element ->
                    userPackagesFromSettingsString.add(userId + USER_PACKAGE_SEPARATOR + element));
        }

        assertWithMessage(
                "KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE settings string user packages %s",
                message).that(userPackagesFromSettingsString)
                .containsExactlyElementsIn(userPackagesCsv.split(","));
    }

    private void captureAndVerifyCancelNotificationAsUser(UserHandle expectedUserHandle,
            int expectedNotificationId) {
        verify(mMockNotificationHelper).cancelNotificationAsUser(expectedUserHandle,
                expectedNotificationId);
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static List<ResourceOveruseConfiguration> sampleResourceOveruseConfigurations() {
        return Arrays.asList(
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.VENDOR,
                        sampleIoOveruseConfigurationBuilder(ComponentType.VENDOR).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.THIRD_PARTY,
                        sampleIoOveruseConfigurationBuilder(ComponentType.THIRD_PARTY).build())
                        .build());
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            sampleInternalResourceOveruseConfigurations() {
        return Arrays.asList(
                sampleInternalResourceOveruseConfiguration(ComponentType.SYSTEM,
                        sampleInternalIoOveruseConfiguration(ComponentType.SYSTEM)),
                sampleInternalResourceOveruseConfiguration(ComponentType.VENDOR,
                        sampleInternalIoOveruseConfiguration(ComponentType.VENDOR)),
                sampleInternalResourceOveruseConfiguration(ComponentType.THIRD_PARTY,
                        sampleInternalIoOveruseConfiguration(ComponentType.THIRD_PARTY)));
    }

    //TODO(b/209701184): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static ResourceOveruseConfiguration.Builder sampleResourceOveruseConfigurationBuilder(
            @ComponentType int componentType, IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        List<String> safeToKill = Arrays.asList(prefix + "_package.non_critical.A",
                prefix + "_pkg.non_critical.B",
                "shared:" + prefix + "_shared_package.non_critical.B",
                "some_pkg_as_" + prefix + "_pkg");
        List<String> vendorPrefixes = Arrays.asList(
                prefix + "_package", "some_pkg_as_" + prefix + "_pkg");
        Map<String, String> pkgToAppCategory = new ArrayMap<>();
        pkgToAppCategory.put("system_package.MEDIA", "android.car.watchdog.app.category.MEDIA");
        pkgToAppCategory.put("system_package.A", "android.car.watchdog.app.category.MAPS");
        pkgToAppCategory.put("vendor_package.MEDIA", "android.car.watchdog.app.category.MEDIA");
        pkgToAppCategory.put("vendor_package.A", "android.car.watchdog.app.category.MAPS");
        pkgToAppCategory.put("third_party_package.MAPS", "android.car.watchdog.app.category.MAPS");
        ResourceOveruseConfiguration.Builder configBuilder =
                new ResourceOveruseConfiguration.Builder(componentType, safeToKill,
                        vendorPrefixes, pkgToAppCategory);
        configBuilder.setIoOveruseConfiguration(ioOveruseConfig);
        return configBuilder;
    }

    //TODO(b/209701184): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static IoOveruseConfiguration.Builder sampleIoOveruseConfigurationBuilder(
            @ComponentType int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        PerStateBytes componentLevelThresholds = new PerStateBytes(
                /* foregroundModeBytes= */ componentType * 10L,
                /* backgroundModeBytes= */ componentType * 20L,
                /* garageModeBytes= */ componentType * 30L);
        Map<String, PerStateBytes> packageSpecificThresholds = new ArrayMap<>();
        packageSpecificThresholds.put(prefix + "_package.A", new PerStateBytes(
                /* foregroundModeBytes= */ componentType * 40L,
                /* backgroundModeBytes= */ componentType * 50L,
                /* garageModeBytes= */ componentType * 60L));

        Map<String, PerStateBytes> appCategorySpecificThresholds = new ArrayMap<>();
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA,
                new PerStateBytes(/* foregroundModeBytes= */ componentType * 100L,
                        /* backgroundModeBytes= */ componentType * 200L,
                        /* garageModeBytes= */ componentType * 300L));
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS,
                new PerStateBytes(/* foregroundModeBytes= */ componentType * 1100L,
                        /* backgroundModeBytes= */ componentType * 2200L,
                        /* garageModeBytes= */ componentType * 3300L));

        List<IoOveruseAlertThreshold> systemWideThresholds = Collections.singletonList(
                new IoOveruseAlertThreshold(/* durationInSeconds= */ componentType * 10L,
                        /* writtenBytesPerSecond= */ componentType * 200L));

        return new IoOveruseConfiguration.Builder(componentLevelThresholds,
                packageSpecificThresholds, appCategorySpecificThresholds, systemWideThresholds);
    }

    private static android.automotive.watchdog.internal.ResourceOveruseConfiguration
            sampleInternalResourceOveruseConfiguration(@ComponentType int componentType,
            android.automotive.watchdog.internal.IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        android.automotive.watchdog.internal.ResourceOveruseConfiguration config =
                new android.automotive.watchdog.internal.ResourceOveruseConfiguration();
        config.componentType = componentType;
        config.safeToKillPackages = Arrays.asList(prefix + "_package.non_critical.A",
                prefix + "_pkg.non_critical.B",
                "shared:" + prefix + "_shared_package.non_critical.B",
                "some_pkg_as_" + prefix + "_pkg");
        config.vendorPackagePrefixes = Arrays.asList(
                prefix + "_package", "some_pkg_as_" + prefix + "_pkg");
        config.packageMetadata = Arrays.asList(
                constructPackageMetadata("system_package.MEDIA", ApplicationCategoryType.MEDIA),
                constructPackageMetadata("system_package.A", ApplicationCategoryType.MAPS),
                constructPackageMetadata("vendor_package.MEDIA", ApplicationCategoryType.MEDIA),
                constructPackageMetadata("vendor_package.A", ApplicationCategoryType.MAPS),
                constructPackageMetadata("third_party_package.MAPS", ApplicationCategoryType.MAPS));

        ResourceSpecificConfiguration resourceSpecificConfig = new ResourceSpecificConfiguration();
        resourceSpecificConfig.setIoOveruseConfiguration(ioOveruseConfig);
        config.resourceSpecificConfigurations = Collections.singletonList(resourceSpecificConfig);

        return config;
    }

    private static android.automotive.watchdog.internal.IoOveruseConfiguration
            sampleInternalIoOveruseConfiguration(@ComponentType int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        android.automotive.watchdog.internal.IoOveruseConfiguration config =
                new android.automotive.watchdog.internal.IoOveruseConfiguration();
        config.componentLevelThresholds = constructPerStateIoOveruseThreshold(
                WatchdogPerfHandler.toComponentTypeStr(componentType),
                /* fgBytes= */ componentType * 10L, /* bgBytes= */ componentType *  20L,
                /*gmBytes= */ componentType * 30L);
        config.packageSpecificThresholds = Collections.singletonList(
                constructPerStateIoOveruseThreshold(prefix + "_package.A",
                        /* fgBytes= */ componentType * 40L, /* bgBytes= */ componentType * 50L,
                        /* gmBytes= */ componentType * 60L));
        config.categorySpecificThresholds = Arrays.asList(
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA,
                        /* fgBytes= */ componentType * 100L, /* bgBytes= */ componentType * 200L,
                        /* gmBytes= */ componentType * 300L),
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS,
                        /* fgBytes= */ componentType * 1100L, /* bgBytes= */ componentType * 2200L,
                        /* gmBytes= */ componentType * 3300L));
        config.systemWideThresholds = Collections.singletonList(
                constructInternalIoOveruseAlertThreshold(
                        /* duration= */ componentType * 10L, /* writeBPS= */ componentType * 200L));
        return config;
    }

    private static PackageMetadata constructPackageMetadata(
            String packageName, @ApplicationCategoryType int appCategoryType) {
        PackageMetadata metadata = new PackageMetadata();
        metadata.packageName = packageName;
        metadata.appCategoryType = appCategoryType;
        return metadata;
    }

    //TODO(b/209701184): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static PerStateIoOveruseThreshold constructPerStateIoOveruseThreshold(String name,
            long fgBytes, long bgBytes, long gmBytes) {
        PerStateIoOveruseThreshold threshold = new PerStateIoOveruseThreshold();
        threshold.name = name;
        threshold.perStateWriteBytes = new android.automotive.watchdog.PerStateBytes();
        threshold.perStateWriteBytes.foregroundBytes = fgBytes;
        threshold.perStateWriteBytes.backgroundBytes = bgBytes;
        threshold.perStateWriteBytes.garageModeBytes = gmBytes;
        return threshold;
    }

    private static android.automotive.watchdog.internal.IoOveruseAlertThreshold
            constructInternalIoOveruseAlertThreshold(long duration, long writeBPS) {
        android.automotive.watchdog.internal.IoOveruseAlertThreshold threshold =
                new android.automotive.watchdog.internal.IoOveruseAlertThreshold();
        threshold.durationInSeconds = duration;
        threshold.writtenBytesPerSecond = writeBPS;
        return threshold;
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static PackageIoOveruseStats constructPackageIoOveruseStats(int uid,
            boolean shouldNotify, android.automotive.watchdog.PerStateBytes forgivenWriteBytes,
            android.automotive.watchdog.IoOveruseStats ioOveruseStats) {
        PackageIoOveruseStats stats = new PackageIoOveruseStats();
        stats.uid = uid;
        stats.shouldNotify = shouldNotify;
        stats.forgivenWriteBytes = forgivenWriteBytes;
        stats.ioOveruseStats = ioOveruseStats;
        return stats;
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static ResourceOveruseStats constructResourceOveruseStats(int uid, String packageName,
            android.automotive.watchdog.IoOveruseStats internalIoOveruseStats) {
        IoOveruseStats ioOveruseStats = WatchdogPerfHandler.toIoOveruseStatsBuilder(
                internalIoOveruseStats, /* totalTimesKilled= */ 0,
                internalIoOveruseStats.killableOnOveruse).build();

        return new ResourceOveruseStats.Builder(packageName, UserHandle.getUserHandleForUid(uid))
                .setIoOveruseStats(ioOveruseStats).build();
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    static UserPackageIoUsageStats constructUserPackageIoUsageStats(
            int userId, String packageName, android.automotive.watchdog.PerStateBytes writtenBytes,
            android.automotive.watchdog.PerStateBytes forgivenWriteBytes, int totalOveruses) {
        UserPackageIoUsageStats stats = new UserPackageIoUsageStats();
        stats.userId = userId;
        stats.packageName = packageName;
        stats.ioUsageStats = new IoUsageStats();
        stats.ioUsageStats.writtenBytes = writtenBytes;
        stats.ioUsageStats.forgivenWriteBytes = forgivenWriteBytes;
        stats.ioUsageStats.totalOveruses = totalOveruses;
        return stats;
    }

    public static boolean isUserPackageIoUsageStatsEquals(UserPackageIoUsageStats actual,
            UserPackageIoUsageStats expected) {
        return actual.userId == expected.userId && actual.packageName.equals(expected.packageName)
                && isInternalPerStateBytesEquals(
                        actual.ioUsageStats.writtenBytes, expected.ioUsageStats.writtenBytes)
                && isInternalPerStateBytesEquals(actual.ioUsageStats.forgivenWriteBytes,
                        expected.ioUsageStats.forgivenWriteBytes)
                && actual.ioUsageStats.totalOveruses == expected.ioUsageStats.totalOveruses;
    }

    public static boolean isInternalPerStateBytesEquals(
            android.automotive.watchdog.PerStateBytes actual,
            android.automotive.watchdog.PerStateBytes expected) {
        return actual.foregroundBytes == expected.foregroundBytes
                && actual.backgroundBytes == expected.backgroundBytes
                && actual.garageModeBytes == expected.garageModeBytes;
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            boolean killableOnOveruse,
            android.automotive.watchdog.PerStateBytes remainingWriteBytes,
            android.automotive.watchdog.PerStateBytes writtenBytes, int totalOveruses) {
        return constructInternalIoOveruseStats(killableOnOveruse, STATS_DURATION_SECONDS,
                remainingWriteBytes, writtenBytes, totalOveruses);
    }

    private android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            boolean killableOnOveruse, long durationInSecs,
            android.automotive.watchdog.PerStateBytes remainingWriteBytes,
            android.automotive.watchdog.PerStateBytes writtenBytes, int totalOveruses) {
        android.automotive.watchdog.IoOveruseStats stats =
                new android.automotive.watchdog.IoOveruseStats();
        stats.startTime = mTimeSource.now().getEpochSecond();
        stats.durationInSeconds = durationInSecs;
        stats.killableOnOveruse = killableOnOveruse;
        stats.remainingWriteBytes = remainingWriteBytes;
        stats.writtenBytes = writtenBytes;
        stats.totalOveruses = totalOveruses;
        return stats;
    }

    private static AtomsProto.CarWatchdogIoOveruseStatsReported
            constructIoOveruseStatsReported(int uid, AtomsProto.CarWatchdogPerStateBytes threshold,
            AtomsProto.CarWatchdogPerStateBytes writtenBytes) {
        return constructCarWatchdogIoOveruseStatsReported(
                uid, WatchdogPerfHandler.constructCarWatchdogIoOveruseStats(
                        AtomsProto.CarWatchdogIoOveruseStats.Period.DAILY, threshold, writtenBytes)
        );
    }

    private static AtomsProto.CarWatchdogIoOveruseStatsReported
            constructCarWatchdogIoOveruseStatsReported(
                    int uid, AtomsProto.CarWatchdogIoOveruseStats ioOveruseStats) {
        return AtomsProto.CarWatchdogIoOveruseStatsReported.newBuilder()
                .setUid(uid)
                .setIoOveruseStats(ioOveruseStats)
                .build();
    }

    private static AtomsProto.CarWatchdogKillStatsReported constructIoOveruseKillStatsReported(
            int uid, int systemState, AtomsProto.CarWatchdogPerStateBytes threshold,
            AtomsProto.CarWatchdogPerStateBytes writtenBytes) {
        return constructCarWatchdogKillStatsReported(uid,
                CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE, systemState,
                CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_IO_OVERUSE,
                WatchdogPerfHandler.constructCarWatchdogIoOveruseStats(
                        AtomsProto.CarWatchdogIoOveruseStats.Period.DAILY, threshold, writtenBytes)
        );
    }

    private static AtomsProto.CarWatchdogKillStatsReported constructCarWatchdogKillStatsReported(
            int uid, int uidState, int systemState, int killReason,
            AtomsProto.CarWatchdogIoOveruseStats ioOveruseStats) {
        return AtomsProto.CarWatchdogKillStatsReported.newBuilder()
                .setUid(uid)
                .setUidState(AtomsProto.CarWatchdogKillStatsReported.UidState.forNumber(uidState))
                .setSystemState(AtomsProto.CarWatchdogKillStatsReported.SystemState.forNumber(
                        systemState))
                .setKillReason(AtomsProto.CarWatchdogKillStatsReported.KillReason.forNumber(
                        killReason))
                .setIoOveruseStats(ioOveruseStats)
                .build();
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private void captureAndVerifyIoOveruseStatsReported(
            List<AtomsProto.CarWatchdogIoOveruseStatsReported> expected) throws Exception {
        verify(() -> CarStatsLog.write(eq(CarStatsLog.CAR_WATCHDOG_IO_OVERUSE_STATS_REPORTED),
                mOverusingUidCaptor.capture(), mOveruseStatsCaptor.capture()),
                times(expected.size()));

        List<Integer> allUidValues = mOverusingUidCaptor.getAllValues();
        List<byte[]> allOveruseStatsValues = mOveruseStatsCaptor.getAllValues();
        List<AtomsProto.CarWatchdogIoOveruseStatsReported> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); ++i) {
            actual.add(constructCarWatchdogIoOveruseStatsReported(allUidValues.get(i),
                    AtomsProto.CarWatchdogIoOveruseStats.parseFrom(allOveruseStatsValues.get(i))));
        }
        assertWithMessage("I/O overuse stats reported to statsd").that(actual)
                .containsExactlyElementsIn(expected);
    }

    private void captureAndVerifyKillStatsReported(
            List<AtomsProto.CarWatchdogKillStatsReported> expected) throws Exception {
        // Overuse handling task is posted on the main thread and this task performs disabling and
        // uploading metrics. Wait for this task to complete.
        CarServiceUtils.runOnMainSync(() -> {});

        verify(() -> CarStatsLog.write(eq(CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED),
                mKilledUidCaptor.capture(), mUidStateCaptor.capture(),
                mSystemStateCaptor.capture(), mKillReasonCaptor.capture(), eq(null),
                mKilledStatsCaptor.capture()), times(expected.size()));

        List<Integer> allUidValues = mKilledUidCaptor.getAllValues();
        List<Integer> allUidStateValues = mUidStateCaptor.getAllValues();
        List<Integer> allSystemStateValues = mSystemStateCaptor.getAllValues();
        List<Integer> allKillReasonValues = mKillReasonCaptor.getAllValues();
        List<byte[]> allIoOveruseStatsValues = mKilledStatsCaptor.getAllValues();
        List<AtomsProto.CarWatchdogKillStatsReported> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); ++i) {
            actual.add(constructCarWatchdogKillStatsReported(allUidValues.get(i),
                    allUidStateValues.get(i), allSystemStateValues.get(i),
                    allKillReasonValues.get(i),
                    AtomsProto.CarWatchdogIoOveruseStats.parseFrom(
                            allIoOveruseStatsValues.get(i))));
        }
        assertWithMessage("I/O overuse kill stats reported to statsd").that(actual)
                .containsExactlyElementsIn(expected);
    }

    private List<AtomsProto.CarWatchdogSystemIoUsageSummary> verifyAndGetSystemIoUsageSummaries(
            ZonedDateTime beginReportDate) {
        ZonedDateTime beginWeekStartDate = beginReportDate.with(ChronoField.DAY_OF_WEEK, 1);
        ZonedDateTime endWeekStartDate = mTimeSource.getCurrentDate()
                .with(ChronoField.DAY_OF_WEEK, 1);
        List<AtomsProto.CarWatchdogSystemIoUsageSummary> expectedSummaries = new ArrayList<>();
        while (!beginWeekStartDate.equals(endWeekStartDate)) {
            long startEpochSecond = beginWeekStartDate.toEpochSecond();
            verify(mSpiedWatchdogStorage).getDailySystemIoUsageSummaries(
                    IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES, startEpochSecond,
                    beginWeekStartDate.plusWeeks(1).toEpochSecond());
            expectedSummaries.add(AtomsProto.CarWatchdogSystemIoUsageSummary.newBuilder()
                    .setIoUsageSummary(constructCarWatchdogIoUsageSummary(
                            sampleDailyIoUsageSummariesForAWeek(startEpochSecond,
                                    SYSTEM_DAILY_IO_USAGE_SUMMARY_MULTIPLIER)))
                    .setStartTimeMillis(startEpochSecond * 1000)
                    .build());
            beginWeekStartDate = beginWeekStartDate.plusWeeks(1);
        }
        return expectedSummaries;
    }

    private List<AtomsProto.CarWatchdogUidIoUsageSummary> verifyAndGetUidIoUsageSummaries(
            ZonedDateTime beginReportDate, List<Integer> expectUids) {
        ZonedDateTime beginWeekStartDate = beginReportDate.with(ChronoField.DAY_OF_WEEK, 1);
        ZonedDateTime endWeekStartDate = mTimeSource.getCurrentDate()
                .with(ChronoField.DAY_OF_WEEK, 1);
        List<AtomsProto.CarWatchdogUidIoUsageSummary> expectedSummaries = new ArrayList<>();
        while (!beginWeekStartDate.equals(endWeekStartDate)) {
            long startEpochSecond = beginWeekStartDate.toEpochSecond();
            verify(mSpiedWatchdogStorage).getTopUsersDailyIoUsageSummaries(
                    UID_IO_USAGE_SUMMARY_TOP_COUNT * 2,
                    IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES, startEpochSecond,
                    beginWeekStartDate.plusWeeks(1).toEpochSecond());
            for (Integer uid : expectUids) {
                expectedSummaries.add(AtomsProto.CarWatchdogUidIoUsageSummary.newBuilder()
                        .setUid(uid)
                        .setIoUsageSummary(constructCarWatchdogIoUsageSummary(
                                sampleDailyIoUsageSummariesForAWeek(startEpochSecond, uid)))
                        .setStartTimeMillis(startEpochSecond * 1000)
                        .build());
            }
            beginWeekStartDate = beginWeekStartDate.plusWeeks(1);
        }
        return expectedSummaries;
    }

    private static AtomsProto.CarWatchdogIoUsageSummary constructCarWatchdogIoUsageSummary(
            List<AtomsProto.CarWatchdogDailyIoUsageSummary> dailySummaries) {
        return AtomsProto.CarWatchdogIoUsageSummary.newBuilder()
                .setEventTimePeriod(AtomsProto.CarWatchdogEventTimePeriod.newBuilder()
                        .setPeriod(AtomsProto.CarWatchdogEventTimePeriod.Period.WEEKLY).build())
                .addAllDailyIoUsageSummary(dailySummaries)
                .build();
    }

    private List<AtomsProto.CarWatchdogDailyIoUsageSummary> sampleDailyIoUsageSummariesForAWeek(
            long startEpochSeconds, long sysOrUidMultiplier) {
        List<AtomsProto.CarWatchdogDailyIoUsageSummary> summaries = new ArrayList<>();
        long weekMultiplier = ChronoUnit.WEEKS.between(
                ZonedDateTime.ofInstant(Instant.ofEpochSecond(startEpochSeconds), ZONE_OFFSET),
                mTimeSource.getCurrentDate());
        for (int i = 1; i < 8; ++i) {
            summaries.add(constructCarWatchdogDailyIoUsageSummary(
                    /* fgWrBytes= */ 100 * i * weekMultiplier * sysOrUidMultiplier,
                    /* bgWrBytes= */ 200 * i * weekMultiplier * sysOrUidMultiplier,
                    /* gmWrBytes= */ 300 * i * weekMultiplier * sysOrUidMultiplier,
                    /* overuseCount= */ 2 * i));
        }
        return summaries;
    }

    static AtomsProto.CarWatchdogDailyIoUsageSummary constructCarWatchdogDailyIoUsageSummary(
            long fgWrBytes, long bgWrBytes, long gmWrBytes, int overuseCount) {
        return AtomsProto.CarWatchdogDailyIoUsageSummary.newBuilder()
                .setWrittenBytes(WatchdogPerfHandler
                        .constructCarWatchdogPerStateBytes(fgWrBytes, bgWrBytes, gmWrBytes))
                .setOveruseCount(overuseCount)
                .build();
    }

    //TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static IResourceOveruseListener createMockResourceOveruseListener() {
        IResourceOveruseListener listener = mock(IResourceOveruseListener.Stub.class);
        when(listener.asBinder()).thenCallRealMethod();
        return listener;
    }

    private static PackageInfo constructPackageInfo(String packageName, int uid,
            List<String> sharedUidPackages, int uidType, int componentType, int appCategoryType) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageIdentifier = new PackageIdentifier();
        packageInfo.packageIdentifier.name = packageName;
        packageInfo.packageIdentifier.uid = uid;
        packageInfo.uidType = uidType;
        packageInfo.sharedUidPackages = sharedUidPackages;
        packageInfo.componentType = componentType;
        packageInfo.appCategoryType = appCategoryType;

        return packageInfo;
    }

    private static String toPackageInfosString(List<PackageInfo> packageInfos) {
        StringBuilder builder = new StringBuilder();
        for (PackageInfo packageInfo : packageInfos) {
            builder = packageInfoStringBuilder(builder, packageInfo).append('\n');
        }
        return builder.toString();
    }

    private static StringBuilder packageInfoStringBuilder(
            StringBuilder builder, PackageInfo packageInfo) {
        if (packageInfo == null) {
            return builder.append("Null package info\n");
        }
        builder.append("Package name: '").append(packageInfo.packageIdentifier.name)
                .append("', UID: ").append(packageInfo.packageIdentifier.uid).append('\n')
                .append("Owned packages: ");
        if (packageInfo.sharedUidPackages != null) {
            for (int i = 0; i < packageInfo.sharedUidPackages.size(); ++i) {
                builder.append('\'').append(packageInfo.sharedUidPackages.get(i)).append('\'');
                if (i < packageInfo.sharedUidPackages.size() - 1) {
                    builder.append(", ");
                }
            }
            builder.append('\n');
        } else {
            builder.append("Null");
        }
        builder.append("Component type: ").append(packageInfo.componentType).append('\n')
                .append("Application category type: ").append(packageInfo.appCategoryType).append(
                '\n');

        return builder;
    }

    private static void assertPackageInfoEquals(List<PackageInfo> actual,
            List<PackageInfo> expected) throws Exception {
        assertWithMessage("Package infos for UIDs:\nExpected: %s\nActual: %s",
                CarWatchdogServiceUnitTest.toPackageInfosString(expected),
                CarWatchdogServiceUnitTest.toPackageInfosString(actual))
                .that(actual)
                .comparingElementsUsing(
                        Correspondence.from(CarWatchdogServiceUnitTest::isPackageInfoEquals,
                                "is package info equal to")).containsExactlyElementsIn(expected);
    }

    private static boolean isPackageInfoEquals(PackageInfo lhs, PackageInfo rhs) {
        return isEquals(lhs.packageIdentifier, rhs.packageIdentifier)
                && lhs.sharedUidPackages.containsAll(rhs.sharedUidPackages)
                && lhs.componentType == rhs.componentType
                && lhs.appCategoryType == rhs.appCategoryType;
    }

    private static boolean isEquals(PackageIdentifier lhs, PackageIdentifier rhs) {
        return lhs.name.equals(rhs.name) && lhs.uid == rhs.uid;
    }

    // TODO(b/262301082): Remove when all relevant tests have moved to WatchdogPerfHandlerUnitTest.
    private static android.content.pm.PackageInfo constructPackageManagerPackageInfo(
            String packageName, int uid, String sharedUserId) {
        if (packageName.startsWith("system")) {
            return constructPackageManagerPackageInfo(
                    packageName, uid, sharedUserId, ApplicationInfo.FLAG_SYSTEM, 0);
        }
        if (packageName.startsWith("vendor")) {
            return constructPackageManagerPackageInfo(
                    packageName, uid, sharedUserId, ApplicationInfo.FLAG_SYSTEM,
                    ApplicationInfo.PRIVATE_FLAG_OEM);
        }
        return constructPackageManagerPackageInfo(packageName, uid, sharedUserId, 0, 0);
    }

    private static android.content.pm.PackageInfo constructPackageManagerPackageInfo(
            String packageName, int uid, String sharedUserId, int flags, int privateFlags) {
        android.content.pm.PackageInfo packageInfo = new android.content.pm.PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.sharedUserId = sharedUserId;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        packageInfo.applicationInfo.uid = uid;
        packageInfo.applicationInfo.flags = flags;
        packageInfo.applicationInfo.privateFlags = privateFlags;
        return packageInfo;
    }

    private static SparseArray<String> constructPackagesByNotificationId(int idOffset,
            String... packages) {
        SparseArray<String> packagesById = new SparseArray<>();
        idOffset = idOffset < 0 ? 0 : idOffset % RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
        for (String packageName : packages) {
            packagesById.put(RESOURCE_OVERUSE_NOTIFICATION_BASE_ID + idOffset, packageName);
            idOffset = ++idOffset % RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
        }
        return packagesById;
    }

    private static final class TestClient extends ICarWatchdogServiceCallback.Stub {
        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {}
        @Override
        public void onPrepareProcessTermination() {}
    }

    static final class TestTimeSource extends TimeSource {
        private static final Instant TEST_DATE_TIME = Instant.parse("2021-11-12T13:14:15.16Z");
        private Instant mNow;
        TestTimeSource() {
            mNow = TEST_DATE_TIME;
        }

        @Override
        public Instant now() {
            /* Return the same time, so the tests are deterministic. */
            return mNow;
        }

        @Override
        public String toString() {
            return "Mocked date to " + now();
        }

        void updateNow(int numDaysAgo) {
            mNow = TEST_DATE_TIME.minus(numDaysAgo, ChronoUnit.DAYS);
        }
    }
}

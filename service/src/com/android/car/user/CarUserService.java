/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.user;

import static android.Manifest.permission.CREATE_USERS;
import static android.Manifest.permission.MANAGE_USERS;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;

import static com.android.car.PermissionHelper.checkHasAtLeastOnePermissionGranted;
import static com.android.car.PermissionHelper.checkHasDumpPermissionGranted;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.ICarResultReceiver;
import android.car.ICarUserService;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.builtin.os.UserManagerHelper;
import android.car.builtin.util.Slog;
import android.car.builtin.util.Slogf;
import android.car.builtin.util.TimingsTraceLog;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.settings.CarSettings;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserIdentificationAssociationSetValue;
import android.car.user.CarUserManager.UserIdentificationAssociationType;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserCreationResult;
import android.car.user.UserIdentificationAssociationResponse;
import android.car.user.UserRemovalResult;
import android.car.user.UserStartResult;
import android.car.user.UserStopResult;
import android.car.user.UserSwitchResult;
import android.car.util.concurrent.AndroidFuture;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.CreateUserRequest;
import android.hardware.automotive.vehicle.V2_0.CreateUserStatus;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.RemoveUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationSetAssociation;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationSetRequest;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.R;
import com.android.car.hal.HalCallback;
import com.android.car.hal.UserHalHelper;
import com.android.car.hal.UserHalService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.common.CommonConstants.UserLifecycleEventType;
import com.android.car.internal.common.EventLogTags;
import com.android.car.internal.common.UserHelperLite;
import com.android.car.internal.os.CarSystemProperties;
import com.android.car.internal.util.ArrayUtils;
import com.android.car.internal.util.FunctionalUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.InitialUserSetter.InitialUserInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * User service for cars.
 */
public final class CarUserService extends ICarUserService.Stub implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(CarUserService.class);

    /** {@code int} extra used to represent a user id in a {@link ICarResultReceiver} response. */
    public static final String BUNDLE_USER_ID = "user.id";
    /** {@code int} extra used to represent user flags in a {@link ICarResultReceiver} response. */
    public static final String BUNDLE_USER_FLAGS = "user.flags";
    /**
     * {@code String} extra used to represent a user name in a {@link ICarResultReceiver} response.
     */
    public static final String BUNDLE_USER_NAME = "user.name";
    /**
     * {@code int} extra used to represent the user locales in a {@link ICarResultReceiver}
     * response.
     */
    public static final String BUNDLE_USER_LOCALES = "user.locales";
    /**
     * {@code int} extra used to represent the info action in a {@link ICarResultReceiver} response.
     */
    public static final String BUNDLE_INITIAL_INFO_ACTION = "initial_info.action";

    public static final String VEHICLE_HAL_NOT_SUPPORTED = "Vehicle Hal not supported.";

    public static final String HANDLER_THREAD_NAME = "UserService";

    // Constants below must match value of same constants defined by ActivityManager
    public static final int USER_OP_SUCCESS = 0;
    public static final int USER_OP_UNKNOWN_USER = -1;
    public static final int USER_OP_IS_CURRENT = -2;
    public static final int USER_OP_ERROR_IS_SYSTEM = -3;
    public static final int USER_OP_ERROR_RELATED_USERS_CANNOT_STOP = -4;

    private final Context mContext;
    private final ActivityManagerHelper mAmHelper;
    private final ActivityManager mAm;
    private final UserManager mUserManager;
    private final int mMaxRunningUsers;
    private final InitialUserSetter mInitialUserSetter;
    private final UserPreCreator mUserPreCreator;

    private final Object mLockUser = new Object();
    @GuardedBy("mLockUser")
    private boolean mUser0Unlocked;
    @GuardedBy("mLockUser")
    private final ArrayList<Runnable> mUser0UnlockTasks = new ArrayList<>();
    /**
     * Background users that will be restarted in garage mode. This list can include the
     * current foreground user but the current foreground user should not be restarted.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersToRestart = new ArrayList<>();
    /**
     * Keep the list of background users started here. This is wholly for debugging purpose.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersRestartedHere = new ArrayList<>();

    private final UserHalService mHal;

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            HANDLER_THREAD_NAME);
    private final Handler mHandler;

    /**
     * Internal listeners to be notified on new user activities events.
     *
     * <p>This collection should be accessed and manipulated by {@code mHandlerThread} only.
     */
    private final List<UserLifecycleListener> mUserLifecycleListeners = new ArrayList<>();

    /**
     * App listeners to be notified on new user activities events.
     *
     * <p>This collection should be accessed and manipulated by {@code mHandlerThread} only.
     */
    private final ArrayMap<IBinder, AppLifecycleListener> mAppLifecycleListeners =
            new ArrayMap<>();

    /**
     * User Id for the user switch in process, if any.
     */
    @GuardedBy("mLockUser")
    private int mUserIdForUserSwitchInProcess = UserHandle.USER_NULL;
    /**
     * Request Id for the user switch in process, if any.
     */
    @GuardedBy("mLockUser")
    private int mRequestIdForUserSwitchInProcess;
    private final int mHalTimeoutMs = CarSystemProperties.getUserHalTimeout().orElse(5_000);

    // TODO(b/163566866): Use mSwitchGuestUserBeforeSleep for new create guest request
    private final boolean mSwitchGuestUserBeforeSleep;

    @Nullable
    @GuardedBy("mLockUser")
    private UserHandle mInitialUser;

    private ICarResultReceiver mUserSwitchUiReceiver;

    private final CarUxRestrictionsManagerService mCarUxRestrictionService;

    /**
     * Whether some operations - like user switch - are restricted by driving safety constraints.
     */
    @GuardedBy("mLockUser")
    private boolean mUxRestricted;

    /**
     * If {@code false}, garage mode operations (background users start at garage mode entry and
     * background users stop at garage mode exit) will be skipped. Controlled using car shell
     * command {@code adb shell set-start-bg-users-on-garage-mode [true|false]}
     * Purpose: Garage mode testing and simulation
     */
    @GuardedBy("mLockUser")
    private boolean mStartBackgroundUsersOnGarageMode = true;

    /**
     * Callback to notify {@code CarServiceHelper} about driving safety changes (through
     * {@link ICarServiceHelper#setSafetyMode(boolean).
     *
     * <p>NOTE: in theory, that logic should belong to {@code CarDevicePolicyService}, but it's
     * simpler to do it here (and that service already depends on this one).
     */
    @GuardedBy("mLockUser")
    private ICarServiceHelper mICarServiceHelper;

    private final ICarUxRestrictionsChangeListener mCarUxRestrictionsChangeListener =
            new ICarUxRestrictionsChangeListener.Stub() {
        @Override
        public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
            setUxRestrictions(restrictions);
        }
    };

    /** Map used to avoid calling UserHAL when a user was removed because HAL creation failed. */
    @GuardedBy("mLockUser")
    private final SparseBooleanArray mFailedToCreateUserIds = new SparseBooleanArray(1);

    private final UserHandleHelper mUserHandleHelper;

    public CarUserService(@NonNull Context context, @NonNull UserHalService hal,
            @NonNull UserManager userManager,
            @NonNull ActivityManagerHelper amHelper,
            int maxRunningUsers,
            @NonNull CarUxRestrictionsManagerService uxRestrictionService) {
        this(context, hal, userManager, new UserHandleHelper(context, userManager),
                context.getSystemService(ActivityManager.class), amHelper, maxRunningUsers,
                /* initialUserSetter= */ null, /* userPreCreator= */ null, uxRestrictionService,
                null);
    }

    @VisibleForTesting
    CarUserService(@NonNull Context context, @NonNull UserHalService hal,
            @NonNull UserManager userManager,
            @NonNull UserHandleHelper userHandleHelper,
            @NonNull ActivityManager am,
            @NonNull ActivityManagerHelper amHelper,
            int maxRunningUsers,
            @Nullable InitialUserSetter initialUserSetter,
            @Nullable UserPreCreator userPreCreator,
            @NonNull CarUxRestrictionsManagerService uxRestrictionService,
            @Nullable Handler handler) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "constructed for user " + context.getUserId());
        }
        mContext = context;
        mHal = hal;
        mAm = am;
        mAmHelper = amHelper;
        mMaxRunningUsers = maxRunningUsers;
        mUserManager = userManager;
        mUserHandleHelper = userHandleHelper;
        mHandler = handler == null ? new Handler(mHandlerThread.getLooper()) : handler;
        mInitialUserSetter =
                initialUserSetter == null ? new InitialUserSetter(context, this,
                        (u) -> setInitialUser(u), mUserHandleHelper) : initialUserSetter;
        mUserPreCreator =
                userPreCreator == null ? new UserPreCreator(context, mUserManager) : userPreCreator;
        Resources resources = context.getResources();
        mSwitchGuestUserBeforeSleep = resources.getBoolean(
                R.bool.config_switchGuestUserBeforeGoingSleep);
        mCarUxRestrictionService = uxRestrictionService;
    }

    @Override
    public void init() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "init()");
        }
        mCarUxRestrictionService.registerUxRestrictionsChangeListener(
                mCarUxRestrictionsChangeListener, Display.DEFAULT_DISPLAY);

        setUxRestrictions(mCarUxRestrictionService.getCurrentUxRestrictions());
    }

    @Override
    public void release() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "release()");
        }
        mCarUxRestrictionService
                .unregisterUxRestrictionsChangeListener(mCarUxRestrictionsChangeListener);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(@NonNull IndentingPrintWriter writer) {
        checkHasDumpPermissionGranted(mContext, "dump()");

        writer.println("*CarUserService*");
        handleDumpListeners(writer);
        writer.printf("User switch UI receiver %s\n", mUserSwitchUiReceiver);
        synchronized (mLockUser) {
            writer.println("User0Unlocked: " + mUser0Unlocked);
            writer.println("BackgroundUsersToRestart: " + mBackgroundUsersToRestart);
            writer.println("BackgroundUsersRestarted: " + mBackgroundUsersRestartedHere);
            if (mFailedToCreateUserIds.size() > 0) {
                writer.println("FailedToCreateUserIds: " + mFailedToCreateUserIds);
            }
            writer.printf("Is UX restricted: %b\n", mUxRestricted);
            writer.printf("Start Background Users On Garage Mode=%s\n",
                    mStartBackgroundUsersOnGarageMode);
        }

        writer.println("SwitchGuestUserBeforeSleep: " + mSwitchGuestUserBeforeSleep);

        writer.println("MaxRunningUsers: " + mMaxRunningUsers);
        writer.printf("User HAL timeout: %dms\n",  mHalTimeoutMs);
        writer.printf("Initial user: %s\n", mInitialUser);

        writer.println("Relevant overlayable properties");
        Resources res = mContext.getResources();
        writer.increaseIndent();
        writer.printf("owner_name=%s\n", res.getString(com.android.internal.R.string.owner_name));
        writer.printf("default_guest_name=%s\n", res.getString(R.string.default_guest_name));
        writer.decreaseIndent();
        writer.printf("User switch in process=%d\n", mUserIdForUserSwitchInProcess);
        writer.printf("Request Id for the user switch in process=%d\n ",
                    mRequestIdForUserSwitchInProcess);
        writer.printf("System UI package name=%s\n", getSystemUiPackageName());

        writer.println("Relevant Global settings");
        writer.increaseIndent();
        dumpGlobalProperty(writer, CarSettings.Global.LAST_ACTIVE_USER_ID);
        dumpGlobalProperty(writer, CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID);
        writer.decreaseIndent();

        mInitialUserSetter.dump(writer);
    }

    private void dumpGlobalProperty(IndentingPrintWriter writer, String property) {
        String value = Settings.Global.getString(mContext.getContentResolver(), property);
        writer.printf("%s=%s\n", property, value);
    }

    private void handleDumpListeners(IndentingPrintWriter writer) {
        writer.increaseIndent();
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            handleDumpServiceLifecycleListeners(writer);
            handleDumpAppLifecycleListeners(writer);
            latch.countDown();
        });
        int timeout = 5;
        try {
            if (!latch.await(timeout, TimeUnit.SECONDS)) {
                writer.printf("Handler thread didn't respond in %ds when dumping listeners\n",
                        timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.println("Interrupted waiting for handler thread to dump app and user listeners");
        }
        writer.decreaseIndent();
    }

    private void handleDumpServiceLifecycleListeners(PrintWriter writer) {
        if (mUserLifecycleListeners.isEmpty()) {
            writer.println("No lifecycle listeners for internal services");
            return;
        }
        int size = mUserLifecycleListeners.size();
        writer.printf("%d lifecycle listener%s for services\n", size, size == 1 ? "" : "s");
        String indent = "  ";
        for (int i = 0; i < size; i++) {
            UserLifecycleListener listener = mUserLifecycleListeners.get(i);
            writer.printf("%s%s\n", indent, FunctionalUtils.getLambdaName(listener));
        }
    }

    private void handleDumpAppLifecycleListeners(IndentingPrintWriter writer) {
        int size = mAppLifecycleListeners.size();
        if (size == 0) {
            writer.println("No lifecycle listeners for apps");
            return;
        }
        writer.printf("%d lifecycle listener%s for apps\n", size, size == 1 ? "" : "s");
        writer.increaseIndent();
        for (int i = 0; i < size; i++) {
            mAppLifecycleListeners.valueAt(i).dump(writer);
        }
        writer.decreaseIndent();
    }

    @Override
    public void setLifecycleListenerForApp(String packageName, ICarResultReceiver receiver) {
        int uid = Binder.getCallingUid();
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SET_LIFECYCLE_LISTENER, uid, packageName);
        checkInteractAcrossUsersPermission("setLifecycleListenerForApp-" + uid + "-" + packageName);

        IBinder receiverBinder = receiver.asBinder();
        AppLifecycleListener listener = new AppLifecycleListener(uid, packageName, receiver,
                (l) -> onListenerDeath(l));
        Slogf.d(TAG, "Adding %s (using binder %s)", listener, receiverBinder);
        mHandler.post(() -> mAppLifecycleListeners.put(receiverBinder, listener));
    }

    private void onListenerDeath(AppLifecycleListener listener) {
        Slogf.i(TAG, "Removing listener %s on binder death", listener);
        mHandler.post(() -> mAppLifecycleListeners.remove(listener.receiver.asBinder()));
    }

    @Override
    public void resetLifecycleListenerForApp(ICarResultReceiver receiver) {
        int uid = Binder.getCallingUid();
        checkInteractAcrossUsersPermission("resetLifecycleListenerForApp-" + uid);
        IBinder receiverBinder = receiver.asBinder();
        mHandler.post(() -> {
            AppLifecycleListener listener = mAppLifecycleListeners.get(receiverBinder);
            if (listener == null) {
                Slogf.e(TAG, "resetLifecycleListenerForApp(uid=%d): no listener for receiver", uid);
                return;
            }
            if (listener.uid != uid) {
                Slogf.e(TAG, "resetLifecycleListenerForApp(): uid mismatch (called by %d) for "
                        + "listener %s", uid, listener);
            }
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_RESET_LIFECYCLE_LISTENER, uid,
                    listener.packageName);
            Slogf.d(TAG, "Removing %s (using binder %s)", listener, receiverBinder);
            mAppLifecycleListeners.remove(receiverBinder);

            listener.onDestroy();
        });
    }

    /**
     * Gets the initial foreground user after the device boots or resumes from suspension.
     *
     * <p>When the OEM supports the User HAL, the initial user won't be available until the HAL
     * returns the initial value to {@code CarService} - if HAL takes too long or times out, this
     * method returns {@code null}.
     *
     * <p>If the HAL eventually times out, {@code CarService} will fallback to its default behavior
     * (like switching to the last active user), and this method will return the result of such
     * operation.
     *
     * <p>Notice that if {@code CarService} crashes, subsequent calls to this method will return
     * {@code null}.
     *
     * @hide
     */
    @Nullable
    public UserHandle getInitialUser() {
        checkInteractAcrossUsersPermission("getInitialUser");
        synchronized (mLockUser) {
            return mInitialUser;
        }
    }

    /**
     * Sets the initial foreground user after the device boots or resumes from suspension.
     */
    public void setInitialUser(@Nullable UserHandle user) {
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SET_INITIAL_USER,
                user == null ? UserHandle.USER_NULL : user.getIdentifier());
        synchronized (mLockUser) {
            mInitialUser = user;
        }
        if (user == null) {
            // This mean InitialUserSetter failed and could not fallback, so the initial user was
            // not switched (and most likely is SYSTEM_USER).
            // TODO(b/153104378): should we set it to ActivityManager.getCurrentUser() instead?
            Slog.wtf(TAG, "Initial user set to null");
        }
    }

    private void initResumeReplaceGuest() {
        int currentUserId = ActivityManager.getCurrentUser();
        UserHandle currentUser = mUserHandleHelper.getExistingUserHandle(currentUserId);

        if (currentUser == null) {
            Slog.wtf(TAG, "Current user handle doesn't exits " + currentUserId);
        }

        if (!mInitialUserSetter.canReplaceGuestUser(currentUser)) return; // Not a guest

        InitialUserInfo info =
                new InitialUserSetter.Builder(InitialUserSetter.TYPE_REPLACE_GUEST).build();

        mInitialUserSetter.set(info);
    }

    /**
     * Calls to switch user at the power suspend.
     *
     * <p><b>Note:</b> Should be used only by {@link CarPowerManagementService}
     *
     */
    public void onSuspend() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "onSuspend called.");
        }

        if (mSwitchGuestUserBeforeSleep) {
            initResumeReplaceGuest();
        }

        preCreateUsersInternal();
    }

    /**
     * Calls to switch user at the power resume.
     *
     * <p>
     * <b>Note:</b> Should be used only by {@link CarPowerManagementService}
     *
     */
    public void onResume() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "onResume called.");
        }

        mHandler.post(() -> initBootUser(InitialUserInfoRequestType.RESUME));
    }

    /**
     * Calls to start user at the android startup.
     */
    public void initBootUser() {
        mHandler.post(() -> initBootUser(getInitialUserInfoRequestType()));
    }

    private void initBootUser(int requestType) {
        boolean replaceGuest =
                requestType == InitialUserInfoRequestType.RESUME && !mSwitchGuestUserBeforeSleep;
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_INITIAL_USER_INFO_REQ, requestType,
                mHalTimeoutMs);
        checkManageUsersPermission("startInitialUser");

        if (!isUserHalSupported()) {
            fallbackToDefaultInitialUserBehavior(/* userLocales= */ null, replaceGuest);
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_INITIAL_USER_INFO_REQ_COMPLETE,
                    requestType);
            return;
        }

        UsersInfo usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);
        mHal.getInitialUserInfo(requestType, mHalTimeoutMs, usersInfo, (status, resp) -> {
            if (resp != null) {
                EventLog.writeEvent(EventLogTags.CAR_USER_SVC_INITIAL_USER_INFO_RESP,
                        status, resp.action, resp.userToSwitchOrCreate.userId,
                        resp.userToSwitchOrCreate.flags, resp.userNameToCreate, resp.userLocales);

                String userLocales = resp.userLocales;
                InitialUserInfo info;
                switch (resp.action) {
                    case InitialUserInfoResponseAction.SWITCH:
                        int userId = resp.userToSwitchOrCreate.userId;
                        if (userId <= 0) {
                            Slog.w(TAG, "invalid (or missing) user id sent by HAL: " + userId);
                            fallbackToDefaultInitialUserBehavior(userLocales, replaceGuest);
                            break;
                        }
                        info = new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH)
                                .setUserLocales(userLocales)
                                .setSwitchUserId(userId)
                                .setReplaceGuest(replaceGuest)
                                .build();
                        mInitialUserSetter.set(info);
                        break;

                    case InitialUserInfoResponseAction.CREATE:
                        int halFlags = resp.userToSwitchOrCreate.flags;
                        String userName =  resp.userNameToCreate;
                        info = new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE)
                                .setUserLocales(userLocales)
                                .setNewUserName(userName)
                                .setNewUserFlags(halFlags)
                                .build();
                        mInitialUserSetter.set(info);
                        break;

                    case InitialUserInfoResponseAction.DEFAULT:
                        fallbackToDefaultInitialUserBehavior(userLocales, replaceGuest);
                        break;
                    default:
                        Slog.w(TAG, "invalid response action on " + resp);
                        fallbackToDefaultInitialUserBehavior(/* user locale */ null, replaceGuest);
                        break;

                }
            } else {
                EventLog.writeEvent(EventLogTags.CAR_USER_SVC_INITIAL_USER_INFO_RESP, status);
                fallbackToDefaultInitialUserBehavior(/* user locale */ null, replaceGuest);
            }
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_INITIAL_USER_INFO_REQ_COMPLETE,
                    requestType);
        });
    }

    private void fallbackToDefaultInitialUserBehavior(String userLocales, boolean replaceGuest) {
        InitialUserInfo info = new InitialUserSetter.Builder(
                InitialUserSetter.TYPE_DEFAULT_BEHAVIOR)
                .setUserLocales(userLocales)
                .setReplaceGuest(replaceGuest)
                .build();
        mInitialUserSetter.set(info);
    }

    @VisibleForTesting
    int getInitialUserInfoRequestType() {
        if (!mInitialUserSetter.hasInitialUser()) {
            return InitialUserInfoRequestType.FIRST_BOOT;
        }
        if (mContext.getPackageManager().isDeviceUpgrading()) {
            return InitialUserInfoRequestType.FIRST_BOOT_AFTER_OTA;
        }
        return InitialUserInfoRequestType.COLD_BOOT;
    }

    /**
     * Sets the {@link ICarServiceHelper} so it can receive UX restriction updates.
     */
    public void setCarServiceHelper(ICarServiceHelper helper) {
        boolean restricted;
        synchronized (mLockUser) {
            mICarServiceHelper = helper;
            restricted = mUxRestricted;
        }
        updateSafetyMode(helper, restricted);
    }

    private void updateSafetyMode(@Nullable ICarServiceHelper helper, boolean restricted) {
        if (helper == null) return;

        boolean isSafe = !restricted;
        try {
            helper.setSafetyMode(isSafe);
        } catch (Exception e) {
            Slog.e(TAG, "Exception calling helper.setDpmSafetyMode(" + isSafe + ")", e);
        }
    }

    private void setUxRestrictions(@Nullable CarUxRestrictions restrictions) {
        boolean restricted = restrictions != null
                && (restrictions.getActiveRestrictions() & UX_RESTRICTIONS_NO_SETUP)
                        == UX_RESTRICTIONS_NO_SETUP;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "setUxRestrictions(" + restrictions + "): restricted=" + restricted);
        } else {
            Slog.i(TAG, "Setting UX restricted to " + restricted);
        }

        ICarServiceHelper helper = null;

        synchronized (mLockUser) {
            mUxRestricted = restricted;
            if (mICarServiceHelper == null) {
                Slog.e(TAG, "onUxRestrictionsChanged(): no mICarServiceHelper");
            }
            helper = mICarServiceHelper;
        }
        updateSafetyMode(helper, restricted);
    }

    private boolean isUxRestricted() {
        synchronized (mLockUser) {
            return mUxRestricted;
        }
    }

    /**
     * Calls the {@link UserHalService} and {@link ActivityManager} for user switch.
     *
     * <p>
     * When everything works well, the workflow is:
     * <ol>
     *   <li> {@link UserHalService} is called for HAL user switch with ANDROID_SWITCH request
     *   type, current user id, target user id, and a callback.
     *   <li> HAL called back with SUCCESS.
     *   <li> {@link ActivityManager} is called for Android user switch.
     *   <li> Receiver would receive {@code STATUS_SUCCESSFUL}.
     *   <li> Once user is unlocked, {@link UserHalService} is again called with ANDROID_POST_SWITCH
     *   request type, current user id, and target user id. In this case, the current and target
     *   user IDs would be same.
     * <ol/>
     *
     * <p>
     * Corner cases:
     * <ul>
     *   <li> If target user is already the current user, no user switch is performed and receiver
     *   would receive {@code STATUS_OK_USER_ALREADY_IN_FOREGROUND} right away.
     *   <li> If HAL user switch call fails, no Android user switch. Receiver would receive
     *   {@code STATUS_HAL_INTERNAL_FAILURE}.
     *   <li> If HAL user switch call is successful, but android user switch call fails,
     *   {@link UserHalService} is again called with request type POST_SWITCH, current user id, and
     *   target user id, but in this case the current and target user IDs would be different.
     *   <li> If another user switch request for the same target user is received while previous
     *   request is in process, receiver would receive
     *   {@code STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO} for the new request right away.
     *   <li> If a user switch request is received while another user switch request for different
     *   target user is in process, the previous request would be abandoned and new request will be
     *   processed. No POST_SWITCH would be sent for the previous request.
     * <ul/>
     *
     * @param targetUserId - target user Id
     * @param timeoutMs - timeout for HAL to wait
     * @param receiver - receiver for the results
     */
    @Override
    public void switchUser(@UserIdInt int targetUserId, int timeoutMs,
            @NonNull AndroidFuture<UserSwitchResult> receiver) {
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SWITCH_USER_REQ, targetUserId, timeoutMs);
        checkManageOrCreateUsersPermission("switchUser");
        Objects.requireNonNull(receiver);
        UserHandle targetUser = mUserHandleHelper.getExistingUserHandle(targetUserId);
        Preconditions.checkArgument(targetUser != null, "Target user doesn't exist");
        if (mUserManager.getUserSwitchability() != UserManager.SWITCHABILITY_STATUS_OK) {
            sendUserSwitchResult(receiver, UserSwitchResult.STATUS_NOT_SWITCHABLE);
            return;
        }
        mHandler.post(() -> handleSwitchUser(targetUser, timeoutMs, receiver));
    }

    private void handleSwitchUser(@NonNull UserHandle targetUser, int timeoutMs,
            @NonNull AndroidFuture<UserSwitchResult> receiver) {
        int currentUser = ActivityManager.getCurrentUser();
        int targetUserId = targetUser.getIdentifier();
        if (currentUser == targetUserId) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "Current user is same as requested target user: " + targetUserId);
            }
            int resultStatus = UserSwitchResult.STATUS_OK_USER_ALREADY_IN_FOREGROUND;
            sendUserSwitchResult(receiver, resultStatus);
            return;
        }

        if (isUxRestricted()) {
            sendUserSwitchResult(receiver, UserSwitchResult.STATUS_UX_RESTRICTION_FAILURE);
            return;
        }

        // If User Hal is not supported, just android user switch.
        if (!isUserHalSupported()) {
            if (mAm.switchUser(UserHandle.of(targetUserId))) {
                sendUserSwitchResult(receiver, UserSwitchResult.STATUS_SUCCESSFUL);
                return;
            }
            sendUserSwitchResult(receiver, UserSwitchResult.STATUS_ANDROID_FAILURE);
            return;
        }

        synchronized (mLockUser) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "switchUser(" + targetUserId + "): currentuser=" + currentUser
                        + ", mUserIdForUserSwitchInProcess=" + mUserIdForUserSwitchInProcess);
            }

            // If there is another request for the same target user, return another request in
            // process, else {@link mUserIdForUserSwitchInProcess} is updated and {@link
            // mRequestIdForUserSwitchInProcess} is reset. It is possible that there may be another
            // user switch request in process for different target user, but that request is now
            // ignored.
            if (mUserIdForUserSwitchInProcess == targetUserId) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG,
                            "Another user switch request in process for the requested target user: "
                                    + targetUserId);
                }

                int resultStatus = UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO;
                sendUserSwitchResult(receiver, resultStatus);
                return;
            } else {
                mUserIdForUserSwitchInProcess = targetUserId;
                mRequestIdForUserSwitchInProcess = 0;
            }
        }

        UsersInfo usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);
        SwitchUserRequest request = createUserSwitchRequest(targetUserId, usersInfo);

        mHal.switchUser(request, timeoutMs, (halCallbackStatus, resp) -> {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "switch response: status="
                        + UserHalHelper.halCallbackStatusToString(halCallbackStatus)
                        + ", resp=" + resp);
            }

            int resultStatus = UserSwitchResult.STATUS_HAL_INTERNAL_FAILURE;

            synchronized (mLockUser) {
                if (halCallbackStatus != HalCallback.STATUS_OK) {
                    Slog.w(TAG, "invalid callback status ("
                            + UserHalHelper.halCallbackStatusToString(halCallbackStatus)
                            + ") for response " + resp);
                    sendUserSwitchResult(receiver, resultStatus);
                    mUserIdForUserSwitchInProcess = UserHandle.USER_NULL;
                    return;
                }

                if (mUserIdForUserSwitchInProcess != targetUserId) {
                    // Another user switch request received while HAL responded. No need to process
                    // this request further
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Slog.d(TAG, "Another user switch received while HAL responsed. Request"
                                + " abondoned for : " + targetUserId + ". Current user in process: "
                                + mUserIdForUserSwitchInProcess);
                    }
                    resultStatus =
                            UserSwitchResult.STATUS_TARGET_USER_ABANDONED_DUE_TO_A_NEW_REQUEST;
                    sendUserSwitchResult(receiver, resultStatus);
                    mUserIdForUserSwitchInProcess = UserHandle.USER_NULL;
                    return;
                }

                switch (resp.status) {
                    case SwitchUserStatus.SUCCESS:
                        boolean switched;
                        switched = mAm.switchUser(UserHandle.of(targetUserId));
                        if (switched) {
                            sendUserSwitchUiCallback(targetUserId);
                            resultStatus = UserSwitchResult.STATUS_SUCCESSFUL;
                            mRequestIdForUserSwitchInProcess = resp.requestId;
                        } else {
                            resultStatus = UserSwitchResult.STATUS_ANDROID_FAILURE;
                            postSwitchHalResponse(resp.requestId, targetUserId);
                        }
                        break;
                    case SwitchUserStatus.FAILURE:
                        // HAL failed to switch user
                        resultStatus = UserSwitchResult.STATUS_HAL_FAILURE;
                        break;
                    default:
                        // Shouldn't happen because UserHalService validates the status
                        Slog.wtf(TAG, "Received invalid user switch status from HAL: " + resp);
                }

                if (mRequestIdForUserSwitchInProcess == 0) {
                    mUserIdForUserSwitchInProcess = UserHandle.USER_NULL;
                }
            }
            sendUserSwitchResult(receiver, halCallbackStatus, resultStatus, resp.errorMessage);
        });
    }

    @Override
    public void removeUser(@UserIdInt int userId, AndroidFuture<UserRemovalResult> receiver) {
        removeUser(userId, /* hasCallerRestrictions= */ false, receiver);
    }

    /**
     * Internal implementation of {@code removeUser()}, which is used by both
     * {@code ICarUserService} and {@code ICarDevicePolicyService}.
     *
     * @param userId user to be removed
     * @param hasCallerRestrictions when {@code true}, if the caller user is not an admin, it can
     * only remove itself.
     * @param receiver to post results
     */
    public void removeUser(@UserIdInt int userId, boolean hasCallerRestrictions,
            AndroidFuture<UserRemovalResult> receiver) {
        checkManageOrCreateUsersPermission("removeUser");
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_REMOVE_USER_REQ, userId,
                hasCallerRestrictions ? 1 : 0);

        if (hasCallerRestrictions) {
            // Restrictions: non-admin user can only remove itself, admins have no restrictions
            int callingUserId = Binder.getCallingUserHandle().getIdentifier();
            if (!mUserHandleHelper.isAdminUser(UserHandle.of(callingUserId))
                    && userId != callingUserId) {
                throw new SecurityException("Non-admin user " + callingUserId
                        + " can only remove itself");
            }
        }
        mHandler.post(() -> handleRemoveUser(userId, hasCallerRestrictions, receiver));
    }

    private void handleRemoveUser(@UserIdInt int userId, boolean hasCallerRestrictions,
            AndroidFuture<UserRemovalResult> receiver) {
        UserHandle user = mUserHandleHelper.getExistingUserHandle(userId);
        if (user == null) {
            sendUserRemovalResult(userId, UserRemovalResult.STATUS_USER_DOES_NOT_EXIST, receiver);
            return;
        }
        android.hardware.automotive.vehicle.V2_0.UserInfo halUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halUser.userId = user.getIdentifier();
        halUser.flags = UserHalHelper.convertFlags(mUserHandleHelper, user);
        UsersInfo usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);

        // check if the user is last admin user.
        boolean isLastAdmin = false;
        if (UserHalHelper.isAdmin(halUser.flags)) {
            int size = usersInfo.existingUsers.size();
            int totalAdminUsers = 0;
            for (int i = 0; i < size; i++) {
                if (UserHalHelper.isAdmin(usersInfo.existingUsers.get(i).flags)) {
                    totalAdminUsers++;
                }
            }
            if (totalAdminUsers == 1) {
                isLastAdmin = true;
            }
        }

        // First remove user from android and then remove from HAL because HAL remove user is one
        // way call.
        // TODO(b/170887769): rename hasCallerRestrictions to fromCarDevicePolicyManager (or use an
        // int / enum to indicate if it's called from CarUserManager or CarDevicePolicyManager), as
        // it's counter-intuitive that it's "allowed even when disallowed" when it
        // "has caller restrictions"
        boolean evenWhenDisallowed = hasCallerRestrictions;
        int result = mUserManager.removeUserOrSetEphemeral(userId, evenWhenDisallowed);
        if (result == UserManager.REMOVE_RESULT_ERROR) {
            sendUserRemovalResult(userId, UserRemovalResult.STATUS_ANDROID_FAILURE, receiver);
            return;
        }

        if (isLastAdmin) {
            Slog.w(TAG,
                    "Last admin user successfully removed or set ephemeral. User Id: " + userId);
        }

        switch (result) {
            case UserManager.REMOVE_RESULT_REMOVED:
            case UserManager.REMOVE_RESULT_ALREADY_BEING_REMOVED:
                sendUserRemovalResult(userId,
                        isLastAdmin ? UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_REMOVED
                                : UserRemovalResult.STATUS_SUCCESSFUL, receiver);
            case UserManager.REMOVE_RESULT_SET_EPHEMERAL:
                sendUserRemovalResult(userId,
                        isLastAdmin ? UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_SET_EPHEMERAL
                                : UserRemovalResult.STATUS_SUCCESSFUL_SET_EPHEMERAL, receiver);
            default:
                sendUserRemovalResult(userId, UserRemovalResult.STATUS_ANDROID_FAILURE, receiver);
        }
    }

    /**
     * Should be called by {@code ICarImpl} only.
     */
    public void onUserRemoved(@NonNull UserHandle user) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "onUserRemoved: " + user.toString());
        }
        notifyHalUserRemoved(user);
    }

    private void notifyHalUserRemoved(@NonNull UserHandle user) {
        if (!isUserHalSupported()) return;

        if (user == null) {
            Slog.wtf(TAG, "notifyHalUserRemoved() called for null user");
            return;
        }

        int userId = user.getIdentifier();

        if (userId == UserHandle.USER_NULL) {
            Slog.wtf(TAG, "notifyHalUserRemoved() called for UserHandle.USER_NULL");
            return;
        }

        synchronized (mLockUser) {
            if (mFailedToCreateUserIds.get(userId)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "notifyHalUserRemoved(): skipping " + userId);
                }
                mFailedToCreateUserIds.delete(userId);
                return;
            }
        }

        android.hardware.automotive.vehicle.V2_0.UserInfo halUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halUser.userId = userId;
        halUser.flags = UserHalHelper.convertFlags(mUserHandleHelper, user);

        RemoveUserRequest request = new RemoveUserRequest();
        request.removedUserInfo = halUser;
        request.usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);
        mHal.removeUser(request);
    }

    private void sendUserRemovalResult(@UserIdInt int userId, @UserRemovalResult.Status int result,
            AndroidFuture<UserRemovalResult> receiver) {
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_REMOVE_USER_RESP, userId, result);
        receiver.complete(new UserRemovalResult(result));
    }

    private void sendUserSwitchUiCallback(@UserIdInt int targetUserId) {
        if (mUserSwitchUiReceiver == null) {
            Slog.w(TAG, "No User switch UI receiver.");
            return;
        }

        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SWITCH_USER_UI_REQ, targetUserId);
        try {
            mUserSwitchUiReceiver.send(targetUserId, null);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error calling user switch UI receiver.", e);
        }
    }

    /**
     * Used to create the initial user, even when it's disallowed by {@code DevicePolicyManager}.
     */
    @Nullable
    UserHandle createUserEvenWhenDisallowed(@Nullable String name, @NonNull String userType,
            int flags) {
        synchronized (mLockUser) {
            if (mICarServiceHelper == null) {
                Slog.wtf(TAG, "createUserEvenWhenDisallowed(): mICarServiceHelper not set yet",
                        new Exception());
                return null;
            }
        }

        try {
            ICarServiceHelper iCarServiceHelper;
            synchronized (mLockUser) {
                iCarServiceHelper = mICarServiceHelper;
            }
            UserHandle user = iCarServiceHelper.createUserEvenWhenDisallowed(name,
                    userType, flags);
            return user;
        } catch (RemoteException e) {
            Slog.e(TAG, "createUserEvenWhenDisallowed(" + UserHelperLite.safeName(name) + ", "
                    + userType + ", " + flags + ") failed", e);
            return null;
        }
    }

    @Override
    public void createUser(@Nullable String name, @NonNull String userType, int flags,
            int timeoutMs, @NonNull AndroidFuture<UserCreationResult> receiver) {
        createUser(name, userType, flags, timeoutMs, receiver, /* hasCallerRestrictions= */ false);
    }

    /**
     * Internal implementation of {@code createUser()}, which is used by both
     * {@code ICarUserService} and {@code ICarDevicePolicyService}.
     *
     * @param hasCallerRestrictions when {@code true}, if the caller user is not an admin, it can
     * only create admin users
     */
    public void createUser(@Nullable String name, @NonNull String userType, int flags,
            int timeoutMs, @NonNull AndroidFuture<UserCreationResult> receiver,
            boolean hasCallerRestrictions) {
        Objects.requireNonNull(userType, "user type cannot be null");
        Objects.requireNonNull(receiver, "receiver cannot be null");
        checkManageOrCreateUsersPermission(flags);
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_CREATE_USER_REQ,
                UserHelperLite.safeName(name), userType, flags, timeoutMs,
                hasCallerRestrictions ? 1 : 0);
        mHandler.post(() -> handleCreateUser(name, userType, flags, timeoutMs, receiver,
                hasCallerRestrictions));
    }

    private void handleCreateUser(@Nullable String name, @NonNull String userType,
            int flags, int timeoutMs, @NonNull AndroidFuture<UserCreationResult> receiver,
            boolean hasCallerRestrictions) {
        boolean isGuest = userType.equals(UserManager.USER_TYPE_FULL_GUEST);
        if (isGuest && flags != 0) {
            // Non-zero flags are not allowed when creating a guest user.
            Slogf.e(TAG, "Invalid flags %d specified when creating a guest user %s", flags, name);
            sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_INVALID_REQUEST);
            return;
        }
        if (hasCallerRestrictions) {
            // Restrictions:
            // - type/flag can only be normal user, admin, or guest
            // - non-admin user can only create non-admin users

            boolean validCombination;
            switch (userType) {
                case UserManager.USER_TYPE_FULL_SECONDARY:
                    validCombination = flags == 0
                        || (flags & UserManagerHelper.FLAG_ADMIN) == UserManagerHelper.FLAG_ADMIN;
                    break;
                case UserManager.USER_TYPE_FULL_GUEST:
                    validCombination = true;
                    break;
                default:
                    validCombination = false;
            }
            if (!validCombination) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "Invalid combination of user type(" + userType
                            + ") and flags (" + flags
                            + ") for caller with restrictions");
                }
                sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_INVALID_REQUEST);
                return;

            }

            int callingUserId = Binder.getCallingUserHandle().getIdentifier();
            UserHandle callingUser = mUserHandleHelper.getExistingUserHandle(callingUserId);
            if (!mUserHandleHelper.isAdminUser(callingUser)
                    && (flags & UserManagerHelper.FLAG_ADMIN) == UserManagerHelper.FLAG_ADMIN) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "Non-admin user " + callingUserId
                            + " can only create non-admin users");
                }
                sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_INVALID_REQUEST);
                return;
            }
        }

        UserHandle newUser;
        try {
            newUser = isGuest
                    ? mUserManager.createGuest(mContext, name).getUserHandle()
                    : mUserManager.createUser(name, userType, flags).getUserHandle();
            if (newUser == null) {
                Slog.w(TAG, "um.createUser() returned null for user of type " + userType
                        + " and flags " + flags);
                sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_ANDROID_FAILURE);
                return;
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "Created user: " + newUser.toString());
            }
            // TODO(b/196179969): enabled the log
            //EventLog.writeEvent(EventLogTags.CAR_USER_SVC_CREATE_USER_USER_CREATED, newUser.id,
            //        UserHelperLite.safeName(newUser.name), newUser.userType, newUser.flags);
        } catch (NullPointerException e) {
            // TODO(b/196179969): SHIPSTOP Remove it in follow-up CL, possible due to
            // getUserHandle();
            // This is possible when createGuest or createUser return null. As UserInfo is
            // eliminated, createGuest or createUser result can't be checked. In future CLs,
            // createGuest or createUser will be updated with the call which return user Handle.
            sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_ANDROID_FAILURE);
            return;
        } catch (RuntimeException e) {
            Slog.e(TAG, "Error creating user of type " + userType + " and flags"
                    + flags, e);
            sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_ANDROID_FAILURE);
            return;
        }

        if (!isUserHalSupported()) {
            sendUserCreationResult(receiver, UserCreationResult.STATUS_SUCCESSFUL, newUser, null);
            return;
        }

        CreateUserRequest request = new CreateUserRequest();
        request.usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);
        if (!TextUtils.isEmpty(name)) {
            request.newUserName = name;
        }
        request.newUserInfo.userId = newUser.getIdentifier();
        request.newUserInfo.flags = UserHalHelper.convertFlags(mUserHandleHelper, newUser);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "Create user request: " + request);
        }

        try {
            mHal.createUser(request, timeoutMs, (status, resp) -> {
                int resultStatus = UserCreationResult.STATUS_HAL_INTERNAL_FAILURE;
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "createUserResponse: status="
                            + UserHalHelper.halCallbackStatusToString(status) + ", resp=" + resp);
                }
                UserHandle user = null; // user returned in the result
                if (status != HalCallback.STATUS_OK) {
                    Slog.w(TAG, "invalid callback status ("
                            + UserHalHelper.halCallbackStatusToString(status) + ") for response "
                            + resp);
                    EventLog.writeEvent(EventLogTags.CAR_USER_SVC_CREATE_USER_RESP, status,
                            resultStatus, resp.errorMessage);
                    removeCreatedUser(newUser, "HAL call failed with "
                            + UserHalHelper.halCallbackStatusToString(status));
                    sendUserCreationResult(receiver, resultStatus, user, /* errorMsg= */ null);
                    return;
                }

                switch (resp.status) {
                    case CreateUserStatus.SUCCESS:
                        resultStatus = UserCreationResult.STATUS_SUCCESSFUL;
                        user = newUser;
                        break;
                    case CreateUserStatus.FAILURE:
                        // HAL failed to switch user
                        resultStatus = UserCreationResult.STATUS_HAL_FAILURE;
                        break;
                    default:
                        // Shouldn't happen because UserHalService validates the status
                        Slog.wtf(TAG, "Received invalid user switch status from HAL: " + resp);
                }
                EventLog.writeEvent(EventLogTags.CAR_USER_SVC_CREATE_USER_RESP, status,
                        resultStatus, resp.errorMessage);
                if (user == null) {
                    removeCreatedUser(newUser, "HAL returned "
                            + UserCreationResult.statusToString(resultStatus));
                }
                sendUserCreationResult(receiver, resultStatus, user, resp.errorMessage);
            });
        } catch (Exception e) {
            Slog.w(TAG, "mHal.createUser(" + request + ") failed", e);
            removeCreatedUser(newUser, "mHal.createUser() failed");
            sendUserCreationResultFailure(receiver, UserCreationResult.STATUS_HAL_INTERNAL_FAILURE);
        }
    }

    private void removeCreatedUser(@NonNull UserHandle user, @NonNull String reason) {
        Slogf.i(TAG, "removing %s reason: %s", user, reason);

        int userId = user.getIdentifier();
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_CREATE_USER_USER_REMOVED, userId, reason);

        synchronized (mLockUser) {
            mFailedToCreateUserIds.put(userId, true);
        }

        try {
            if (!mUserManager.removeUser(user)) {
                Slogf.w(TAG, "Failed to remove user %s", user);
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Failed to remove user %s", user);
        }
    }

    @Override
    public UserIdentificationAssociationResponse getUserIdentificationAssociation(
            @UserIdentificationAssociationType int[] types) {
        if (!isUserHalUserAssociationSupported()) {
            return UserIdentificationAssociationResponse.forFailure(VEHICLE_HAL_NOT_SUPPORTED);
        }

        Preconditions.checkArgument(!ArrayUtils.isEmpty(types), "must have at least one type");
        checkManageOrCreateUsersPermission("getUserIdentificationAssociation");

        int uid = getCallingUid();
        int userId = getCallingUserHandle().getIdentifier();
        EventLog.writeEvent(EventLogTags.CAR_USER_MGR_GET_USER_AUTH_REQ, uid, userId);

        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        request.userInfo.userId = userId;
        request.userInfo.flags = getHalUserInfoFlags(userId);

        request.numberAssociationTypes = types.length;
        for (int i = 0; i < types.length; i++) {
            request.associationTypes.add(types[i]);
        }

        UserIdentificationResponse halResponse = mHal.getUserAssociation(request);
        if (halResponse == null) {
            Slog.w(TAG, "getUserIdentificationAssociation(): HAL returned null for "
                    + Arrays.toString(types));
            return UserIdentificationAssociationResponse.forFailure();
        }

        int[] values = new int[halResponse.associations.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = halResponse.associations.get(i).value;
        }
        EventLog.writeEvent(EventLogTags.CAR_USER_MGR_GET_USER_AUTH_RESP, values.length);

        return UserIdentificationAssociationResponse.forSuccess(values, halResponse.errorMessage);
    }

    @Override
    public void setUserIdentificationAssociation(int timeoutMs,
            @UserIdentificationAssociationType int[] types,
            @UserIdentificationAssociationSetValue int[] values,
            AndroidFuture<UserIdentificationAssociationResponse> result) {
        if (!isUserHalUserAssociationSupported()) {
            result.complete(
                    UserIdentificationAssociationResponse.forFailure(VEHICLE_HAL_NOT_SUPPORTED));
            return;
        }

        Preconditions.checkArgument(!ArrayUtils.isEmpty(types), "must have at least one type");
        Preconditions.checkArgument(!ArrayUtils.isEmpty(values), "must have at least one value");
        if (types.length != values.length) {
            throw new IllegalArgumentException("types (" + Arrays.toString(types) + ") and values ("
                    + Arrays.toString(values) + ") should have the same length");
        }
        checkManageOrCreateUsersPermission("setUserIdentificationAssociation");

        int uid = getCallingUid();
        int userId = getCallingUserHandle().getIdentifier();
        EventLog.writeEvent(EventLogTags.CAR_USER_MGR_SET_USER_AUTH_REQ, uid, userId, types.length);

        UserIdentificationSetRequest request = new UserIdentificationSetRequest();
        request.userInfo.userId = userId;
        request.userInfo.flags = getHalUserInfoFlags(userId);

        request.numberAssociations = types.length;
        for (int i = 0; i < types.length; i++) {
            UserIdentificationSetAssociation association = new UserIdentificationSetAssociation();
            association.type = types[i];
            association.value = values[i];
            request.associations.add(association);
        }

        mHal.setUserAssociation(timeoutMs, request, (status, resp) -> {
            if (status != HalCallback.STATUS_OK) {
                Slog.w(TAG, "setUserIdentificationAssociation(): invalid callback status ("
                        + UserHalHelper.halCallbackStatusToString(status) + ") for response "
                        + resp);
                if (resp == null || TextUtils.isEmpty(resp.errorMessage)) {
                    EventLog.writeEvent(EventLogTags.CAR_USER_MGR_SET_USER_AUTH_RESP, 0);
                    result.complete(UserIdentificationAssociationResponse.forFailure());
                    return;
                }
                EventLog.writeEvent(EventLogTags.CAR_USER_MGR_SET_USER_AUTH_RESP, 0,
                        resp.errorMessage);
                result.complete(
                        UserIdentificationAssociationResponse.forFailure(resp.errorMessage));
                return;
            }
            int respSize = resp.associations.size();
            EventLog.writeEvent(EventLogTags.CAR_USER_MGR_SET_USER_AUTH_RESP, respSize,
                    resp.errorMessage);

            int[] responseTypes = new int[respSize];
            for (int i = 0; i < respSize; i++) {
                responseTypes[i] = resp.associations.get(i).value;
            }
            UserIdentificationAssociationResponse response = UserIdentificationAssociationResponse
                    .forSuccess(responseTypes, resp.errorMessage);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "setUserIdentificationAssociation(): resp= " + resp
                        + ", converted=" + response);
            }
            result.complete(response);
        });
    }

    /**
     * Gets the User HAL flags for the given user.
     *
     * @throws IllegalArgumentException if the user does not exist.
     */
    private int getHalUserInfoFlags(@UserIdInt int userId) {
        UserHandle user = mUserHandleHelper.getExistingUserHandle(userId);
        Preconditions.checkArgument(user != null, "no user for id %d", userId);
        return UserHalHelper.convertFlags(mUserHandleHelper, user);
    }

    static void sendUserSwitchResult(@NonNull AndroidFuture<UserSwitchResult> receiver,
            @UserSwitchResult.Status int userSwitchStatus) {
        sendUserSwitchResult(receiver, HalCallback.STATUS_INVALID, userSwitchStatus,
                /* errorMessage= */ null);
    }

    static void sendUserSwitchResult(@NonNull AndroidFuture<UserSwitchResult> receiver,
            @HalCallback.HalCallbackStatus int halCallbackStatus,
            @UserSwitchResult.Status int userSwitchStatus, @Nullable String errorMessage) {
        if (errorMessage != null) {
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SWITCH_USER_RESP, halCallbackStatus,
                    userSwitchStatus, errorMessage);
        } else {
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SWITCH_USER_RESP, halCallbackStatus,
                    userSwitchStatus);
        }
        receiver.complete(new UserSwitchResult(userSwitchStatus, errorMessage));
    }

    static void sendUserCreationResultFailure(@NonNull AndroidFuture<UserCreationResult> receiver,
            @UserCreationResult.Status int status) {
        sendUserCreationResult(receiver, status, /* user= */ null, /* errorMessage= */ null);
    }

    private static void sendUserCreationResult(@NonNull AndroidFuture<UserCreationResult> receiver,
            @UserCreationResult.Status int status, @NonNull UserHandle user,
            @Nullable String errorMessage) {
        if (TextUtils.isEmpty(errorMessage)) {
            errorMessage = null;
        }
        receiver.complete(new UserCreationResult(status, user, errorMessage));
    }

    /**
     * Calls activity manager for user switch.
     *
     * <p><b>NOTE</b> This method is meant to be called just by UserHalService.
     *
     * @param requestId for the user switch request
     * @param targetUserId of the target user
     *
     * @hide
     */
    public void switchAndroidUserFromHal(int requestId, @UserIdInt int targetUserId) {
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_SWITCH_USER_FROM_HAL_REQ, requestId,
                targetUserId);
        Slog.i(TAG, "User hal requested a user switch. Target user id " + targetUserId);

        boolean result = mAm.switchUser(UserHandle.of(targetUserId));
        if (result) {
            updateUserSwitchInProcess(requestId, targetUserId);
        } else {
            postSwitchHalResponse(requestId, targetUserId);
        }
    }

    private void updateUserSwitchInProcess(int requestId, @UserIdInt int targetUserId) {
        synchronized (mLockUser) {
            if (mUserIdForUserSwitchInProcess != UserHandle.USER_NULL) {
                // Some other user switch is in process.
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "User switch for user: " + mUserIdForUserSwitchInProcess
                            + " is in process. Abandoning it as a new user switch is requested"
                            + " for the target user: " + targetUserId);
                }
            }
            mUserIdForUserSwitchInProcess = targetUserId;
            mRequestIdForUserSwitchInProcess = requestId;
        }
    }

    private void postSwitchHalResponse(int requestId, @UserIdInt int targetUserId) {
        if (!isUserHalSupported()) return;

        UsersInfo usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper);
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_POST_SWITCH_USER_REQ, requestId,
                targetUserId, usersInfo.currentUser.userId);
        SwitchUserRequest request = createUserSwitchRequest(targetUserId, usersInfo);
        request.requestId = requestId;
        mHal.postSwitchResponse(request);
    }

    private SwitchUserRequest createUserSwitchRequest(@UserIdInt int targetUserId,
            @NonNull UsersInfo usersInfo) {
        UserHandle targetUser = mUserHandleHelper.getExistingUserHandle(targetUserId);
        android.hardware.automotive.vehicle.V2_0.UserInfo halTargetUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halTargetUser.userId = targetUser.getIdentifier();
        halTargetUser.flags = UserHalHelper.convertFlags(mUserHandleHelper, targetUser);
        SwitchUserRequest request = new SwitchUserRequest();
        request.targetUser = halTargetUser;
        request.usersInfo = usersInfo;
        return request;
    }

    /**
     * Checks if the User HAL is supported.
     */
    public boolean isUserHalSupported() {
        return mHal.isSupported();
    }

    /**
     * Checks if the User HAL user association is supported.
     */
    @Override
    public boolean isUserHalUserAssociationSupported() {
        return mHal.isUserAssociationSupported();
    }

    /**
     * Sets a callback which is invoked before user switch.
     *
     * <p>
     * This method should only be called by the Car System UI. The purpose of this call is to notify
     * Car System UI to show the user switch UI before the user switch.
     */
    @Override
    public void setUserSwitchUiCallback(@NonNull ICarResultReceiver receiver) {
        checkManageUsersPermission("setUserSwitchUiCallback");

        // Confirm that caller is system UI.
        String systemUiPackageName = getSystemUiPackageName();
        if (systemUiPackageName == null) {
            throw new IllegalStateException("System UI package not found.");
        }

        try {
            int systemUiUid = mContext
                    .createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0).getPackageManager()
                    .getPackageUid(systemUiPackageName, PackageManager.MATCH_SYSTEM_ONLY);
            int callerUid = Binder.getCallingUid();
            if (systemUiUid != callerUid) {
                throw new SecurityException("Invalid caller. Only" + systemUiPackageName
                        + " is allowed to make this call");
            }
        } catch (NameNotFoundException e) {
            throw new IllegalStateException("Package " + systemUiPackageName + " not found.");
        }

        mUserSwitchUiReceiver = receiver;
    }

    // TODO(157082995): This information can be taken from
    // PackageManageInternalImpl.getSystemUiServiceComponent
    @Nullable
    private String getSystemUiPackageName() {
        try {
            ComponentName componentName = ComponentName.unflattenFromString(mContext.getResources()
                    .getString(com.android.internal.R.string.config_systemUIServiceComponent));
            return componentName.getPackageName();
        } catch (RuntimeException e) {
            Slog.w(TAG, "error while getting system UI package name.", e);
            return null;
        }
    }

    private void updateDefaultUserRestriction() {
        // We want to set restrictions on system and guest users only once. These are persisted
        // onto disk, so it's sufficient to do it once + we minimize the number of disk writes.
        if (Settings.Global.getInt(mContext.getContentResolver(),
                CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, /* default= */ 0) != 0) {
            return;
        }
        // Only apply the system user restrictions if the system user is headless.
        if (UserManager.isHeadlessSystemUserMode()) {
            setSystemUserRestrictions();
        }
        Settings.Global.putInt(mContext.getContentResolver(),
                CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
    }

    private boolean isPersistentUser(@UserIdInt int userId) {
        return !mUserHandleHelper.isEphemeralUser(UserHandle.of(userId));
    }

    /**
     * Adds a new {@link UserLifecycleListener} to listen to user activity events.
     */
    public void addUserLifecycleListener(@NonNull UserLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        mHandler.post(() -> mUserLifecycleListeners.add(listener));
    }

    /**
     * Removes previously added {@link UserLifecycleListener}.
     */
    public void removeUserLifecycleListener(@NonNull UserLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        mHandler.post(() -> mUserLifecycleListeners.remove(listener));
    }

    private void onUserUnlocked(@UserIdInt int userId) {
        ArrayList<Runnable> tasks = null;
        synchronized (mLockUser) {
            sendPostSwitchToHalLocked(userId);
            if (userId == UserHandle.USER_SYSTEM) {
                if (!mUser0Unlocked) { // user 0, unlocked, do this only once
                    updateDefaultUserRestriction();
                    tasks = new ArrayList<>(mUser0UnlockTasks);
                    mUser0UnlockTasks.clear();
                    mUser0Unlocked = true;
                }
            } else { // none user0
                Integer user = userId;
                if (isPersistentUser(userId)) {
                    // current foreground user should stay in top priority.
                    if (userId == ActivityManager.getCurrentUser()) {
                        mBackgroundUsersToRestart.remove(user);
                        mBackgroundUsersToRestart.add(0, user);
                    }
                    // -1 for user 0
                    if (mBackgroundUsersToRestart.size() > (mMaxRunningUsers - 1)) {
                        int userToDrop = mBackgroundUsersToRestart.get(
                                mBackgroundUsersToRestart.size() - 1);
                        Slog.i(TAG, "New user unlocked:" + userId
                                + ", dropping least recently user from restart list:" + userToDrop);
                        // Drop the least recently used user.
                        mBackgroundUsersToRestart.remove(mBackgroundUsersToRestart.size() - 1);
                    }
                }
            }
        }
        if (tasks != null && tasks.size() > 0) {
            Slog.d(TAG, "User0 unlocked, run queued tasks:" + tasks.size());
            for (Runnable r : tasks) {
                r.run();
            }
        }
    }

    /**
     * Starts the specified user in the background.
     *
     * @param userId user to start in background
     * @param receiver to post results
     */
    public void startUserInBackground(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStartResult> receiver) {
        checkManageOrCreateUsersPermission("startUserInBackground");
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_START_USER_IN_BACKGROUND_REQ, userId);

        mHandler.post(() -> handleStartUserInBackground(userId, receiver));
    }

    private void handleStartUserInBackground(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStartResult> receiver) {
        // If the requested user is the current user, do nothing and return success.
        if (ActivityManager.getCurrentUser() == userId) {
            sendUserStartResult(
                    userId, UserStartResult.STATUS_SUCCESSFUL_USER_IS_CURRENT_USER, receiver);
            return;
        }
        // If requested user does not exist, return error.
        if (mUserHandleHelper.getExistingUserHandle(userId) == null) {
            Slogf.w(TAG, "User %d does not exist", userId);
            sendUserStartResult(userId, UserStartResult.STATUS_USER_DOES_NOT_EXIST, receiver);
            return;
        }

        if (!mAmHelper.startUserInBackground(userId)) {
            Slogf.w(TAG, "Failed to start user %d in background", userId);
            sendUserStartResult(userId, UserStartResult.STATUS_ANDROID_FAILURE, receiver);
            return;
        }

        // TODO(b/181331178): We are not updating mBackgroundUsersToRestart or
        // mBackgroundUsersRestartedHere, which were only used for the garage mode. Consider
        // renaming them to make it more clear.
        sendUserStartResult(userId, UserStartResult.STATUS_SUCCESSFUL, receiver);
    }

    private void sendUserStartResult(@UserIdInt int userId, @UserStartResult.Status int result,
            @NonNull AndroidFuture<UserStartResult> receiver) {
        EventLog.writeEvent(
                EventLogTags.CAR_USER_SVC_START_USER_IN_BACKGROUND_RESP, userId, result);
        receiver.complete(new UserStartResult(result));
    }

    /**
     * Starts all background users that were active in system.
     *
     * @return list of background users started successfully.
     */
    @NonNull
    public ArrayList<Integer> startAllBackgroundUsersInGarageMode() {
        synchronized (mLockUser) {
            if (!mStartBackgroundUsersOnGarageMode) {
                Slogf.i(TAG, "Background users are not started as mStartBackgroundUsersOnGarageMode"
                        + " is false.");
                return new ArrayList<>();
            }
        }

        ArrayList<Integer> users;
        synchronized (mLockUser) {
            users = new ArrayList<>(mBackgroundUsersToRestart);
            mBackgroundUsersRestartedHere.clear();
            mBackgroundUsersRestartedHere.addAll(mBackgroundUsersToRestart);
        }
        ArrayList<Integer> startedUsers = new ArrayList<>();
        for (Integer user : users) {
            if (user == ActivityManager.getCurrentUser()) {
                continue;
            }
            if (mAmHelper.startUserInBackground(user)) {
                if (mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(user))) {
                    // already unlocked / unlocking. No need to unlock.
                    startedUsers.add(user);
                } else if (mAmHelper.unlockUser(user)) {
                    startedUsers.add(user);
                } else { // started but cannot unlock
                    Slog.w(TAG, "Background user started but cannot be unlocked:" + user);
                    if (mUserManager.isUserRunning(UserHandle.of(user))) {
                        // add to started list so that it can be stopped later.
                        startedUsers.add(user);
                    }
                }
            }
        }
        // Keep only users that were re-started in mBackgroundUsersRestartedHere
        synchronized (mLockUser) {
            ArrayList<Integer> usersToRemove = new ArrayList<>();
            for (Integer user : mBackgroundUsersToRestart) {
                if (!startedUsers.contains(user)) {
                    usersToRemove.add(user);
                }
            }
            mBackgroundUsersRestartedHere.removeAll(usersToRemove);
        }
        return startedUsers;
    }

    /**
     * Stops the specified background user.
     *
     * @param userId user to stop
     * @param receiver to post results
     */
    public void stopUser(@UserIdInt int userId, @NonNull AndroidFuture<UserStopResult> receiver) {
        checkManageOrCreateUsersPermission("stopUser");
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_STOP_USER_REQ, userId);

        mHandler.post(() -> handleStopUser(userId, receiver));
    }

    private void handleStopUser(
            @UserIdInt int userId, @NonNull AndroidFuture<UserStopResult> receiver) {
        @UserStopResult.Status int userStopStatus = stopBackgroundUserInternal(userId);
        sendUserStopResult(userId, userStopStatus, receiver);
    }

    private void sendUserStopResult(@UserIdInt int userId, @UserStopResult.Status int result,
            @NonNull AndroidFuture<UserStopResult> receiver) {
        EventLog.writeEvent(EventLogTags.CAR_USER_SVC_STOP_USER_RESP, userId, result);
        receiver.complete(new UserStopResult(result));
    }

    private @UserStopResult.Status int stopBackgroundUserInternal(@UserIdInt int userId) {
        int r = mAmHelper.stopUserWithDelayedLocking(userId, true);
        switch(r) {
            case USER_OP_SUCCESS:
                return UserStopResult.STATUS_SUCCESSFUL;
            case USER_OP_ERROR_IS_SYSTEM:
                Slogf.w(TAG, "Cannot stop the system user: %d", userId);
                return UserStopResult.STATUS_FAILURE_SYSTEM_USER;
            case USER_OP_IS_CURRENT:
                Slogf.w(TAG, "Cannot stop the current user: %d", userId);
                return UserStopResult.STATUS_FAILURE_CURRENT_USER;
            case USER_OP_UNKNOWN_USER:
                Slogf.w(TAG, "Cannot stop the user that does not exist: %d", userId);
                return UserStopResult.STATUS_USER_DOES_NOT_EXIST;
            default:
                Slogf.w(TAG, "stopUser failed, user: %d, err: %d", userId, r);
        }
        return UserStopResult.STATUS_ANDROID_FAILURE;
    }

    /**
     * Sets boolean to control background user operations during garage mode.
     */
    public void setStartBackgroundUsersOnGarageMode(boolean enable) {
        synchronized (mLockUser) {
            mStartBackgroundUsersOnGarageMode = enable;
        }
    }

    /**
     * Stops a background user.
     *
     * @return whether stopping succeeds.
     */
    public boolean stopBackgroundUserInGagageMode(@UserIdInt int userId) {
        synchronized (mLockUser) {
            if (!mStartBackgroundUsersOnGarageMode) {
                Slogf.i(TAG, "Background users are not stopped as mStartBackgroundUsersOnGarageMode"
                        + " is false.");
                return false;
            }
        }

        @UserStopResult.Status int userStopStatus = stopBackgroundUserInternal(userId);
        if (UserStopResult.isSuccess(userStopStatus)) {
            // Remove the stopped user from the mBackgroundUserRestartedHere list.
            synchronized (mLockUser) {
                mBackgroundUsersRestartedHere.remove(Integer.valueOf(userId));
            }
            return true;
        }
        return false;
    }

    /**
     * Notifies all registered {@link UserLifecycleListener} with the event passed as argument.
     */
    public void onUserLifecycleEvent(@UserLifecycleEventType int eventType,
            @UserIdInt int fromUserId, @UserIdInt int toUserId) {
        int userId = toUserId;

        // Handle special cases first...
        if (eventType == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
            onUserSwitching(fromUserId, toUserId);
        } else if (eventType == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED) {
            onUserUnlocked(userId);
        }

        // ...then notify listeners.
        UserLifecycleEvent event = new UserLifecycleEvent(eventType, fromUserId, userId);

        mHandler.post(() -> {
            handleNotifyServiceUserLifecycleListeners(event);
            handleNotifyAppUserLifecycleListeners(event);
        });
    }

    private void sendPostSwitchToHalLocked(@UserIdInt int userId) {
        if (mUserIdForUserSwitchInProcess == UserHandle.USER_NULL
                || mUserIdForUserSwitchInProcess != userId
                || mRequestIdForUserSwitchInProcess == 0) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "No user switch request Id. No android post switch sent.");
            }
            return;
        }
        postSwitchHalResponse(mRequestIdForUserSwitchInProcess, mUserIdForUserSwitchInProcess);
        mUserIdForUserSwitchInProcess = UserHandle.USER_NULL;
        mRequestIdForUserSwitchInProcess = 0;
    }

    private void handleNotifyAppUserLifecycleListeners(UserLifecycleEvent event) {
        int listenersSize = mAppLifecycleListeners.size();
        if (listenersSize == 0) {
            Slogf.d(TAG, "No app listener to be notified of %s", event);
            return;
        }
        // Must use a different TimingsTraceLog because it's another thread
        Slogf.d(TAG, "Notifying %d app listeners of %s", listenersSize, event);
        int userId = event.getUserId();
        TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        int eventType = event.getEventType();
        t.traceBegin("notify-app-listeners-user-" + userId + "-event-" + eventType);
        for (int i = 0; i < listenersSize; i++) {
            AppLifecycleListener listener = mAppLifecycleListeners.valueAt(i);
            Bundle data = new Bundle();
            data.putInt(CarUserManager.BUNDLE_PARAM_ACTION, eventType);

            int fromUserId = event.getPreviousUserId();
            if (fromUserId != UserHandle.USER_NULL) {
                data.putInt(CarUserManager.BUNDLE_PARAM_PREVIOUS_USER_ID, fromUserId);
            }
            Slogf.d(TAG, "Notifying listener %s", listener);
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_NOTIFY_APP_LIFECYCLE_LISTENER,
                    listener.uid, listener.packageName, eventType, fromUserId, userId);
            try {
                t.traceBegin("notify-app-listener-" + listener.toShortString());
                listener.receiver.send(userId, data);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Error calling lifecycle listener %s", listener);
            } finally {
                t.traceEnd();
            }
        }
        t.traceEnd(); // notify-app-listeners-user-USERID-event-EVENT_TYPE
    }

    private void handleNotifyServiceUserLifecycleListeners(UserLifecycleEvent event) {
        TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        if (mUserLifecycleListeners.isEmpty()) {
            Slog.w(TAG, "Not notifying internal UserLifecycleListeners");
            return;
        } else if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "Notifying " + mUserLifecycleListeners.size()
                    + " service listeners of " + event);
        }

        int userId = event.getUserId();
        int eventType = event.getEventType();
        t.traceBegin("notify-listeners-user-" + userId + "-event-" + eventType);
        for (UserLifecycleListener listener : mUserLifecycleListeners) {
            String listenerName = FunctionalUtils.getLambdaName(listener);
            EventLog.writeEvent(EventLogTags.CAR_USER_SVC_NOTIFY_INTERNAL_LIFECYCLE_LISTENER,
                    listenerName, eventType, event.getPreviousUserId(), userId);
            try {
                t.traceBegin("notify-listener-" + listenerName);
                listener.onEvent(event);
            } catch (RuntimeException e) {
                Slog.e(TAG,
                        "Exception raised when invoking onEvent for " + listenerName, e);
            } finally {
                t.traceEnd();
            }
        }
        t.traceEnd(); // notify-listeners-user-USERID-event-EVENT_TYPE
    }

    private void onUserSwitching(@UserIdInt int fromUserId, @UserIdInt int toUserId) {
        Slog.i(TAG, "onUserSwitching() callback for user " + toUserId);
        TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        t.traceBegin("onUserSwitching-" + toUserId);

        // Switch HAL users if user switch is not requested by CarUserService
        notifyHalLegacySwitch(fromUserId, toUserId);

        mInitialUserSetter.setLastActiveUser(toUserId);

        t.traceEnd();
    }

    private void notifyHalLegacySwitch(@UserIdInt int fromUserId, @UserIdInt int toUserId) {
        synchronized (mLockUser) {
            if (mUserIdForUserSwitchInProcess != UserHandle.USER_NULL) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "notifyHalLegacySwitch(" + fromUserId + ", " + toUserId
                            + "): not needed, normal switch for " + mUserIdForUserSwitchInProcess);
                }
                return;
            }
        }

        if (!isUserHalSupported()) return;

        // switch HAL user
        UsersInfo usersInfo = UserHalHelper.newUsersInfo(mUserManager, mUserHandleHelper,
                fromUserId);
        SwitchUserRequest request = createUserSwitchRequest(toUserId, usersInfo);
        mHal.legacyUserSwitch(request);
    }

    /**
     * Runs the given runnable when user 0 is unlocked. If user 0 is already unlocked, it is
     * run inside this call.
     *
     * @param r Runnable to run.
     */
    public void runOnUser0Unlock(@NonNull Runnable r) {
        Objects.requireNonNull(r, "runnable cannot be null");
        boolean runNow = false;
        synchronized (mLockUser) {
            if (mUser0Unlocked) {
                runNow = true;
            } else {
                mUser0UnlockTasks.add(r);
            }
        }
        if (runNow) {
            r.run();
        }
    }

    @VisibleForTesting
    @NonNull
    ArrayList<Integer> getBackgroundUsersToRestart() {
        ArrayList<Integer> backgroundUsersToRestart = null;
        synchronized (mLockUser) {
            backgroundUsersToRestart = new ArrayList<>(mBackgroundUsersToRestart);
        }
        return backgroundUsersToRestart;
    }

    private void setSystemUserRestrictions() {
        // Disable Location service for system user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        locationManager.setLocationEnabledForUser(
                /* enabled= */ false, UserHandle.of(UserHandle.USER_SYSTEM));
    }

    private void checkInteractAcrossUsersPermission(String message) {
        checkHasAtLeastOnePermissionGranted(mContext, message,
                android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    /**
     * Manages the required number of pre-created users.
     */
    @Override
    public void updatePreCreatedUsers() {
        checkManageOrCreateUsersPermission("preCreateUsers");
        preCreateUsersInternal();
    }

    private void preCreateUsersInternal() {
        mHandler.post(() -> mUserPreCreator.managePreCreatedUsers());
    }

    // TODO(b/167698977): members below were copied from UserManagerService; it would be better to
    // move them to some internal android.os class instead.
    private static final int ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION =
            UserManagerHelper.FLAG_MANAGED_PROFILE
            | UserManagerHelper.FLAG_PROFILE
            | UserManagerHelper.FLAG_EPHEMERAL
            | UserManagerHelper.FLAG_RESTRICTED
            | UserManagerHelper.FLAG_GUEST
            | UserManagerHelper.FLAG_DEMO
            | UserManagerHelper.FLAG_FULL;

    static void checkManageUsersPermission(String message) {
        if (!hasManageUsersPermission()) {
            throw new SecurityException("You need " + MANAGE_USERS + " permission to: " + message);
        }
    }

    private static void checkManageOrCreateUsersPermission(String message) {
        if (!hasManageOrCreateUsersPermission()) {
            throw new SecurityException(
                    "You either need " + MANAGE_USERS + " or " + CREATE_USERS + " permission to: "
            + message);
        }
    }

    private static void checkManageOrCreateUsersPermission(int creationFlags) {
        if ((creationFlags & ~ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION) == 0) {
            if (!hasManageOrCreateUsersPermission()) {
                throw new SecurityException("You either need " + MANAGE_USERS + " or "
                        + CREATE_USERS + "permission to create a user with flags "
                        + creationFlags);
            }
        } else if (!hasManageUsersPermission()) {
            throw new SecurityException("You need " + MANAGE_USERS + " permission to create a user"
                    + " with flags " + creationFlags);
        }
    }

    private static boolean hasManageUsersPermission() {
        final int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(MANAGE_USERS, callingUid);
    }

    private static boolean hasManageUsersOrPermission(String alternativePermission) {
        final int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(MANAGE_USERS, callingUid)
                || hasPermissionGranted(alternativePermission, callingUid);
    }

    private static boolean hasManageOrCreateUsersPermission() {
        return hasManageUsersOrPermission(CREATE_USERS);
    }

    private static boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(permission, uid, /* owningUid= */ -1,
                /* exported= */ true) == PackageManager.PERMISSION_GRANTED;
    }
}

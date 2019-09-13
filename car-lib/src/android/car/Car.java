/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.car;

import static android.car.CarLibLog.TAG_CAR;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.car.cluster.CarInstrumentClusterManager;
import android.car.cluster.ClusterActivityState;
import android.car.content.pm.CarPackageManager;
import android.car.diagnostic.CarDiagnosticManager;
import android.car.drivingstate.CarDrivingStateManager;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarSensorManager;
import android.car.hardware.CarVendorExtensionManager;
import android.car.hardware.cabin.CarCabinManager;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarProperty;
import android.car.media.CarAudioManager;
import android.car.media.CarMediaManager;
import android.car.navigation.CarNavigationStatusManager;
import android.car.settings.CarConfigurationManager;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.car.test.CarTestManagerBinderWrapper;
import android.car.trust.CarTrustAgentEnrollmentManager;
import android.car.vms.VmsSubscriberManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;

/**
 *   Top level car API for embedded Android Auto deployments.
 *   This API works only for devices with {@link PackageManager#FEATURE_AUTOMOTIVE}
 *   Calling this API on a device with no such feature will lead to an exception.
 */
public final class Car {

    /**
     * Binder service name of car service registered to service manager.
     *
     * @hide
     */
    public static final String CAR_SERVICE_BINDER_SERVICE_NAME = "car_service";
    /**
     * Service name for {@link CarSensorManager}, to be used in {@link #getCarManager(String)}.
     *
     * @deprecated  {@link CarSensorManager} is deprecated. Use {@link CarPropertyManager} instead.
     */
    @Deprecated
    public static final String SENSOR_SERVICE = "sensor";

    /** Service name for {@link CarInfoManager}, to be used in {@link #getCarManager(String)}. */
    public static final String INFO_SERVICE = "info";

    /** Service name for {@link CarAppFocusManager}. */
    public static final String APP_FOCUS_SERVICE = "app_focus";

    /** Service name for {@link CarPackageManager} */
    public static final String PACKAGE_SERVICE = "package";

    /** Service name for {@link CarAudioManager} */
    public static final String AUDIO_SERVICE = "audio";

    /** Service name for {@link CarNavigationStatusManager} */
    public static final String CAR_NAVIGATION_SERVICE = "car_navigation_service";

    /**
     * Service name for {@link CarInstrumentClusterManager}
     *
     * @deprecated CarInstrumentClusterManager is being deprecated
     * @hide
     */
    @Deprecated
    public static final String CAR_INSTRUMENT_CLUSTER_SERVICE = "cluster_service";

    /**
     * Service name for {@link CarCabinManager}.
     *
     * @deprecated {@link CarCabinManager} is deprecated. Use {@link CarPropertyManager} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String CABIN_SERVICE = "cabin";

    /**
     * @hide
     */
    @SystemApi
    public static final String DIAGNOSTIC_SERVICE = "diagnostic";

    /**
     * Service name for {@link CarHvacManager}
     * @deprecated {@link CarHvacManager} is deprecated. Use {@link CarPropertyManager} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String HVAC_SERVICE = "hvac";

    /**
     * @hide
     */
    @SystemApi
    public static final String POWER_SERVICE = "power";

    /**
     * @hide
     */
    @SystemApi
    public static final String PROJECTION_SERVICE = "projection";

    /**
     * Service name for {@link CarPropertyManager}
     */
    public static final String PROPERTY_SERVICE = "property";

    /**
     * Service name for {@link CarVendorExtensionManager}
     *
     * @deprecated {@link CarVendorExtensionManager} is deprecated.
     * Use {@link CarPropertyManager} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String VENDOR_EXTENSION_SERVICE = "vendor_extension";

    /**
     * @hide
     */
    public static final String BLUETOOTH_SERVICE = "car_bluetooth";

    /**
     * @hide
     */
    @SystemApi
    public static final String VMS_SUBSCRIBER_SERVICE = "vehicle_map_subscriber_service";

    /**
     * Service name for {@link CarDrivingStateManager}
     * @hide
     */
    @SystemApi
    public static final String CAR_DRIVING_STATE_SERVICE = "drivingstate";

    /**
     * Service name for {@link CarUxRestrictionsManager}
     */
    public static final String CAR_UX_RESTRICTION_SERVICE = "uxrestriction";

    /**
     * Service name for {@link android.car.settings.CarConfigurationManager}
     */
    public static final String CAR_CONFIGURATION_SERVICE = "configuration";

    /**
     * Service name for {@link android.car.media.CarMediaManager}
     * @hide
     */
    public static final String CAR_MEDIA_SERVICE = "car_media";

    /**
     *
     * Service name for {@link android.car.CarBugreportManager}
     * @hide
     */
    public static final String CAR_BUGREPORT_SERVICE = "car_bugreport";

    /**
     * @hide
     */
    @SystemApi
    public static final String STORAGE_MONITORING_SERVICE = "storage_monitoring";

    /**
     * Service name for {@link android.car.trust.CarTrustAgentEnrollmentManager}
     * @hide
     */
    @SystemApi
    public static final String CAR_TRUST_AGENT_ENROLLMENT_SERVICE = "trust_enroll";

    /**
     * Service for testing. This is system app only feature.
     * Service name for {@link CarTestManager}, to be used in {@link #getCarManager(String)}.
     * @hide
     */
    @SystemApi
    public static final String TEST_SERVICE = "car-service-test";

    /** Permission necessary to access car's mileage information.
     *  @hide
     */
    @SystemApi
    public static final String PERMISSION_MILEAGE = "android.car.permission.CAR_MILEAGE";

    /** Permission necessary to access car's energy information. */
    public static final String PERMISSION_ENERGY = "android.car.permission.CAR_ENERGY";

    /** Permission necessary to access car's VIN information */
    public static final String PERMISSION_IDENTIFICATION =
            "android.car.permission.CAR_IDENTIFICATION";

    /** Permission necessary to access car's speed. */
    public static final String PERMISSION_SPEED = "android.car.permission.CAR_SPEED";

    /** Permission necessary to access car's dynamics state.
     *  @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DYNAMICS_STATE =
            "android.car.permission.CAR_DYNAMICS_STATE";

    /** Permission necessary to access car's fuel door and ev charge port. */
    public static final String PERMISSION_ENERGY_PORTS = "android.car.permission.CAR_ENERGY_PORTS";

    /** Permission necessary to read car's exterior lights information.
     *  @hide
     */
    @SystemApi
    public static final String PERMISSION_EXTERIOR_LIGHTS =
            "android.car.permission.CAR_EXTERIOR_LIGHTS";

    /**
     * Permission necessary to read car's interior lights information.
     */
    public static final String PERMISSION_READ_INTERIOR_LIGHTS =
            "android.car.permission.READ_CAR_INTERIOR_LIGHTS";

    /** Permission necessary to control car's exterior lights.
     *  @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_EXTERIOR_LIGHTS =
            "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS";

    /**
     * Permission necessary to control car's interior lights.
     */
    public static final String PERMISSION_CONTROL_INTERIOR_LIGHTS =
            "android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS";

    /** Permission necessary to access car's powertrain information.*/
    public static final String PERMISSION_POWERTRAIN = "android.car.permission.CAR_POWERTRAIN";

    /**
     * Permission necessary to change car audio volume through {@link CarAudioManager}.
     */
    public static final String PERMISSION_CAR_CONTROL_AUDIO_VOLUME =
            "android.car.permission.CAR_CONTROL_AUDIO_VOLUME";

    /**
     * Permission necessary to change car audio settings through {@link CarAudioManager}.
     */
    public static final String PERMISSION_CAR_CONTROL_AUDIO_SETTINGS =
            "android.car.permission.CAR_CONTROL_AUDIO_SETTINGS";

    /**
     * Permission necessary to receive full audio ducking events from car audio focus handler.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_RECEIVE_CAR_AUDIO_DUCKING_EVENTS =
            "android.car.permission.RECEIVE_CAR_AUDIO_DUCKING_EVENTS";

    /**
     * Permission necessary to use {@link CarNavigationStatusManager}.
     */
    public static final String PERMISSION_CAR_NAVIGATION_MANAGER =
            "android.car.permission.CAR_NAVIGATION_MANAGER";

    /**
     * Permission necessary to start activities in the instrument cluster through
     * {@link CarInstrumentClusterManager}
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL =
            "android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL";

    /**
     * Application must have this permission in order to be launched in the instrument cluster
     * display.
     *
     * @hide
     */
    public static final String PERMISSION_CAR_DISPLAY_IN_CLUSTER =
            "android.car.permission.CAR_DISPLAY_IN_CLUSTER";

    /** Permission necessary to use {@link CarInfoManager}. */
    public static final String PERMISSION_CAR_INFO = "android.car.permission.CAR_INFO";

    /** Permission necessary to read temperature of car's exterior environment. */
    public static final String PERMISSION_EXTERIOR_ENVIRONMENT =
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT";

    /**
     * Permission necessary to access car specific communication channel.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VENDOR_EXTENSION =
            "android.car.permission.CAR_VENDOR_EXTENSION";

    /**
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_APP_BLOCKING =
            "android.car.permission.CONTROL_APP_BLOCKING";

    /**
     * Permission necessary to access car's engine information.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_ENGINE_DETAILED =
            "android.car.permission.CAR_ENGINE_DETAILED";

    /**
     * Permission necessary to access car's tire pressure information.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_TIRES = "android.car.permission.CAR_TIRES";

    /**
     * Permission necessary to access car's steering angle information.
     */
    public static final String PERMISSION_READ_STEERING_STATE =
            "android.car.permission.READ_CAR_STEERING";

    /**
     * Permission necessary to read and write display units for distance, fuel volume, tire pressure
     * and ev battery.
     */
    public static final String PERMISSION_READ_DISPLAY_UNITS =
            "android.car.permission.READ_CAR_DISPLAY_UNITS";
    /**
     * Permission necessary to control display units for distance, fuel volume, tire pressure
     * and ev battery.
     */
    public static final String PERMISSION_CONTROL_DISPLAY_UNITS =
            "android.car.permission.CONTROL_CAR_DISPLAY_UNITS";

    /**
     * Permission necessary to control car's door.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_DOORS =
            "android.car.permission.CONTROL_CAR_DOORS";

    /**
     * Permission necessary to control car's windows.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_WINDOWS =
            "android.car.permission.CONTROL_CAR_WINDOWS";

    /**
     * Permission necessary to control car's seats.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_SEATS =
            "android.car.permission.CONTROL_CAR_SEATS";

    /**
     * Permission necessary to control car's mirrors.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_MIRRORS =
            "android.car.permission.CONTROL_CAR_MIRRORS";

    /**
     * Permission necessary to access Car HVAC APIs.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_CLIMATE =
            "android.car.permission.CONTROL_CAR_CLIMATE";

    /**
     * Permission necessary to access Car POWER APIs.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_POWER = "android.car.permission.CAR_POWER";

    /**
     * Permission necessary to access Car PROJECTION system APIs.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_PROJECTION = "android.car.permission.CAR_PROJECTION";

    /**
     * Permission necessary to access projection status.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_PROJECTION_STATUS =
            "android.car.permission.ACCESS_CAR_PROJECTION_STATUS";

    /**
     * Permission necessary to mock vehicle hal for testing.
     * @hide
     * @deprecated mocking vehicle HAL in car service is no longer supported.
     */
    @SystemApi
    public static final String PERMISSION_MOCK_VEHICLE_HAL =
            "android.car.permission.CAR_MOCK_VEHICLE_HAL";

    /**
     * Permission necessary to access CarTestService.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_TEST_SERVICE =
            "android.car.permission.CAR_TEST_SERVICE";

    /**
     * Permission necessary to access CarDrivingStateService to get a Car's driving state.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DRIVING_STATE =
            "android.car.permission.CAR_DRIVING_STATE";

    /**
     * Permission necessary to access VMS client service.
     *
     * @hide
     */
    public static final String PERMISSION_BIND_VMS_CLIENT =
            "android.car.permission.BIND_VMS_CLIENT";

    /**
     * Permissions necessary to access VMS publisher APIs.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VMS_PUBLISHER = "android.car.permission.VMS_PUBLISHER";

    /**
     * Permissions necessary to access VMS subscriber APIs.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VMS_SUBSCRIBER = "android.car.permission.VMS_SUBSCRIBER";

    /**
     * Permissions necessary to read diagnostic information, including vendor-specific bits.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DIAGNOSTIC_READ_ALL =
        "android.car.permission.CAR_DIAGNOSTICS";

    /**
     * Permissions necessary to clear diagnostic information.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DIAGNOSTIC_CLEAR =
            "android.car.permission.CLEAR_CAR_DIAGNOSTICS";

    /**
     * Permission necessary to configure UX restrictions through {@link CarUxRestrictionsManager}.
     *
     * @hide
     */
    public static final String PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION =
            "android.car.permission.CAR_UX_RESTRICTIONS_CONFIGURATION";

    /**
     * Permissions necessary to clear diagnostic information.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_STORAGE_MONITORING =
            "android.car.permission.STORAGE_MONITORING";

    /**
     * Permission necessary to enroll a device as a trusted authenticator device.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_ENROLL_TRUST =
            "android.car.permission.CAR_ENROLL_TRUST";

    /** Type of car connection: platform runs directly in car. */
    public static final int CONNECTION_TYPE_EMBEDDED = 5;


    /** @hide */
    @IntDef({CONNECTION_TYPE_EMBEDDED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionType {}

    /**
     * Activity Action: Provide media playing through a media template app.
     * <p>Input: String extra mapped by {@link android.app.SearchManager#QUERY} is the query
     * used to start the media. String extra mapped by {@link #CAR_EXTRA_MEDIA_COMPONENT} is the
     * component name of the media app which user wants to play media on.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String CAR_INTENT_ACTION_MEDIA_TEMPLATE =
            "android.car.intent.action.MEDIA_TEMPLATE";

    /**
     * Used as a string extra field with {@link #CAR_INTENT_ACTION_MEDIA_TEMPLATE} to specify the
     * MediaBrowserService that user wants to start the media on.
     *
     * @hide
     */
    public static final String CAR_EXTRA_MEDIA_COMPONENT =
            "android.car.intent.extra.MEDIA_COMPONENT";

    /**
     * Used as a string extra field with {@link #CAR_INTENT_ACTION_MEDIA_TEMPLATE} to specify the
     * media app that user wants to start the media on. Note: this is not the templated media app.
     *
     * This is being deprecated. Use {@link #CAR_EXTRA_MEDIA_COMPONENT} instead.
     */
    public static final String CAR_EXTRA_MEDIA_PACKAGE = "android.car.intent.extra.MEDIA_PACKAGE";

    /**
     * Used as a string extra field of media session to specify the service corresponding to the
     * session.
     *
     * @hide
     */
    public static final String CAR_EXTRA_BROWSE_SERVICE_FOR_SESSION =
            "android.media.session.BROWSE_SERVICE";

    /** @hide */
    public static final String CAR_SERVICE_INTERFACE_NAME = "android.car.ICar";

    private static final String CAR_SERVICE_PACKAGE = "com.android.car";

    private static final String CAR_SERVICE_CLASS = "com.android.car.CarService";

    /**
     * Category used by navigation applications to indicate which activity should be launched on
     * the instrument cluster when such application holds
     * {@link CarAppFocusManager#APP_FOCUS_TYPE_NAVIGATION} focus.
     *
     * @hide
     */
    public static final String CAR_CATEGORY_NAVIGATION = "android.car.cluster.NAVIGATION";

    /**
     * When an activity is launched in the cluster, it will receive {@link ClusterActivityState} in
     * the intent's extra under this key, containing instrument cluster information such as
     * unobscured area, visibility, etc.
     *
     * @hide
     */
    @SystemApi
    public static final String CAR_EXTRA_CLUSTER_ACTIVITY_STATE =
            "android.car.cluster.ClusterActivityState";


    /**
     * Callback to notify the Lifecycle of car service.
     *
     * <p>Access to car service should happen
     * after {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} call with
     * {@code ready} set {@code true}.</p>
     *
     * <p>When {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} is
     * called with ready set to false, access to car service should stop until car service is ready
     * again from {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} call
     * with {@code ready} set to {@code true}.</p>
     * @hide
     */
    public interface CarServiceLifecycleListener {
        /**
         * Car service has gone through status change.
         *
         * <p>This is always called in the main thread context.</p>
         *
         * @param car {@code Car} object that was originally associated with this lister from
         *            {@link #createCar(Context, Handler, long, Car.CarServiceLifecycleListener)}
         *            call.
         * @param ready When {@code true, car service is ready and all accesses are ok.
         *              Otherwise car service has crashed or killed and will be restarted.
         */
        void onLifecycleChanged(@NonNull Car car, boolean ready);
    }

    /**
     * {@link #createCar(Context, Handler, long, CarServiceLifecycleListener)}'s
     * waitTimeoutMs value to use to wait forever inside the call until car service is ready.
     * @hide
     */
    public static final long CAR_WAIT_TIMEOUT_WAIT_FOREVER = -1;

    /**
     * {@link #createCar(Context, Handler, long, CarServiceLifecycleListener)}'s
     * waitTimeoutMs value to use to skip any waiting inside the call.
     * @hide
     */
    public static final long CAR_WAIT_TIMEOUT_DO_NOT_WAIT = 0;

    private static final long CAR_SERVICE_BIND_RETRY_INTERVAL_MS = 500;
    private static final long CAR_SERVICE_BIND_MAX_RETRY = 20;

    private static final long CAR_SERVICE_BINDER_POLLING_INTERVAL_MS = 50;
    private static final long CAR_SERVICE_BINDER_POLLING_MAX_RETRY = 100;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "STATE_", value = {
            STATE_DISCONNECTED,
            STATE_CONNECTING,
            STATE_CONNECTED,
    })
    @Target({ElementType.TYPE_USE})
    public @interface StateTypeEnum {}

    private static final boolean DBG = false;

    private final Context mContext;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ICar mService;
    @GuardedBy("mLock")
    private boolean mServiceBound;

    @GuardedBy("mLock")
    @StateTypeEnum
    private int mConnectionState;
    @GuardedBy("mLock")
    private int mConnectionRetryCount;

    private final Runnable mConnectionRetryRunnable = new Runnable() {
        @Override
        public void run() {
            startCarService();
        }
    };

    private final Runnable mConnectionRetryFailedRunnable = new Runnable() {
        @Override
        public void run() {
            mServiceConnectionListener.onServiceDisconnected(new ComponentName(CAR_SERVICE_PACKAGE,
                    CAR_SERVICE_CLASS));
        }
    };

    private final ServiceConnection mServiceConnectionListener =
            new ServiceConnection () {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                ICar newService = ICar.Stub.asInterface(service);
                if (newService == null) {
                    Log.wtf(TAG_CAR, "null binder service", new RuntimeException());
                    return;  // should not happen.
                }
                if (mService != null && mService.asBinder().equals(newService.asBinder())) {
                    // already connected.
                    return;
                }
                mConnectionState = STATE_CONNECTED;
                mService = newService;
            }
            if (mServiceConnectionListenerClient != null) {
                mServiceConnectionListenerClient.onServiceConnected(name, service);
            }
            if (mStatusChangeCallback != null) {
                mStatusChangeCallback.onLifecycleChanged(Car.this, true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                if (mConnectionState  == STATE_DISCONNECTED) {
                    // can happen when client calls disconnect before onServiceDisconnected call.
                    return;
                }
                handleCarDisconnectLocked();
            }
            if (mServiceConnectionListenerClient != null) {
                mServiceConnectionListenerClient.onServiceDisconnected(name);
            }
            if (mStatusChangeCallback != null) {
                mStatusChangeCallback.onLifecycleChanged(Car.this, false);
            }
        }
    };

    @Nullable
    private final ServiceConnection mServiceConnectionListenerClient;

    /** Can be added after ServiceManager.getService call */
    @Nullable
    private final CarServiceLifecycleListener mStatusChangeCallback;

    @GuardedBy("mLock")
    private final HashMap<String, CarManagerBase> mServiceMap = new HashMap<>();

    /** Handler for generic event dispatching. */
    private final Handler mEventHandler;

    private final Handler mMainThreadEventHandler;

    /**
     * A factory method that creates Car instance for all Car API access.
     * @param context
     * @param serviceConnectionListener listener for monitoring service connection.
     * @param handler the handler on which the callback should execute, or null to execute on the
     * service's main thread. Note: the service connection listener will be always on the main
     * thread regardless of the handler given.
     * @return Car instance if system is in car environment and returns {@code null} otherwise.
     *
     * @deprecated use {@link #createCar(Context, Handler)} instead.
     */
    @Deprecated
    public static Car createCar(Context context, ServiceConnection serviceConnectionListener,
            @Nullable Handler handler) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Log.e(TAG_CAR, "FEATURE_AUTOMOTIVE not declared while android.car is used");
            return null;
        }
        try {
            return new Car(context, /* service= */ null , serviceConnectionListener,
                    /* statusChangeListener= */ null, handler);
        } catch (IllegalArgumentException e) {
            // Expected when car service loader is not available.
        }
        return null;
    }

    /**
     * A factory method that creates Car instance for all Car API access using main thread {@code
     * Looper}.
     *
     * @see #createCar(Context, ServiceConnection, Handler)
     *
     * @deprecated use {@link #createCar(Context, Handler)} instead.
     */
    @Deprecated
    public static Car createCar(Context context, ServiceConnection serviceConnectionListener) {
      return createCar(context, serviceConnectionListener, null);
    }

    /**
     * Creates new {@link Car} object which connected synchronously to Car Service and ready to use.
     *
     * @param context application's context
     *
     * @return Car object if operation succeeded, otherwise null.
     */
    @Nullable
    public static Car createCar(Context context) {
        return createCar(context, (Handler) null);
    }

    /**
     * Creates new {@link Car} object which connected synchronously to Car Service and ready to use.
     *
     * @param context application's context
     * @param handler the handler on which the manager's callbacks will be executed, or null to
     * execute on the application's main thread.
     *
     * @return Car object if operation succeeded, otherwise null.
     */
    @Nullable
    public static Car createCar(Context context, @Nullable Handler handler) {
        Car car = null;
        IBinder service = null;
        boolean started = false;
        int retryCount = 0;
        while (true) {
            service = ServiceManager.getService(CAR_SERVICE_BINDER_SERVICE_NAME);
            if (car == null) {
                // service can be still null. The constructor is safe for null service.
                car = new Car(context, ICar.Stub.asInterface(service),
                        null /*serviceConnectionListener*/, null /*statusChangeListener*/, handler);
            }
            if (service != null) {
                if (!started) {  // specialization for most common case.
                    return car;
                }
                break;
            }
            if (!started) {
                car.startCarService();
                started = true;
            }
            retryCount++;
            if (retryCount > CAR_SERVICE_BINDER_POLLING_MAX_RETRY) {
                Log.e(TAG_CAR, "cannot get car_service, waited for car service (ms):"
                                + CAR_SERVICE_BINDER_POLLING_INTERVAL_MS
                                * CAR_SERVICE_BINDER_POLLING_MAX_RETRY,
                        new RuntimeException());
                return null;
            }
            try {
                Thread.sleep(CAR_SERVICE_BINDER_POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Log.e(CarLibLog.TAG_CAR, "interrupted while waiting for car_service",
                        new RuntimeException());
                return null;
            }
        }
        // Can be accessed from mServiceConnectionListener in main thread.
        synchronized (car) {
            if (car.mService == null) {
                car.mService = ICar.Stub.asInterface(service);
                Log.w(TAG_CAR,
                        "waited for car_service (ms):"
                                + CAR_SERVICE_BINDER_POLLING_INTERVAL_MS * retryCount,
                        new RuntimeException());
            }
            car.mConnectionState = STATE_CONNECTED;
        }
        return car;
    }

    /**
     * Creates new {@link Car} object with {@link CarServiceLifecycleListener}.
     *
     * <p> If car service is ready inside this call and if the caller is running in the main thread,
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} will be called
     * with ready set to be true. Otherwise,
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} will be called
     * from the main thread later. </p>
     *
     * <p>This call can block up to specified waitTimeoutMs to wait for car service to be ready.
     * If car service is not ready within the given time, it will return a Car instance in
     * disconnected state. Blocking main thread forever can lead into getting ANR (Application Not
     * Responding) killing from system and should not be used if the app is supposed to survive
     * across the crash / restart of car service. It can be still useful in case the app cannot do
     * anything without car service being ready. In any waiting, if the thread is getting
     * interrupted, it will return immediately.
     * </p>
     *
     * <p>Note that returned {@link Car} object is not guaranteed to be connected when there is
     * a limited timeout. Regardless of returned car being connected or not, it is recommended to
     * implement all car related initialization inside
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} and avoid the
     * needs to check if returned {@link Car} is connected or not from returned {@link Car}.</p>
     *
     * @param handler dispatches all Car*Manager events to this Handler. Exception is
     *                {@link CarServiceLifecycleListener} which will be always dispatched to main
     *                thread. Passing null leads into dispatching all Car*Manager callbacks to main
     *                thread as well.
     * @param waitTimeoutMs Setting this to {@link #CAR_WAIT_TIMEOUT_DO_NOT_WAIT} will guarantee
     *                      that the API does not wait for the car service at all. Setting this to
     *                      to {@link #CAR_WAIT_TIMEOUT_WAIT_FOREVER} will block the call forever
     *                      until the car service is ready. Setting any positive value will be
     *                      interpreted as timeout value.
     *
     * @hide
     */
    @NonNull
    public static Car createCar(@NonNull Context context,
            @Nullable Handler handler, long waitTimeoutMs,
            @NonNull CarServiceLifecycleListener statusChangeListener) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(statusChangeListener);
        Car car = null;
        IBinder service = null;
        boolean started = false;
        int retryCount = 0;
        long maxRetryCount = 0;
        if (waitTimeoutMs > 0) {
            maxRetryCount = waitTimeoutMs / CAR_SERVICE_BINDER_POLLING_INTERVAL_MS;
            // at least wait once if it is positive value.
            if (maxRetryCount == 0) {
                maxRetryCount = 1;
            }
        }
        boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
        while (true) {
            service = ServiceManager.getService(CAR_SERVICE_BINDER_SERVICE_NAME);
            if (car == null) {
                // service can be still null. The constructor is safe for null service.
                car = new Car(context, ICar.Stub.asInterface(service), null, statusChangeListener,
                        handler);
            }
            if (service != null) {
                if (!started) {  // specialization for most common case : car service already ready
                    car.dispatchCarReadyToMainThread(isMainThread);
                    // Needs this for CarServiceLifecycleListener. Note that ServiceConnection
                    // will skip the callback as valid mService is set already.
                    car.startCarService();
                    return car;
                }
                // service available after starting.
                break;
            }
            if (!started) {
                car.startCarService();
                started = true;
            }
            retryCount++;
            if (waitTimeoutMs < 0 && retryCount >= CAR_SERVICE_BINDER_POLLING_MAX_RETRY
                    && retryCount % CAR_SERVICE_BINDER_POLLING_MAX_RETRY == 0) {
                // Log warning if car service is not alive even for waiting forever case.
                Log.w(TAG_CAR, "car_service not ready, waited for car service (ms):"
                                + retryCount * CAR_SERVICE_BINDER_POLLING_INTERVAL_MS,
                        new RuntimeException());
            } else if (waitTimeoutMs >= 0 && retryCount > maxRetryCount) {
                if (waitTimeoutMs > 0) {
                    Log.w(TAG_CAR, "car_service not ready, waited for car service (ms):"
                                    + waitTimeoutMs,
                            new RuntimeException());
                }
                return car;
            }

            try {
                Thread.sleep(CAR_SERVICE_BINDER_POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG_CAR, "interrupted", new RuntimeException());
                return car;
            }
        }
        // Can be accessed from mServiceConnectionListener in main thread.
        synchronized (car.mLock) {
            Log.w(TAG_CAR,
                    "waited for car_service (ms):"
                            + retryCount * CAR_SERVICE_BINDER_POLLING_INTERVAL_MS,
                    new RuntimeException());
            // ServiceConnection has handled everything.
            if (car.mService != null) {
                return car;
            }
            // mService check in ServiceConnection prevents calling
            // onLifecycleChanged. So onLifecycleChanged should be called explicitly
            // but do it outside lock.
            car.mService = ICar.Stub.asInterface(service);
            car.mConnectionState = STATE_CONNECTED;
        }
        car.dispatchCarReadyToMainThread(isMainThread);
        return car;
    }

    private void dispatchCarReadyToMainThread(boolean isMainThread) {
        if (isMainThread) {
            mStatusChangeCallback.onLifecycleChanged(this, true);
        } else {
            // should dispatch to main thread.
            mMainThreadEventHandler.post(
                    () -> mStatusChangeCallback.onLifecycleChanged(this, true));
        }
    }

    private Car(Context context, @Nullable ICar service,
            @Nullable ServiceConnection serviceConnectionListener,
            @Nullable CarServiceLifecycleListener statusChangeListener,
            @Nullable Handler handler) {
        mContext = context;
        mEventHandler = determineEventHandler(handler);
        mMainThreadEventHandler = determineMainThreadEventHandler(mEventHandler);

        mService = service;
        if (service != null) {
            mConnectionState = STATE_CONNECTED;
        } else {
            mConnectionState = STATE_DISCONNECTED;
        }
        mServiceConnectionListenerClient = serviceConnectionListener;
        mStatusChangeCallback = statusChangeListener;
    }

    /**
     * Car constructor when ICar binder is already available. The binder can be null.
     * @hide
     */
    public Car(Context context, @Nullable ICar service, @Nullable Handler handler) {
        this(context, service, null /*serviceConnectionListener*/, null /*statusChangeListener*/,
                handler);
    }

    private static Handler determineMainThreadEventHandler(Handler eventHandler) {
        Looper mainLooper = Looper.getMainLooper();
        return (eventHandler.getLooper() == mainLooper) ? eventHandler : new Handler(mainLooper);
    }

    private static Handler determineEventHandler(@Nullable Handler handler) {
        if (handler == null) {
            Looper looper = Looper.getMainLooper();
            handler = new Handler(looper);
        }
        return handler;
    }

    /**
     * Connect to car service. This can be called while it is disconnected.
     * @throws IllegalStateException If connection is still on-going from previous
     *         connect call or it is already connected
     *
     * @deprecated this method is not need if this object is created via
     * {@link #createCar(Context, Handler)}.
     */
    @Deprecated
    public void connect() throws IllegalStateException {
        synchronized (mLock) {
            if (mConnectionState != STATE_DISCONNECTED) {
                throw new IllegalStateException("already connected or connecting");
            }
            mConnectionState = STATE_CONNECTING;
            startCarService();
        }
    }

    private void handleCarDisconnectLocked() {
        if (mConnectionState == STATE_DISCONNECTED) {
            // can happen when client calls disconnect with onServiceDisconnected already called.
            return;
        }
        mEventHandler.removeCallbacks(mConnectionRetryRunnable);
        mMainThreadEventHandler.removeCallbacks(mConnectionRetryFailedRunnable);
        mConnectionRetryCount = 0;
        tearDownCarManagersLocked();
        mService = null;
        mConnectionState = STATE_DISCONNECTED;
    }

    /**
     * Disconnect from car service. This can be called while disconnected. Once disconnect is
     * called, all Car*Managers from this instance becomes invalid, and
     * {@link Car#getCarManager(String)} will return different instance if it is connected again.
     */
    public void disconnect() {
        synchronized (mLock) {
            handleCarDisconnectLocked();
            if (mServiceBound) {
                mContext.unbindService(mServiceConnectionListener);
                mServiceBound = false;
            }
        }
    }

    /**
     * Tells if it is connected to the service or not. This will return false if it is still
     * connecting.
     * @return
     */
    public boolean isConnected() {
        synchronized (mLock) {
            return mService != null;
        }
    }

    /**
     * Tells if this instance is already connecting to car service or not.
     * @return
     */
    public boolean isConnecting() {
        synchronized (mLock) {
            return mConnectionState == STATE_CONNECTING;
        }
    }

    /** @hide */
    @VisibleForTesting
    public ServiceConnection getServiceConnectionListener() {
        return mServiceConnectionListener;
    }

    /**
     * Get car specific service as in {@link Context#getSystemService(String)}. Returned
     * {@link Object} should be type-casted to the desired service.
     * For example, to get sensor service,
     * SensorManagerService sensorManagerService = car.getCarManager(Car.SENSOR_SERVICE);
     * @param serviceName Name of service that should be created like {@link #SENSOR_SERVICE}.
     * @return Matching service manager or null if there is no such service.
     */
    @Nullable
    public Object getCarManager(String serviceName) {
        CarManagerBase manager;
        ICar service = getICarOrThrow();
        synchronized (mLock) {
            manager = mServiceMap.get(serviceName);
            if (manager == null) {
                try {
                    IBinder binder = service.getCarService(serviceName);
                    if (binder == null) {
                        Log.w(TAG_CAR, "getCarManager could not get binder for service:"
                                + serviceName);
                        return null;
                    }
                    manager = createCarManager(serviceName, binder);
                    if (manager == null) {
                        Log.w(TAG_CAR, "getCarManager could not create manager for service:"
                                        + serviceName);
                        return null;
                    }
                    mServiceMap.put(serviceName, manager);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
        return manager;
    }

    /**
     * Return the type of currently connected car.
     * @return
     */
    @ConnectionType
    public int getCarConnectionType() {
        return CONNECTION_TYPE_EMBEDDED;
    }

    @Nullable
    private CarManagerBase createCarManager(String serviceName, IBinder binder) {
        CarManagerBase manager = null;
        switch (serviceName) {
            case AUDIO_SERVICE:
                manager = new CarAudioManager(binder, mContext, mEventHandler);
                break;
            case SENSOR_SERVICE:
                manager = new CarSensorManager(binder, mContext, mEventHandler);
                break;
            case INFO_SERVICE:
                manager = new CarInfoManager(binder);
                break;
            case APP_FOCUS_SERVICE:
                manager = new CarAppFocusManager(binder, mEventHandler);
                break;
            case PACKAGE_SERVICE:
                manager = new CarPackageManager(binder, mContext);
                break;
            case CAR_NAVIGATION_SERVICE:
                manager = new CarNavigationStatusManager(binder);
                break;
            case CABIN_SERVICE:
                manager = new CarCabinManager(binder, mContext, mEventHandler);
                break;
            case DIAGNOSTIC_SERVICE:
                manager = new CarDiagnosticManager(binder, mContext, mEventHandler);
                break;
            case HVAC_SERVICE:
                manager = new CarHvacManager(binder, mContext, mEventHandler);
                break;
            case POWER_SERVICE:
                manager = new CarPowerManager(binder, mContext, mEventHandler);
                break;
            case PROJECTION_SERVICE:
                manager = new CarProjectionManager(binder, mEventHandler);
                break;
            case PROPERTY_SERVICE:
                manager = new CarPropertyManager(ICarProperty.Stub.asInterface(binder),
                    mEventHandler);
                break;
            case VENDOR_EXTENSION_SERVICE:
                manager = new CarVendorExtensionManager(binder, mEventHandler);
                break;
            case CAR_INSTRUMENT_CLUSTER_SERVICE:
                manager = new CarInstrumentClusterManager(binder, mEventHandler);
                break;
            case TEST_SERVICE:
                /* CarTestManager exist in static library. So instead of constructing it here,
                 * only pass binder wrapper so that CarTestManager can be constructed outside. */
                manager = new CarTestManagerBinderWrapper(binder);
                break;
            case VMS_SUBSCRIBER_SERVICE:
                manager = new VmsSubscriberManager(binder);
                break;
            case BLUETOOTH_SERVICE:
                manager = new CarBluetoothManager(binder, mContext);
                break;
            case STORAGE_MONITORING_SERVICE:
                manager = new CarStorageMonitoringManager(binder, mEventHandler);
                break;
            case CAR_DRIVING_STATE_SERVICE:
                manager = new CarDrivingStateManager(binder, mContext, mEventHandler);
                break;
            case CAR_UX_RESTRICTION_SERVICE:
                manager = new CarUxRestrictionsManager(binder, mContext, mEventHandler);
                break;
            case CAR_CONFIGURATION_SERVICE:
                manager = new CarConfigurationManager(binder);
                break;
            case CAR_TRUST_AGENT_ENROLLMENT_SERVICE:
                manager = new CarTrustAgentEnrollmentManager(binder, mContext, mEventHandler);
                break;
            case CAR_MEDIA_SERVICE:
                manager = new CarMediaManager(binder);
                break;
            case CAR_BUGREPORT_SERVICE:
                manager = new CarBugreportManager(binder, mContext);
                break;
            default:
                break;
        }
        return manager;
    }

    private void startCarService() {
        Intent intent = new Intent();
        intent.setPackage(CAR_SERVICE_PACKAGE);
        intent.setAction(Car.CAR_SERVICE_INTERFACE_NAME);
        boolean bound = mContext.bindServiceAsUser(intent, mServiceConnectionListener,
                Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF);
        synchronized (mLock) {
            if (!bound) {
                mConnectionRetryCount++;
                if (mConnectionRetryCount > CAR_SERVICE_BIND_MAX_RETRY) {
                    Log.w(TAG_CAR, "cannot bind to car service after max retry");
                    mMainThreadEventHandler.post(mConnectionRetryFailedRunnable);
                } else {
                    mEventHandler.postDelayed(mConnectionRetryRunnable,
                            CAR_SERVICE_BIND_RETRY_INTERVAL_MS);
                }
            } else {
                mEventHandler.removeCallbacks(mConnectionRetryRunnable);
                mMainThreadEventHandler.removeCallbacks(mConnectionRetryFailedRunnable);
                mConnectionRetryCount = 0;
                mServiceBound = true;
            }
        }
    }

    private ICar getICarOrThrow() throws IllegalStateException {
        synchronized (mLock) {
            if (mService == null) {
                throw new IllegalStateException("not connected");
            }
            return mService;
        }
    }

    private void tearDownCarManagersLocked() {
        // All disconnected handling should be only doing its internal cleanup.
        for (CarManagerBase manager: mServiceMap.values()) {
            manager.onCarDisconnected();
        }
        mServiceMap.clear();
    }
}

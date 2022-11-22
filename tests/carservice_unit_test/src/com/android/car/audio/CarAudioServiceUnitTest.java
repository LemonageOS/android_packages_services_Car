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

package com.android.car.audio;

import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS;
import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_OEM_AUDIO_SERVICE;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.car.media.CarAudioManager.INVALID_AUDIO_ZONE;
import static android.car.media.CarAudioManager.INVALID_REQUEST_ID;
import static android.car.media.CarAudioManager.INVALID_VOLUME_GROUP_ID;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCheckCallingOrSelfPermission;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_LOSS;
import static android.media.AudioManager.AUDIOFOCUS_NONE;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.AudioManager.EXTRA_VOLUME_STREAM_TYPE;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.MASTER_MUTE_CHANGED_ACTION;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.media.AudioManager.SUCCESS;
import static android.media.AudioManager.VOLUME_CHANGED_ACTION;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_MUTE;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.car.R.bool.audioPersistMasterMuteState;
import static com.android.car.R.bool.audioUseCarVolumeGroupMuting;
import static com.android.car.R.bool.audioUseCoreRouting;
import static com.android.car.R.bool.audioUseCoreVolume;
import static com.android.car.R.bool.audioUseDynamicRouting;
import static com.android.car.R.bool.audioUseHalDuckingSignals;
import static com.android.car.R.integer.audioVolumeAdjustmentContextsVersion;
import static com.android.car.R.integer.audioVolumeKeyEventTimeoutMs;
import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.car.audio.GainBuilder.DEFAULT_GAIN;
import static com.android.car.audio.GainBuilder.MAX_GAIN;
import static com.android.car.audio.GainBuilder.MIN_GAIN;
import static com.android.car.audio.GainBuilder.STEP_SIZE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.ICarOccupantZoneCallback;
import android.car.VehicleAreaSeat;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.media.AudioManagerHelper.AudioPatchInfo;
import android.car.builtin.os.UserManagerHelper;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioPatchHandle;
import android.car.media.CarVolumeGroupInfo;
import android.car.media.IMediaAudioRequestStatusCallback;
import android.car.media.IPrimaryZoneMediaAudioRequestCallback;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.hardware.automotive.audiocontrol.Reasons;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioGain;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.IAudioService;
import android.media.audiopolicy.AudioPolicy;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import com.android.car.CarInputService;
import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.R;
import com.android.car.audio.hal.AudioControlFactory;
import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.audio.hal.AudioControlWrapper.AudioControlDeathRecipient;
import com.android.car.audio.hal.AudioControlWrapperAidl;
import com.android.car.audio.hal.HalAudioGainCallback;
import com.android.car.audio.hal.HalFocusListener;
import com.android.car.oem.CarOemAudioDuckingProxyService;
import com.android.car.oem.CarOemAudioFocusProxyService;
import com.android.car.oem.CarOemAudioVolumeProxyService;
import com.android.car.oem.CarOemProxyService;
import com.android.car.test.utils.TemporaryFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioServiceUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarAudioServiceUnitTest.class.getSimpleName();
    private static final long TEST_CALLBACK_TIMEOUT_MS = 100;
    private static final int VOLUME_KEY_EVENT_TIMEOUT_MS = 3000;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE = 1;
    private static final int AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO = 2;
    private static final String MEDIA_TEST_DEVICE = "media_bus_device";
    private static final String OEM_TEST_DEVICE = "oem_bus_device";
    private static final String NAVIGATION_TEST_DEVICE = "navigation_bus_device";
    private static final String CALL_TEST_DEVICE = "call_bus_device";
    private static final String NOTIFICATION_TEST_DEVICE = "notification_bus_device";
    private static final String VOICE_TEST_DEVICE = "voice_bus_device";
    private static final String RING_TEST_DEVICE = "ring_bus_device";
    private static final String ALARM_TEST_DEVICE = "alarm_bus_device";
    private static final String SYSTEM_BUS_DEVICE = "system_bus_device";
    private static final String SECONDARY_TEST_DEVICE = "secondary_zone_bus";
    private static final String PRIMARY_ZONE_MICROPHONE_ADDRESS = "Built-In Mic";
    private static final String PRIMARY_ZONE_FM_TUNER_ADDRESS = "FM Tuner";
    // From the car audio configuration file in /res/raw/car_audio_configuration.xml
    private static final int SECONDARY_ZONE_ID = 1;
    private static final int OUT_OF_RANGE_ZONE = SECONDARY_ZONE_ID + 1;
    private static final int PRIMARY_ZONE_VOLUME_GROUP_COUNT = 4;
    private static final int SECONDARY_ZONE_VOLUME_GROUP_COUNT = 1;
    private static final int SECONDARY_ZONE_VOLUME_GROUP_ID = SECONDARY_ZONE_VOLUME_GROUP_COUNT - 1;
    private static final int TEST_PRIMARY_GROUP = 0;
    private static final int TEST_SECONDARY_GROUP = 1;
    private static final int TEST_THIRD_GROUP = 2;
    private static final int TEST_PRIMARY_GROUP_INDEX = 0;
    private static final int TEST_FLAGS = 0;
    private static final float TEST_VALUE = -.75f;
    private static final float INVALID_TEST_VALUE = -1.5f;
    private static final int TEST_DISPLAY_TYPE = 2;
    private static final int TEST_SEAT = 2;
    private static final int PRIMARY_OCCUPANT_ZONE = 0;
    private static final int SECONDARY_OCCUPANT_ZONE = 1;
    private static final int INVALID_STATUS = 0;

    private static final int TEST_DRIVER_OCCUPANT_ZONE_ID = 1;
    private static final int TEST_PASSENGER_OCCUPANT_ZONE_ID = 2;
    private static final int TEST_UNASSIGNED_OCCUPANT_ZONE_ID = 4;

    private static final CarOccupantZoneManager.OccupantZoneInfo TEST_DRIVER_OCCUPANT =
            getOccupantInfo(TEST_DRIVER_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER,
                    VehicleAreaSeat.SEAT_ROW_1_LEFT);
    private static final CarOccupantZoneManager.OccupantZoneInfo TEST_PASSENGER_OCCUPANT =
            getOccupantInfo(TEST_PASSENGER_OCCUPANT_ZONE_ID,
                    CarOccupantZoneManager.OCCUPANT_TYPE_FRONT_PASSENGER,
                    VehicleAreaSeat.SEAT_ROW_1_RIGHT);

    private static final String PROPERTY_RO_ENABLE_AUDIO_PATCH =
            "ro.android.car.audio.enableaudiopatch";

    private static final int MEDIA_APP_UID = 1086753;
    private static final int MEDIA_PASSENGER_APP_UID = 1186753;
    private static final String MEDIA_CLIENT_ID = "media-client-id";
    private static final String MEDIA_PACKAGE_NAME = "com.android.car.audio";
    private static final int MEDIA_EMPTY_FLAG = 0;
    private static final String REGISTRATION_ID = "meh";
    private static final int MEDIA_VOLUME_GROUP_ID = 0;
    private static final int NAVIGATION_VOLUME_GROUP_ID = 1;
    private static final int INVALID_USAGE = -1;
    private static final int INVALID_AUDIO_FEATURE = -1;
    private static final int TEST_DRIVER_USER_ID = 10;
    private static final int TEST_PASSENGER_USER_ID = 11;
    private static final int TEST_USER_ID_SECONDARY = 12;
    private static final int TEST_GAIN_INDEX = 4;

    private static final CarVolumeGroupInfo TEST_PRIMARY_VOLUME_INFO =
            new CarVolumeGroupInfo.Builder("group id " + TEST_PRIMARY_GROUP, PRIMARY_AUDIO_ZONE,
                    TEST_PRIMARY_GROUP).setMuted(true).setMinVolumeGainIndex(0)
                    .setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final CarVolumeGroupInfo TEST_SECONDARY_VOLUME_INFO =
            new CarVolumeGroupInfo.Builder("group id " + TEST_SECONDARY_GROUP, PRIMARY_AUDIO_ZONE,
                    TEST_SECONDARY_GROUP).setMuted(true).setMinVolumeGainIndex(0)
                    .setMaxVolumeGainIndex(MAX_GAIN / STEP_SIZE)
                    .setVolumeGainIndex(DEFAULT_GAIN / STEP_SIZE).build();

    private static final AudioDeviceInfo MICROPHONE_TEST_DEVICE =
            new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_MICROPHONE_ADDRESS)
            .setType(TYPE_BUILTIN_MIC)
            .setIsSource(true)
            .build();
    private static final AudioDeviceInfo FM_TUNER_TEST_DEVICE =
            new AudioDeviceInfoBuilder().setAddressName(PRIMARY_ZONE_FM_TUNER_ADDRESS)
            .setType(TYPE_FM_TUNER)
            .setIsSource(true)
            .build();

    private static final AudioFocusInfo TEST_AUDIO_FOCUS_INFO =
            new AudioFocusInfo(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION), MEDIA_APP_UID,
            MEDIA_CLIENT_ID, "com.android.car.audio",
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AUDIOFOCUS_NONE, /* loss= */ 0,
            Build.VERSION.SDK_INT);

    private static final AudioFocusInfo TEST_PASSENGER_AUDIO_FOCUS_INFO =
            new AudioFocusInfo(CarAudioContext
            .getAudioAttributeFromUsage(USAGE_MEDIA), MEDIA_PASSENGER_APP_UID,
            MEDIA_CLIENT_ID, "com.android.car.audio",
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AUDIOFOCUS_NONE, /* loss= */ 0,
            Build.VERSION.SDK_INT);

    private CarAudioService mCarAudioService;
    @Mock
    private Context mMockContext;
    @Mock
    private TelephonyManager mMockTelephonyManager;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private Resources mMockResources;
    @Mock
    private ContentResolver mMockContentResolver;
    @Mock
    IBinder mBinder;
    @Mock
    IBinder mVolumeCallbackBinder;
    @Mock
    IAudioControl mAudioControl;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private CarOccupantZoneService mMockOccupantZoneService;
    @Mock
    private CarOemProxyService mMockCarOemProxyService;
    @Mock
    private IAudioService mMockAudioService;
    @Mock
    private Uri mNavSettingUri;
    @Mock
    private AudioControlWrapperAidl mAudioControlWrapperAidl;
    @Mock
    private CarVolumeCallbackHandler mCarVolumeCallbackHandler;
    @Mock
    private CarInputService mMockCarInputService;

    private boolean mPersistMasterMute = true;
    private boolean mUseDynamicRouting = true;
    private boolean mUseHalAudioDucking = true;
    private boolean mUseCarVolumeGroupMuting = true;

    private TemporaryFile mTemporaryAudioConfigurationUsingCoreAudioFile;
    private TemporaryFile mTemporaryAudioConfigurationFile;
    private TemporaryFile mTemporaryAudioConfigurationWithoutZoneMappingFile;
    private Context mContext;
    private AudioDeviceInfo mMicrophoneInputDevice;
    private AudioDeviceInfo mFmTunerInputDevice;
    private AudioDeviceInfo mMediaOutputDevice;

    @Captor
    private ArgumentCaptor<BroadcastReceiver> mVolumeReceiverCaptor;

    public CarAudioServiceUnitTest() {
        super(CarAudioService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
                .spyStatic(AudioManager.class)
                .spyStatic(AudioManagerHelper.class)
                .spyStatic(AudioControlWrapperAidl.class)
                .spyStatic(AudioControlFactory.class)
                .spyStatic(SystemProperties.class)
                .spyStatic(ServiceManager.class);
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration)) {
            mTemporaryAudioConfigurationFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationFile.write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration File Location: "
                    + mTemporaryAudioConfigurationFile.getPath());
        }

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_without_zone_mapping)) {
            mTemporaryAudioConfigurationWithoutZoneMappingFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationWithoutZoneMappingFile
                    .write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration without Zone mapping File Location: "
                    + mTemporaryAudioConfigurationWithoutZoneMappingFile.getPath());
        }

        try (InputStream configurationStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_audio_routing_and_volume)) {
            mTemporaryAudioConfigurationUsingCoreAudioFile = new TemporaryFile("xml");
            mTemporaryAudioConfigurationUsingCoreAudioFile
                    .write(new String(configurationStream.readAllBytes()));
            Log.i(TAG, "Temporary Car Audio Configuration using Core Audio File Location: "
                    + mTemporaryAudioConfigurationUsingCoreAudioFile.getPath());
        }

        mockCoreAudioRoutingAndVolume();
        mockGrantCarControlAudioSettingsPermission();

        setupAudioControlHAL();
        setupService();

        when(Settings.Secure.getUriFor(
                CarSettings.Secure.KEY_AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL))
                .thenReturn(mNavSettingUri);
    }

    @After
    public void tearDown() throws Exception {
        mTemporaryAudioConfigurationFile.close();
        mTemporaryAudioConfigurationWithoutZoneMappingFile.close();
        CarLocalServices.removeServiceForTest(CarOemProxyService.class);
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    private void setupAudioControlHAL() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mAudioControl);
        doReturn(mBinder).when(AudioControlWrapperAidl::getService);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_DUCKING)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_FOCUS)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK)).thenReturn(true);
        when(mAudioControlWrapperAidl.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_GROUP_MUTING)).thenReturn(true);
        doReturn(mAudioControlWrapperAidl)
                .when(() -> AudioControlFactory.newAudioControl());
    }

    private void setupService() throws Exception {
        when(mMockContext.getSystemService(Context.TELEPHONY_SERVICE))
                .thenReturn(mMockTelephonyManager);
        when(mMockContext.getSystemService(Context.AUDIO_SERVICE))
                .thenReturn(mAudioManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        doReturn(true)
                .when(() -> AudioManagerHelper
                        .setAudioDeviceGain(any(), any(), anyInt(), anyBoolean()));
        doReturn(true)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));

        when(mMockOccupantZoneService.getUserForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_DRIVER_OCCUPANT_ZONE_ID))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        when(mMockOccupantZoneService.getOccupantZoneForUser(UserHandle.of(TEST_DRIVER_USER_ID)))
                .thenReturn(TEST_DRIVER_OCCUPANT);

        when(mMockOccupantZoneService.getUserForOccupant(TEST_PASSENGER_OCCUPANT_ZONE_ID))
                .thenReturn(TEST_PASSENGER_USER_ID);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_PASSENGER_OCCUPANT_ZONE_ID))
                .thenReturn(SECONDARY_ZONE_ID);
        when(mMockOccupantZoneService.getOccupantZoneForUser(
                UserHandle.of(TEST_PASSENGER_USER_ID))).thenReturn(TEST_PASSENGER_OCCUPANT);

        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mMockOccupantZoneService);
        CarLocalServices.removeServiceForTest(CarInputService.class);
        CarLocalServices.addService(CarInputService.class, mMockCarInputService);

        CarLocalServices.removeServiceForTest(CarOemProxyService.class);
        CarLocalServices.addService(CarOemProxyService.class, mMockCarOemProxyService);

        setupAudioManager();

        setupResources();

        mCarAudioService =
                new CarAudioService(mMockContext,
                        mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                        mCarVolumeCallbackHandler);
    }

    private void setupAudioManager() throws Exception {
        AudioDeviceInfo[] outputDevices = generateOutputDeviceInfos();
        AudioDeviceInfo[] inputDevices = generateInputDeviceInfos();
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .thenReturn(outputDevices);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
               .thenReturn(inputDevices);
        when(mMockContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudioManager);

        when(mAudioManager.registerAudioPolicy(any())).thenAnswer(invocation -> {
            AudioPolicy policy = (AudioPolicy) invocation.getArguments()[0];
            policy.setRegistration(REGISTRATION_ID);
            return SUCCESS;
        });

        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(any())).thenReturn(mMockAudioService);
        doReturn(mockBinder).when(() -> ServiceManager.getService(Context.AUDIO_SERVICE));
    }

    private void setupResources() {
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(audioUseDynamicRouting)).thenReturn(mUseDynamicRouting);
        when(mMockResources.getInteger(audioVolumeKeyEventTimeoutMs))
                .thenReturn(VOLUME_KEY_EVENT_TIMEOUT_MS);
        when(mMockResources.getBoolean(audioUseHalDuckingSignals)).thenReturn(mUseHalAudioDucking);
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting))
                .thenReturn(mUseCarVolumeGroupMuting);
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_TWO);
        when(mMockResources.getBoolean(audioPersistMasterMuteState)).thenReturn(mPersistMasterMute);
    }

    @Test
    public void constructor_withNullContext_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new CarAudioService(null));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withNullContextAndNullPath_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> new CarAudioService(/* context= */null,
                                /* audioConfigurationPath= */ null,
                                /* carVolumeCallbackHandler= */ null));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat().contains("Context");
    }

    @Test
    public void constructor_withInvalidVolumeConfiguration_fails() {
        when(mMockResources.getInteger(audioVolumeAdjustmentContextsVersion))
                .thenReturn(AUDIO_CONTEXT_PRIORITY_LIST_VERSION_ONE);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CarAudioService(mMockContext));

        expectWithMessage("Car Audio Service Construction")
                .that(thrown).hasMessageThat()
                .contains("requires audioVolumeAdjustmentContextsVersion 2");
    }

    @Test
    public void getAudioZoneIds_withBaseConfiguration_returnAllTheZones() {
        mCarAudioService.init();

        expectWithMessage("Car Audio Service Zones")
                .that(mCarAudioService.getAudioZoneIds())
                .asList().containsExactly(PRIMARY_AUDIO_ZONE, SECONDARY_ZONE_ID);
    }

    @Test
    public void getVolumeGroupCount_onPrimaryZone_returnsAllGroups() {
        mCarAudioService.init();

        expectWithMessage("Primary zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getVolumeGroupCount_onPrimaryZone__withNonDynamicRouting_returnsAllGroups() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone car volume group count")
                .that(nonDynamicAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(CarAudioDynamicRouting.STREAM_TYPES.length);
    }

    @Test
    public void getVolumeGroupIdForUsage_forMusicUsage() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's media car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA))
                .isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_withNonDynamicRouting_forMusicUsage() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's media car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_MEDIA)).isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forNavigationUsage() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's navigation car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
                .isEqualTo(NAVIGATION_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_withNonDynamicRouting_forNavigationUsage() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's navigation car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forInvalidUsage_returnsInvalidGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's invalid car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, INVALID_USAGE))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void
            getVolumeGroupIdForUsage_forInvalidUsage_withNonDynamicRouting_returnsInvalidGroupId() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Non dynamic routing primary zone's invalid car volume group id")
                .that(nonDynamicAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        INVALID_USAGE)).isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forUnknownUsage_returnsMediaGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's unknown car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_UNKNOWN))
                .isEqualTo(MEDIA_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_forVirtualUsage_returnsInvalidGroupId() {
        mCarAudioService.init();

        expectWithMessage("Primary zone's virtual car volume group id")
                .that(mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE,
                        AudioManagerHelper.getUsageVirtualSource()))
                .isEqualTo(INVALID_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupCount_onSecondaryZone_returnsAllGroups() {
        mCarAudioService.init();

        expectWithMessage("Secondary Zone car volume group count")
                .that(mCarAudioService.getVolumeGroupCount(SECONDARY_ZONE_ID))
                .isEqualTo(SECONDARY_ZONE_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getUsagesForVolumeGroupId_forMusicContext() {
        mCarAudioService.init();


        expectWithMessage("Primary zone's music car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                        MEDIA_VOLUME_GROUP_ID)).asList()
                .containsExactly(USAGE_UNKNOWN, USAGE_GAME, USAGE_MEDIA, USAGE_ANNOUNCEMENT,
                        USAGE_NOTIFICATION, USAGE_NOTIFICATION_EVENT);
    }

    @Test
    public void getUsagesForVolumeGroupId_forSystemContext() {
        mCarAudioService.init();
        int systemVolumeGroup =
                mCarAudioService.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_EMERGENCY);

        expectWithMessage("Primary zone's system car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                        systemVolumeGroup)).asList().containsExactly(USAGE_ALARM, USAGE_EMERGENCY,
                        USAGE_SAFETY, USAGE_VEHICLE_STATUS, USAGE_ASSISTANCE_SONIFICATION);
    }

    @Test
    public void getUsagesForVolumeGroupId_onSecondaryZone_forSingleVolumeGroupId_returnAllUsages() {
        mCarAudioService.init();

        expectWithMessage("Secondary Zone's car volume group id usages")
                .that(mCarAudioService.getUsagesForVolumeGroupId(SECONDARY_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .asList().containsExactly(USAGE_UNKNOWN, USAGE_MEDIA,
                        USAGE_VOICE_COMMUNICATION, USAGE_VOICE_COMMUNICATION_SIGNALLING,
                        USAGE_ALARM, USAGE_NOTIFICATION, USAGE_NOTIFICATION_RINGTONE,
                        USAGE_NOTIFICATION_EVENT, USAGE_ASSISTANCE_ACCESSIBILITY,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, USAGE_ASSISTANCE_SONIFICATION,
                        USAGE_GAME, USAGE_ASSISTANT, USAGE_CALL_ASSISTANT, USAGE_EMERGENCY,
                        USAGE_ANNOUNCEMENT, USAGE_SAFETY, USAGE_VEHICLE_STATUS);
    }

    @Test
    public void getUsagesForVolumeGroupId_withoutDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Media car volume group id without dynamic routing").that(
                nonDynamicAudioService.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE,
                MEDIA_VOLUME_GROUP_ID)).asList()
                .containsExactly(CarAudioDynamicRouting.STREAM_TYPE_USAGES[MEDIA_VOLUME_GROUP_ID]);
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_failsForConfigurationMissing() {
        mCarAudioService.init();

        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService
                        .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                                USAGE_MEDIA, DEFAULT_GAIN));

        expectWithMessage("FM and Media Audio Patch Exception")
                .that(thrown).hasMessageThat().contains("Audio Patch APIs not enabled");
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_failsForMissingPermission() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService
                        .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                                USAGE_MEDIA, DEFAULT_GAIN));

        expectWithMessage("FM and Media Audio Patch Permission Exception")
                .that(thrown).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void createAudioPatch_onMediaOutputDevice_succeeds() {
        mCarAudioService.init();

        mockGrantCarControlAudioSettingsPermission();
        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, true));
        doReturn(new AudioPatchInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE, 0))
                .when(() -> AudioManagerHelper
                        .createAudioPatch(mFmTunerInputDevice, mMediaOutputDevice, DEFAULT_GAIN));

        CarAudioPatchHandle audioPatch = mCarAudioService
                .createAudioPatch(PRIMARY_ZONE_FM_TUNER_ADDRESS, USAGE_MEDIA, DEFAULT_GAIN);

        expectWithMessage("Audio Patch Sink Address")
                .that(audioPatch.getSinkAddress()).isEqualTo(MEDIA_TEST_DEVICE);
        expectWithMessage("Audio Patch Source Address")
                .that(audioPatch.getSourceAddress()).isEqualTo(PRIMARY_ZONE_FM_TUNER_ADDRESS);
        expectWithMessage("Audio Patch Handle")
                .that(audioPatch.getHandleId()).isEqualTo(0);
    }

    @Test
    public void releaseAudioPatch_failsForConfigurationMissing() {
        mCarAudioService.init();

        doReturn(false)
                .when(() -> SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, false));
        CarAudioPatchHandle carAudioPatchHandle =
                new CarAudioPatchHandle(0, PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.releaseAudioPatch(carAudioPatchHandle));

        expectWithMessage("Release FM and Media Audio Patch Exception")
                .that(thrown).hasMessageThat().contains("Audio Patch APIs not enabled");
    }

    @Test
    public void releaseAudioPatch_failsForMissingPermission() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();
        CarAudioPatchHandle carAudioPatchHandle =
                new CarAudioPatchHandle(0, PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE);

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.releaseAudioPatch(carAudioPatchHandle));

        expectWithMessage("FM and Media Audio Patch Permission Exception")
                .that(thrown).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void releaseAudioPatch_forNullSourceAddress_throwsNullPointerException() {
        mCarAudioService.init();
        mockGrantCarControlAudioSettingsPermission();
        doReturn(new AudioPatchInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS, MEDIA_TEST_DEVICE, 0))
                .when(() -> AudioManagerHelper
                        .createAudioPatch(mFmTunerInputDevice, mMediaOutputDevice, DEFAULT_GAIN));

        CarAudioPatchHandle audioPatch = mock(CarAudioPatchHandle.class);
        when(audioPatch.getSourceAddress()).thenReturn(null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mCarAudioService.releaseAudioPatch(audioPatch));

        expectWithMessage("Release audio patch for null source address "
                + "and sink address Null Exception")
                .that(thrown).hasMessageThat()
                .contains("Source Address can not be null for patch id 0");
    }

    @Test
    public void releaseAudioPatch_failsForNullPatch() {
        mCarAudioService.init();

        assertThrows(NullPointerException.class,
                () -> mCarAudioService.releaseAudioPatch(null));
    }

    @Test
    public void setZoneIdForUid_withoutRoutingPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.setZoneIdForUid(OUT_OF_RANGE_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void setZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void setZoneIdForUid_withInvalidZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(INVALID_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Invalid Zone Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + INVALID_AUDIO_ZONE);
    }

    @Test
    public void setZoneIdForUid_withOutOfRangeZone_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.setZoneIdForUid(OUT_OF_RANGE_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID Zone Out of Range Exception")
                .that(thrown).hasMessageThat()
                .contains("Invalid audio zone Id " + OUT_OF_RANGE_ZONE);
    }

    @Test
    public void setZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID));

        expectWithMessage("Set Zone for UID With Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void setZoneIdForUid_withValidZone_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID Status").that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID For Different Zone")
                .that(results).isTrue();
    }

    @Test
    public void setZoneIdForUid_onDifferentZones_withAudioFocus_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        AudioFocusInfo audioFocusInfo = createAudioFocusInfoForMedia();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .requestAudioFocusForTest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        boolean results = noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        expectWithMessage("Set Zone for UID For Different Zone with Audio Focus")
                .that(results).isTrue();
    }

    @Test
    public void getZoneIdForUid_withoutMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for Non Mapped UID")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(SECONDARY_ZONE_ID);
    }

    @Test
    public void getZoneIdForUid_afterSwitchingZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Zone Id")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void clearZoneIdForUid_withoutRoutingPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioSettingsPermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void clearZoneIdForUid_withoutDynamicRouting_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Dynamic Configuration Exception")
                .that(thrown).hasMessageThat()
                .contains("Dynamic routing is required");
    }

    @Test
    public void clearZoneIdForUid_withZoneAudioMapping_fails() {
        mCarAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarAudioService.clearZoneIdForUid(MEDIA_APP_UID));

        expectWithMessage("Clear Zone for UID Audio Zone Mapping Exception")
                .that(thrown).hasMessageThat()
                .contains("UID based routing is not supported while using occupant zone mapping");
    }

    @Test
    public void clearZoneIdForUid_forNonMappedUid_succeeds() throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        boolean status = noZoneMappingAudioService
                .clearZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Clear Zone for UID Audio Zone without Mapping")
                .that(status).isTrue();
    }

    @Test
    public void clearZoneIdForUid_forMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        boolean status = noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Clear Zone for UID Audio Zone with Mapping")
                .that(status).isTrue();
    }

    @Test
    public void getZoneIdForUid_afterClearedUidMapping_returnsDefaultZone() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        noZoneMappingAudioService.clearZoneIdForUid(MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService.getZoneIdForUid(MEDIA_APP_UID);

        expectWithMessage("Get Zone for UID Audio Zone with Cleared Mapping")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_withoutMappedUid_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Mapped audio focus info's zone")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();

        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Mapped audio focus info's zone")
                .that(zoneId).isEqualTo(SECONDARY_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterSwitchingZones_succeeds() throws Exception {
        when(mMockAudioService.setUidDeviceAffinity(any(), anyInt(), any(), any()))
                .thenReturn(SUCCESS);
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        noZoneMappingAudioService
                .setZoneIdForUid(SECONDARY_ZONE_ID, MEDIA_APP_UID);
        noZoneMappingAudioService
                .setZoneIdForUid(PRIMARY_AUDIO_ZONE, MEDIA_APP_UID);

        int zoneId = noZoneMappingAudioService
                .getZoneIdForAudioFocusInfo(TEST_AUDIO_FOCUS_INFO);

        expectWithMessage("Remapped audio focus info's zone")
                .that(zoneId).isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void setGroupVolume_withoutPermission_fails() {
        mCarAudioService.init();

        mockDenyCarControlAudioVolumePermission();

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                        TEST_GAIN_INDEX, TEST_FLAGS));

        expectWithMessage("Set Volume Group Permission Exception")
                .that(thrown).hasMessageThat()
                .contains(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void setGroupVolume_withDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.setGroupVolume(
                PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP, TEST_GAIN_INDEX, TEST_FLAGS);

        verify(mAudioManager).setStreamVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_GROUP],
                TEST_GAIN_INDEX,
                TEST_FLAGS);
    }

    @Test
    public void setGroupVolume_verifyNoCallbacks() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* mute= */ false, TEST_FLAGS);
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP, TEST_GAIN_INDEX,
                TEST_FLAGS);

        verify(mCarVolumeCallbackHandler, never()).onGroupMuteChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void setGroupVolume_afterSetVolumeGroupMute() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* mute= */ true, TEST_FLAGS);
        reset(mCarVolumeCallbackHandler);

        mCarAudioService.setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP, TEST_GAIN_INDEX,
                TEST_FLAGS);

        verify(mCarVolumeCallbackHandler).onGroupMuteChange(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                TEST_FLAGS);
    }

    @Test
    public void getOutputDeviceAddressForUsage_forMusicUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress =
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA);

        expectWithMessage("Media usage audio device address")
                .that(mediaDeviceAddress).isEqualTo(MEDIA_TEST_DEVICE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_withNonDynamicRouting_forMediaUsage_fails() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> nonDynamicAudioService
                        .getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA));

        expectWithMessage("Non dynamic routing media usage audio device address exception")
                .that(thrown).hasMessageThat().contains("Dynamic routing is required");
    }

    @Test
    public void getOutputDeviceAddressForUsage_forNavigationUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress =
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);

        expectWithMessage("Navigation usage audio device address")
                .that(mediaDeviceAddress).isEqualTo(NAVIGATION_TEST_DEVICE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_forInvalidUsage_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        INVALID_USAGE));

        expectWithMessage("Invalid usage audio device address exception")
                .that(thrown).hasMessageThat().contains("Invalid audio attribute " + INVALID_USAGE);
    }

    @Test
    public void getOutputDeviceAddressForUsage_forVirtualUsage_fails() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioService.getOutputDeviceAddressForUsage(PRIMARY_AUDIO_ZONE,
                        AudioManagerHelper.getUsageVirtualSource()));

        expectWithMessage("Invalid context audio device address exception")
                .that(thrown).hasMessageThat()
                .contains("invalid");
    }

    @Test
    public void getOutputDeviceAddressForUsage_onSecondaryZone_forMusicUsage() {
        mCarAudioService.init();

        String mediaDeviceAddress =
                mCarAudioService.getOutputDeviceAddressForUsage(SECONDARY_ZONE_ID, USAGE_MEDIA);

        expectWithMessage("Media usage audio device address for secondary zone")
                .that(mediaDeviceAddress).isEqualTo(SECONDARY_TEST_DEVICE);
    }

    @Test
    public void getSuggestedAudioContextForZone_inPrimaryZone() {
        mCarAudioService.init();
        int defaultAudioContext = mCarAudioService.getCarAudioContext()
                .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE);

        expectWithMessage("Suggested audio context for primary zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(defaultAudioContext);
    }

    @Test
    public void getSuggestedAudioContextForZone_inSecondaryZone() {
        mCarAudioService.init();
        int defaultAudioContext = mCarAudioService.getCarAudioContext()
                .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE);

        expectWithMessage("Suggested audio context for secondary zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(SECONDARY_ZONE_ID))
                .isEqualTo(defaultAudioContext);
    }

    @Test
    public void getSuggestedAudioContextForZone_inInvalidZone() {
        mCarAudioService.init();

        expectWithMessage("Suggested audio context for invalid zone")
                .that(mCarAudioService.getSuggestedAudioContextForZone(INVALID_AUDIO_ZONE))
                .isEqualTo(CarAudioContext.getInvalidContext());
    }

    @Test
    public void isVolumeGroupMuted_noSetVolumeGroupMute() {
        mCarAudioService.init();

        expectWithMessage("Volume group mute for default state")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isFalse();
    }

    @Test
    public void isVolumeGroupMuted_setVolumeGroupMuted_isFalse() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* mute= */ true, TEST_FLAGS);

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* mute= */ false, TEST_FLAGS);

        expectWithMessage("Volume group muted after mute and unmute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isFalse();
    }

    @Test
    public void isVolumeGroupMuted_setVolumeGroupMuted_isTrue() {
        mCarAudioService.init();

        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* mute= */ true, TEST_FLAGS);
        expectWithMessage("Volume group muted after mute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isTrue();
    }

    @Test
    public void isVolumeGroupMuted_withVolumeGroupMutingDisabled() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting))
                .thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Volume group for disabled volume group muting")
                .that(nonVolumeGroupMutingAudioService.isVolumeGroupMuted(
                        PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isFalse();
    }

    @Test
    public void getGroupMaxVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Group max volume for primary audio zone and group")
                .that(mCarAudioService.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo((MAX_GAIN - MIN_GAIN) / STEP_SIZE);
    }

    @Test
    public void getGroupMinVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Group Min Volume for primary audio zone and group")
                .that(mCarAudioService.getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(0);
    }

    @Test
    public void getGroupCurrentVolume_forPrimaryZone() {
        mCarAudioService.init();

        expectWithMessage("Current group volume for primary audio zone and group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo((DEFAULT_GAIN - MIN_GAIN) / STEP_SIZE);
    }

    @Test
    public void getGroupMaxVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);

        verify(mAudioManager).getStreamMaxVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_GROUP]);
    }

    @Test
    public void getGroupMinVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);

        verify(mAudioManager).getStreamMinVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_GROUP]);
    }

    @Test
    public void getGroupCurrentVolume_withNoDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        nonDynamicAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);

        verify(mAudioManager).getStreamVolume(
                CarAudioDynamicRouting.STREAM_TYPES[TEST_PRIMARY_GROUP]);
    }

    @Test
    public void setBalanceTowardRight_nonNullValue() {
        mCarAudioService.init();

        mCarAudioService.setBalanceTowardRight(TEST_VALUE);

        verify(mAudioControlWrapperAidl).setBalanceTowardRight(TEST_VALUE);
    }

    @Test
    public void setBalanceTowardRight_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.setBalanceTowardRight(INVALID_TEST_VALUE));

        expectWithMessage("Out of bounds balance")
                .that(thrown).hasMessageThat()
                .contains(String.format("Balance is out of range of [%f, %f]", -1f, 1f));
    }

    @Test
    public void setFadeTowardFront_nonNullValue() {
        mCarAudioService.init();

        mCarAudioService.setFadeTowardFront(TEST_VALUE);

        verify(mAudioControlWrapperAidl).setFadeTowardFront(TEST_VALUE);
    }

    @Test
    public void setFadeTowardFront_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.setFadeTowardFront(INVALID_TEST_VALUE));

        expectWithMessage("Out of bounds fade")
                .that(thrown).hasMessageThat()
                .contains(String.format("Fade is out of range of [%f, %f]", -1f, 1f));
    }

    @Test
    public void isAudioFeatureEnabled_forDynamicRouting() {
        mCarAudioService.init();

        expectWithMessage("Dynamic routing audio feature")
                .that(mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING))
                .isEqualTo(mUseDynamicRouting);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledDynamicRouting() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        expectWithMessage("Disabled dynamic routing audio feature")
                .that(nonDynamicAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING))
                .isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_forVolumeGroupMuting() {
        mCarAudioService.init();

        expectWithMessage("Group muting audio feature")
                .that(mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING))
                .isEqualTo(mUseCarVolumeGroupMuting);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledVolumeGroupMuting() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Disabled group muting audio feature")
                .that(nonVolumeGroupMutingAudioService
                        .isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING))
                .isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_forUnrecognizableAudioFeature_throws() {
        mCarAudioService.init();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioService.isAudioFeatureEnabled(INVALID_AUDIO_FEATURE));

        expectWithMessage("Unknown audio feature")
                .that(thrown).hasMessageThat()
                .contains("Unknown Audio Feature type: " + INVALID_AUDIO_FEATURE);
    }

    @Test
    public void isAudioFeatureEnabled_forDisabledOemService() {
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with disabled oem service")
                .that(isEnabled).isFalse();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledFocusService() {
        CarOemAudioFocusProxyService service = mock(CarOemAudioFocusProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioFocusService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled focus service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledVolumeService() {
        CarOemAudioVolumeProxyService service = mock(CarOemAudioVolumeProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioVolumeService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled volume service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void isAudioFeatureEnabled_withEnabledDuckingService() {
        CarOemAudioDuckingProxyService service = mock(CarOemAudioDuckingProxyService.class);
        when(mMockCarOemProxyService.isOemServiceEnabled()).thenReturn(true);
        when(mMockCarOemProxyService.getCarOemAudioDuckingService()).thenReturn(service);
        mCarAudioService.init();

        boolean isEnabled =
                mCarAudioService.isAudioFeatureEnabled(AUDIO_FEATURE_OEM_AUDIO_SERVICE);

        expectWithMessage("Oem service enabled with enabled ducking service")
                .that(isEnabled).isTrue();
    }

    @Test
    public void onOccupantZoneConfigChanged_noUserAssignedToPrimaryZone() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(UserManagerHelper.USER_NULL);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(UserManagerHelper.USER_NULL);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        int prevUserId = mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE);

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID before config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(prevUserId);
    }

    @Test
    public void onOccupantZoneConfigChanged_userAssignedToPrimaryZone() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_PASSENGER_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID after config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TEST_PASSENGER_USER_ID);
    }

    @Test
    public void onOccupantZoneConfigChanged_afterResettingUser_returnNoUser() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_PASSENGER_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(UserManagerHelper.USER_NULL);

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID config changed to null")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(UserManagerHelper.USER_NULL);
    }

    @Test
    public void onOccupantZoneConfigChanged_noOccupantZoneMapping() throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        noZoneMappingAudioService.init();
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mMockOccupantZoneService, never()).getUserForOccupant(anyInt());
    }

    @Test
    public void onOccupantZoneConfigChanged_noOccupantZoneMapping_alreadyAssigned()
            throws Exception {
        CarAudioService noZoneMappingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationWithoutZoneMappingFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        noZoneMappingAudioService.init();
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        verify(mMockOccupantZoneService, never()).getUserForOccupant(anyInt());
        expectWithMessage("Occupant Zone for primary zone")
                .that(noZoneMappingAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TEST_DRIVER_USER_ID);
    }

    @Test
    public void onOccupantZoneConfigChanged_multipleZones() throws Exception {
        mCarAudioService.init();
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_PASSENGER_USER_ID, TEST_USER_ID_SECONDARY);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();

        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("User ID for primary and secondary zone after config changed")
                .that(mCarAudioService.getUserIdForZone(PRIMARY_AUDIO_ZONE))
                .isNotEqualTo(mCarAudioService.getUserIdForZone(SECONDARY_ZONE_ID));
        expectWithMessage("Secondary user ID config changed")
                .that(mCarAudioService.getUserIdForZone(SECONDARY_ZONE_ID))
                .isEqualTo(TEST_USER_ID_SECONDARY);
    }

    @Test
    public void serviceDied_registersAudioGainCallback() {
        mCarAudioService.init();
        ArgumentCaptor<AudioControlDeathRecipient> captor =
                ArgumentCaptor.forClass(AudioControlDeathRecipient.class);
        verify(mAudioControlWrapperAidl).linkToDeath(captor.capture());
        AudioControlDeathRecipient runnable = captor.getValue();
        reset(mAudioControlWrapperAidl);

        runnable.serviceDied();

        verify(mAudioControlWrapperAidl).registerAudioGainCallback(any());
    }

    @Test
    public void serviceDied_registersFocusListener() {
        mCarAudioService.init();
        ArgumentCaptor<AudioControlDeathRecipient> captor =
                ArgumentCaptor.forClass(AudioControlDeathRecipient.class);
        verify(mAudioControlWrapperAidl).linkToDeath(captor.capture());
        AudioControlDeathRecipient runnable = captor.getValue();
        reset(mAudioControlWrapperAidl);

        runnable.serviceDied();

        verify(mAudioControlWrapperAidl).registerFocusListener(any());
    }

    private ICarOccupantZoneCallback getOccupantZoneCallback() {
        ArgumentCaptor<ICarOccupantZoneCallback> captor =
                ArgumentCaptor.forClass(ICarOccupantZoneCallback.class);
        verify(mMockOccupantZoneService).registerCallback(captor.capture());
        return captor.getValue();
    }

    @Test
    public void getVolumeGroupIdForAudioContext_forPrimaryGroup() {
        mCarAudioService.init();

        expectWithMessage("Volume group ID for primary audio zone")
                .that(mCarAudioService.getVolumeGroupIdForAudioContext(PRIMARY_AUDIO_ZONE,
                        CarAudioContext.MUSIC))
                .isEqualTo(TEST_PRIMARY_GROUP_INDEX);
    }

    @Test
    public void getInputDevicesForZoneId_primaryZone() {
        mCarAudioService.init();

        expectWithMessage("Get input device for primary zone id")
                .that(mCarAudioService.getInputDevicesForZoneId(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioDeviceAttributes(mMicrophoneInputDevice));
    }

    @Test
    public void getExternalSources_forSingleDevice() {
        mCarAudioService.init();
        AudioDeviceInfo[] inputDevices = generateInputDeviceInfos();

        expectWithMessage("External input device addresses")
                .that(mCarAudioService.getExternalSources())
                .asList().containsExactly(inputDevices[1].getAddress());
    }

    @Test
    public void setAudioEnabled_forEnabledVolumeGroupMuting() {
        mCarAudioService.init();

        mCarAudioService.setAudioEnabled(/* enabled= */ true);

        verify(mAudioControlWrapperAidl).onDevicesToMuteChange(any());
    }

    @Test
    public void setAudioEnabled_forDisabledVolumeGroupMuting() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        nonVolumeGroupMutingAudioService.setAudioEnabled(/* enabled= */ true);

        verify(mAudioControlWrapperAidl, never()).onDevicesToMuteChange(any());
    }

    @Test
    public void registerVolumeCallback_verifyCallbackHandler() {
        mCarAudioService.init();

        mCarAudioService.registerVolumeCallback(mVolumeCallbackBinder);

        verify(mCarVolumeCallbackHandler).registerCallback(mVolumeCallbackBinder);
    }

    @Test
    public void unregisterVolumeCallback_verifyCallbackHandler() {
        mCarAudioService.init();

        mCarAudioService.unregisterVolumeCallback(mVolumeCallbackBinder);

        verify(mCarVolumeCallbackHandler).unregisterCallback(mVolumeCallbackBinder);
    }

    @Test
    public void getMutedVolumeGroups_forInvalidZone() {
        mCarAudioService.init();

        expectWithMessage("Muted volume groups for invalid zone")
                .that(mCarAudioService.getMutedVolumeGroups(INVALID_AUDIO_ZONE))
                .isEmpty();
    }

    @Test
    public void getMutedVolumeGroups_whenVolumeGroupMuteNotSupported() {
        when(mMockResources.getBoolean(audioUseCarVolumeGroupMuting)).thenReturn(false);
        CarAudioService nonVolumeGroupMutingAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonVolumeGroupMutingAudioService.init();

        expectWithMessage("Muted volume groups with disable mute feature")
                .that(nonVolumeGroupMutingAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .isEmpty();
    }

    @Test
    public void getMutedVolumeGroups_withMutedGroups() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_SECONDARY_GROUP,
                /* muted= */ true, TEST_FLAGS);

        expectWithMessage("Muted volume groups")
                .that(mCarAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .containsExactly(TEST_PRIMARY_VOLUME_INFO, TEST_SECONDARY_VOLUME_INFO);
    }

    @Test
    public void getMutedVolumeGroups_afterUnmuting() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_SECONDARY_GROUP,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* muted= */ false, TEST_FLAGS);

        expectWithMessage("Muted volume groups after unmuting one group")
                .that(mCarAudioService.getMutedVolumeGroups(PRIMARY_AUDIO_ZONE))
                .containsExactly(TEST_SECONDARY_VOLUME_INFO);
    }

    @Test
    public void getMutedVolumeGroups_withMutedGroupsForDifferentZone() {
        mCarAudioService.init();
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP,
                /* muted= */ true, TEST_FLAGS);
        mCarAudioService.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, TEST_SECONDARY_GROUP,
                /* muted= */ true, TEST_FLAGS);

        expectWithMessage("Muted volume groups for secondary zone")
                .that(mCarAudioService.getMutedVolumeGroups(SECONDARY_ZONE_ID)).isEmpty();
    }

    @Test
    public void onReceive_forLegacy_noCallToOnVolumeGroupChanged() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        mVolumeReceiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(mVolumeReceiverCaptor.capture(), any(), anyInt());
        BroadcastReceiver receiver = mVolumeReceiverCaptor.getValue();
        Intent intent = new Intent(VOLUME_CHANGED_ACTION);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler, never())
                .onVolumeGroupChange(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onReceive_forLegacy_forStreamMusic() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        verify(mMockContext).registerReceiver(mVolumeReceiverCaptor.capture(), any(), anyInt());
        BroadcastReceiver receiver = mVolumeReceiverCaptor.getValue();
        Intent intent = new Intent(VOLUME_CHANGED_ACTION)
                .putExtra(EXTRA_VOLUME_STREAM_TYPE, STREAM_MUSIC);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler).onVolumeGroupChange(
                eq(PRIMARY_AUDIO_ZONE), anyInt(), eq(FLAG_FROM_KEY | FLAG_SHOW_UI));
    }

    @Test
    public void onReceive_forLegacy_onMuteChanged() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();
        ArgumentCaptor<BroadcastReceiver> captor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(captor.capture(), any(), anyInt());
        BroadcastReceiver receiver = captor.getValue();
        Intent intent = new Intent();
        intent.setAction(MASTER_MUTE_CHANGED_ACTION);

        receiver.onReceive(mMockContext, intent);

        verify(mCarVolumeCallbackHandler)
                .onMasterMuteChanged(eq(PRIMARY_AUDIO_ZONE), eq(FLAG_FROM_KEY | FLAG_SHOW_UI));
    }

    @Test
    public void getVolumeGroupInfosForZone() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        List<CarVolumeGroupInfo> infos =
                mCarAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        for (int index = 0; index < groupCount; index++) {
            CarVolumeGroupInfo info = mCarAudioService
                    .getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, index);
            expectWithMessage("Car volume group infos for primary zone and info %s", info)
                    .that(infos).contains(info);
        }
    }

    @Test
    public void getVolumeGroupInfosForZone_forDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        List<CarVolumeGroupInfo> infos =
                nonDynamicAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        expectWithMessage("Car volume group infos with dynamic routing disabled")
                .that(infos).isEmpty();
    }

    @Test
    public void getVolumeGroupInfosForZone_size() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        List<CarVolumeGroupInfo> infos =
                mCarAudioService.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE);

        expectWithMessage("Car volume group infos size for primary zone")
                .that(infos).hasSize(groupCount);
    }

    @Test
    public void getVolumeGroupInfosForZone_forInvalidZone() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfosForZone(INVALID_AUDIO_ZONE));

        expectWithMessage("Exception for volume group infos size for invalid zone")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo() {
        CarVolumeGroupInfo testVolumeGroupInfo =
                new CarVolumeGroupInfo.Builder(TEST_PRIMARY_VOLUME_INFO).setMuted(false).build();
        mCarAudioService.init();

        expectWithMessage("Car volume group info for primary zone")
                .that(mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(testVolumeGroupInfo);
    }

    @Test
    public void getVolumeGroupInfo_forInvalidZone() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                TEST_PRIMARY_GROUP));

        expectWithMessage("Exception for volume group info size for invalid zone")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo_forInvalidGroup() {
        mCarAudioService.init();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                TEST_PRIMARY_GROUP));

        expectWithMessage("Exception for volume groups info size for invalid group id")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void getVolumeGroupInfo_forGroupOverRange() {
        mCarAudioService.init();
        int groupCount = mCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () ->
                        mCarAudioService.getVolumeGroupInfo(INVALID_AUDIO_ZONE,
                                groupCount));

        expectWithMessage("Exception for volume groups info size for out of range group")
                .that(thrown).hasMessageThat().contains("audio zone Id");
    }

    @Test
    public void registerPrimaryZoneMediaAudioRequestCallbackListener_withNullCallback_fails() {
        mCarAudioService.init();

        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(
                        /* callback= */ null));

        expectWithMessage("Register audio media request callback exception")
                .that(thrown).hasMessageThat()
                .contains("Media request callback");
    }

    @Test
    public void unregisterPrimaryZoneMediaAudioRequestCallback_withNullCallback_fails() {
        mCarAudioService.init();

        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.unregisterPrimaryZoneMediaAudioRequestCallback(
                        /* callback= */ null));

        expectWithMessage("Unregister audio media request callback exception")
                .that(thrown).hasMessageThat()
                .contains("Media request callback");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withPassengerOccupant_succeeds()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        expectWithMessage("Audio media request id")
                .that(mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                        TEST_PASSENGER_OCCUPANT))
                .isNotEqualTo(INVALID_REQUEST_ID);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withDriverOccupant_fails()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, ()
                -> mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_DRIVER_OCCUPANT));

        expectWithMessage("Request media audio exception")
                .that(thrown).hasMessageThat().contains("already owns the primary audio zone");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withNonAssignedOccupant_fails()
            throws Exception {
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(TEST_UNASSIGNED_OCCUPANT_ZONE_ID))
                .thenReturn(/* audioZoneId= */ 4);
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        CarOccupantZoneManager.OccupantZoneInfo info =
                getOccupantInfo(TEST_UNASSIGNED_OCCUPANT_ZONE_ID,
                CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER,
                VehicleAreaSeat.SEAT_ROW_1_LEFT);
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        expectWithMessage("Invalid audio media request id")
                .that(mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback, info))
                .isEqualTo(INVALID_REQUEST_ID);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withPassengerOccupant_callsApprover()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);

        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);

        requestToken.waitForCallback();
        expectWithMessage("Called audio media request id")
                .that(requestToken.mRequestId).isEqualTo(requestId);
        expectWithMessage("Called audio media request info")
                .that(requestToken.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        boolean results = mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);

        expectWithMessage("Allowed audio playback").that(results).isTrue();
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        boolean results = mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);

        expectWithMessage("Unallowed audio playback").that(results).isTrue();
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest_callsRequester() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);

        requestCallback.waitForCallback();
        expectWithMessage("Media request called audio media request id")
                .that(requestCallback.mRequestId).isEqualTo(requestId);
        expectWithMessage("Media request called audio media request info")
                .that(requestCallback.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
        expectWithMessage("Media request called audio media request status")
                .that(requestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withAllowedRequest_callsApprover() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestApprover = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestApprover);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestApprover.waitForCallback();
        requestApprover.reset();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestApprover, requestId,
                /* allowed= */ true);

        requestApprover.waitForCallback();
        expectWithMessage("Media approver called audio media request id")
                .that(requestApprover.mRequestId).isEqualTo(requestId);
        expectWithMessage("Media approver called audio media request info")
                .that(requestApprover.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
        expectWithMessage("Media approver called audio media request status")
                .that(requestApprover.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest_callsRequester()
            throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);

        requestCallback.waitForCallback();
        expectWithMessage("Unallowed media request called audio media request id")
                .that(requestCallback.mRequestId).isEqualTo(requestId);
        expectWithMessage("Unallowed media request called audio media request info")
                .that(requestCallback.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
        expectWithMessage("Unallowed media request called audio media request status")
                .that(requestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_withUnallowedRequest_callsApprover() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestApprover = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestApprover);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestApprover.waitForCallback();
        requestApprover.reset();

        mCarAudioService.allowMediaAudioOnPrimaryZone(requestApprover, requestId,
                /* allowed= */ false);

        requestApprover.waitForCallback();
        expectWithMessage("Unallowed media approver called audio media request id")
                .that(requestApprover.mRequestId).isEqualTo(requestId);
        expectWithMessage("Unallowed approver token called audio media request info")
                .that(requestApprover.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
        expectWithMessage("Unallowed approver token called audio media request status")
                .that(requestApprover.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_witNullOccupant_fails() throws Exception {
        mCarAudioService.init();
        NullPointerException thrown = assertThrows(NullPointerException.class, ()
                -> mCarAudioService.isMediaAudioAllowedInPrimaryZone(/* occupantZoneInfo= */ null));

        expectWithMessage("Media status exception").that(thrown)
                .hasMessageThat().contains("Occupant zone info");
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_byDefault() throws Exception {
        mCarAudioService.init();

        expectWithMessage("Media default status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(TEST_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();

        expectWithMessage("Media allowed status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(TEST_PASSENGER_OCCUPANT))
                .isTrue();
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone_afterDisallowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ false);
        requestToken.waitForCallback();

        expectWithMessage("Media after disallowed status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(TEST_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void resetMediaAudioOnPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean reset = mCarAudioService.resetMediaAudioOnPrimaryZone(TEST_PASSENGER_OCCUPANT);

        requestToken.waitForCallback();
        expectWithMessage("Reset status").that(reset).isTrue();
        expectWithMessage("Media reset status")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(TEST_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone_beforeAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean cancel = mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);

        requestToken.waitForCallback();
        expectWithMessage("Cancel status").that(cancel).isTrue();
        expectWithMessage("Canceled media token called audio media request id")
                .that(requestToken.mRequestId).isEqualTo(requestId);
        expectWithMessage("Canceled media token called audio media request info")
                .that(requestToken.mInfo).isEqualTo(TEST_PASSENGER_OCCUPANT);
        expectWithMessage("Canceled media token called audio media request status")
                .that(requestToken.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone_afterAllowed() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();

        boolean cancel = mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);

        requestToken.waitForCallback();
        expectWithMessage("Cancel status after allowed").that(cancel).isTrue();
        expectWithMessage("Media allowed status after canceled")
                .that(mCarAudioService.isMediaAudioAllowedInPrimaryZone(TEST_PASSENGER_OCCUPANT))
                .isFalse();
    }

    @Test
    public void getZoneIdForAudioFocusInfo_beforeAllowedSharedAudio() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        expectWithMessage("Not yet shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_PASSENGER_AUDIO_FOCUS_INFO))
                .isEqualTo(SECONDARY_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterAllowedShareAudio() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId, /* allowed= */ true);
        requestToken.waitForCallback();

        expectWithMessage("Shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_PASSENGER_AUDIO_FOCUS_INFO))
                .isEqualTo(PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterCanceled() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.cancelMediaAudioOnPrimaryZone(requestId);
        requestToken.waitForCallback();

        expectWithMessage("Canceled shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_PASSENGER_AUDIO_FOCUS_INFO))
                .isEqualTo(SECONDARY_ZONE_ID);
    }

    @Test
    public void getZoneIdForAudioFocusInfo_afterReset() throws Exception {
        mCarAudioService.init();
        TestPrimaryZoneMediaAudioRequestCallback
                requestToken = new TestPrimaryZoneMediaAudioRequestCallback();
        TestMediaRequestStatusCallback requestCallback = new TestMediaRequestStatusCallback();
        ICarOccupantZoneCallback carOccupantZoneCallback = getOccupantZoneCallback();
        carOccupantZoneCallback
                .onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
        mCarAudioService.registerPrimaryZoneMediaAudioRequestCallback(requestToken);
        long requestId = mCarAudioService.requestMediaAudioOnPrimaryZone(requestCallback,
                TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.allowMediaAudioOnPrimaryZone(requestToken, requestId,
                /* allowed= */ true);
        requestToken.waitForCallback();
        requestToken.reset();
        mCarAudioService.resetMediaAudioOnPrimaryZone(TEST_PASSENGER_OCCUPANT);
        requestToken.waitForCallback();

        expectWithMessage("Reset shared media user zone")
                .that(mCarAudioService.getZoneIdForAudioFocusInfo(TEST_PASSENGER_AUDIO_FOCUS_INFO))
                .isEqualTo(SECONDARY_ZONE_ID);
    }

    private static CarOccupantZoneManager.OccupantZoneInfo getOccupantInfo(int occupantZoneId,
            int occupantType, int seat) {
        return new CarOccupantZoneManager.OccupantZoneInfo(occupantZoneId, occupantType, seat);
    }

    @Test
    public void getAudioAttributesForVolumeGroup() {
        mCarAudioService.init();
        CarVolumeGroupInfo info = mCarAudioService.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_GROUP);

        List<AudioAttributes> audioAttributes =
                mCarAudioService.getAudioAttributesForVolumeGroup(info);

        expectWithMessage("Volume group audio attributes").that(audioAttributes)
                .containsExactly(
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_GAME),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_UNKNOWN),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_EVENT),
                        CarAudioContext.getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT));
    }

    @Test
    public void getAudioAttributesForVolumeGroup_withNullInfo_fails() {
        mCarAudioService.init();

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () ->
                        mCarAudioService.getAudioAttributesForVolumeGroup(/* groupInfo= */ null));

        expectWithMessage("Volume group audio attributes with null info exception")
                .that(thrown).hasMessageThat().contains("Car volume group info");
    }

    @Test
    public void getAudioAttributesForVolumeGroup_withDynamicRoutingDisabled() {
        when(mMockResources.getBoolean(audioUseDynamicRouting))
                .thenReturn(/* useDynamicRouting= */ false);
        CarAudioService nonDynamicAudioService = new CarAudioService(mMockContext,
                mTemporaryAudioConfigurationFile.getFile().getAbsolutePath(),
                mCarVolumeCallbackHandler);
        nonDynamicAudioService.init();

        List<AudioAttributes> audioAttributes =
                nonDynamicAudioService.getAudioAttributesForVolumeGroup(TEST_PRIMARY_VOLUME_INFO);

        expectWithMessage("Volume group audio attributes with dynamic routing disabled")
                .that(audioAttributes).isEmpty();
    }

    @Test
    public void onKeyEvent_forInvalidAudioZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(INVALID_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_UNKNOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after invalid audio zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forInvalidEvent() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_UNKNOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after unknown key event")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forActionUp() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_UP, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group "
                + "for action up")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(volumeBefore);
    }

    @Test
    public void onKeyEvent_forActionDownFollowedByActionUp() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent actionDownKeyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);
        KeyEvent actionUpKeyEvent = new KeyEvent(ACTION_UP, KEYCODE_VOLUME_UP);
        listener.onKeyEvent(actionDownKeyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        listener.onKeyEvent(actionUpKeyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group "
                + "for action down then action up")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(volumeBefore + 1);
    }

    @Test
    public void onKeyEvent_forVolumeUpEvent_inPrimaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume up in primary zone in primary group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isGreaterThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume down in primary zone in primary group")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone_forSecondaryGroup() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_SECONDARY_GROUP);
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ASSISTANT)
                .setDeviceAddress(VOICE_TEST_DEVICE)
                .build())
        );
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage(
                "Assistant volume group volume after volume down")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_SECONDARY_GROUP))
                .isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inPrimaryZone_withHigherPriority() {
        mCarAudioService.init();
        int primaryGroupVolumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_GROUP);
        int voiceVolumeGroupBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE,
                TEST_THIRD_GROUP);
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(
                new AudioPlaybackConfigurationBuilder()
                        .setUsage(USAGE_VOICE_COMMUNICATION)
                        .setDeviceAddress(CALL_TEST_DEVICE)
                        .build(),
                new AudioPlaybackConfigurationBuilder()
                        .setUsage(USAGE_MEDIA)
                        .setDeviceAddress(MEDIA_TEST_DEVICE)
                        .build())
        );
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Media volume group volume after volume down")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(primaryGroupVolumeBefore);
        expectWithMessage("Call volume group volume after volume do")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_THIRD_GROUP))
                .isLessThan(voiceVolumeGroupBefore);
    }

    @Test
    public void onKeyEvent_forVolumeMuteEvent_inPrimaryZone() {
        mCarAudioService.init();
        boolean muteBefore = mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE,
                TEST_PRIMARY_GROUP);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(PRIMARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(PRIMARY_OCCUPANT_ZONE))
                .thenReturn(PRIMARY_AUDIO_ZONE);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_MUTE);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Volume group volume after volume mute")
                .that(mCarAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isNotEqualTo(muteBefore);
    }

    @Test
    public void onKeyEvent_forVolumeUpEvent_inSecondaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(SECONDARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(SECONDARY_OCCUPANT_ZONE))
                .thenReturn(SECONDARY_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume up")
                .that(mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isGreaterThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeDownEvent_inSecondaryZone() {
        mCarAudioService.init();
        int volumeBefore = mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(SECONDARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(SECONDARY_OCCUPANT_ZONE))
                .thenReturn(SECONDARY_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume down")
                .that(mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isLessThan(volumeBefore);
    }

    @Test
    public void onKeyEvent_forVolumeMuteEvent_inSecondaryZone() {
        mCarAudioService.init();
        boolean muteBefore = mCarAudioService.isVolumeGroupMuted(SECONDARY_ZONE_ID,
                SECONDARY_ZONE_VOLUME_GROUP_ID);
        CarInputService.KeyEventListener listener = getAudioKeyEventListener();
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(TEST_SEAT))
                .thenReturn(SECONDARY_OCCUPANT_ZONE);
        when(mMockOccupantZoneService.getAudioZoneIdForOccupant(SECONDARY_OCCUPANT_ZONE))
                .thenReturn(SECONDARY_ZONE_ID);
        KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_MUTE);

        listener.onKeyEvent(keyEvent, TEST_DISPLAY_TYPE, TEST_SEAT);

        expectWithMessage("Secondary zone volume group after volume mute")
                .that(mCarAudioService.isVolumeGroupMuted(SECONDARY_ZONE_ID,
                        SECONDARY_ZONE_VOLUME_GROUP_ID))
                .isNotEqualTo(muteBefore);
    }

    @Test
    public void onAudioDeviceGainsChanged_forPrimaryZone_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(PRIMARY_AUDIO_ZONE,
                MEDIA_TEST_DEVICE, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.REMOTE_MUTE), List.of(carGain));

        expectWithMessage("New audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void onAudioDeviceGainsChanged_forSecondaryZone_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(SECONDARY_ZONE_ID,
                SECONDARY_TEST_DEVICE, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.REMOTE_MUTE), List.of(carGain));

        expectWithMessage("New audio gains for secondary zone")
                .that(mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID, TEST_PRIMARY_GROUP))
                .isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void onAudioDeviceGainsChanged_forIncorrectDeviceAddress_sameVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        int volumeBefore = mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP);
        CarAudioGainConfigInfo carGain = createCarAudioGainConfigInfo(PRIMARY_AUDIO_ZONE,
                SECONDARY_TEST_DEVICE, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.REMOTE_MUTE), List.of(carGain));

        expectWithMessage("Same audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(volumeBefore);
    }

    @Test
    public void onAudioDeviceGainsChanged_forMultipleZones_changesVolume() {
        mCarAudioService.init();
        HalAudioGainCallback callback = getHalAudioGainCallback();
        CarAudioGainConfigInfo primaryAudioZoneCarGain = createCarAudioGainConfigInfo(
                PRIMARY_AUDIO_ZONE, MEDIA_TEST_DEVICE, TEST_GAIN_INDEX);
        CarAudioGainConfigInfo secondaryAudioZoneCarGain = createCarAudioGainConfigInfo(
                SECONDARY_ZONE_ID, SECONDARY_TEST_DEVICE, TEST_GAIN_INDEX);

        callback.onAudioDeviceGainsChanged(List.of(Reasons.THERMAL_LIMITATION),
                List.of(primaryAudioZoneCarGain, secondaryAudioZoneCarGain));

        expectWithMessage("New audio gains for primary zone")
                .that(mCarAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_PRIMARY_GROUP))
                .isEqualTo(TEST_GAIN_INDEX);
        expectWithMessage("New audio gains for secondary zone")
                .that(mCarAudioService.getGroupVolume(SECONDARY_ZONE_ID, TEST_PRIMARY_GROUP))
                .isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void getActiveAudioAttributesForZone() {
        mCarAudioService.init();

        expectWithMessage("Default active audio attributes").that(
                mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE)).isEmpty();
    }

    @Test
    public void getActiveAudioAttributesForZone_withActiveHalFocus() {
        when(mAudioManager.requestAudioFocus(any())).thenReturn(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        mCarAudioService.init();
        requestHalAudioFocus(USAGE_ALARM);

        expectWithMessage("HAL active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_ALARM).build());
    }

    @Test
    public void getActiveAudioAttributesForZone_withActivePlayback() {
        mCarAudioService.init();
        mockActivePlayback();

        expectWithMessage("Playback active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build());
    }

    @Test
    public void getActiveAudioAttributesForZone_withActiveHalAndPlayback() {
        mCarAudioService.init();
        mockActivePlayback();
        when(mAudioManager.requestAudioFocus(any())).thenReturn(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        requestHalAudioFocus(USAGE_VOICE_COMMUNICATION);

        expectWithMessage("Playback active audio attributes")
                .that(mCarAudioService.getActiveAudioAttributesForZone(PRIMARY_AUDIO_ZONE))
                .containsExactly(new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build(),
                        new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION).build());
    }

    @Test
    public void getCallStateForZone_forPrimaryZone() throws Exception {
        when(mMockTelephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        mCarAudioService.init();
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_DRIVER_USER_ID, TEST_USER_ID_SECONDARY);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("Primary zone call state").that(
                mCarAudioService.getCallStateForZone(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TelephonyManager.CALL_STATE_OFFHOOK);
    }

    @Test
    public void getCallStateForZone_forNonPrimaryZone() throws Exception {
        when(mMockTelephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        when(mMockOccupantZoneService.getDriverUserId()).thenReturn(TEST_DRIVER_USER_ID);
        mCarAudioService.init();
        when(mMockOccupantZoneService.getUserForOccupant(anyInt()))
                .thenReturn(TEST_PASSENGER_USER_ID, TEST_USER_ID_SECONDARY);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        expectWithMessage("Secondary zone call state").that(
                        mCarAudioService.getCallStateForZone(SECONDARY_ZONE_ID))
                .isEqualTo(TelephonyManager.CALL_STATE_IDLE);
    }

    @Test
    public void getVolumeGroupAndContextCount() {
        CarAudioService useCoreAudioCarAudioService =
                getCarAudioServiceUsingCoreAudioRoutingAndVolume();

        verify(mAudioManager).registerVolumeGroupCallback(any(), any());
        expectWithMessage("Primary zone car volume group count")
                .that(useCoreAudioCarAudioService.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .isEqualTo(CoreAudioRoutingUtils.getVolumeGroups().size());
        expectWithMessage("Number of contexts")
                .that(useCoreAudioCarAudioService.getCarAudioContext().getAllContextsIds().size())
                .isEqualTo(CoreAudioRoutingUtils.getProductStrategies().size());
        expectWithMessage("Car Audio Contexts")
                .that(useCoreAudioCarAudioService.getCarAudioContext().getAllContextsIds())
                .containsExactly(CoreAudioRoutingUtils.NAV_STRATEGY_ID,
                        CoreAudioRoutingUtils.MUSIC_STRATEGY_ID,
                        CoreAudioRoutingUtils.OEM_STRATEGY_ID);
    }

    private CarAudioGainConfigInfo createCarAudioGainConfigInfo(int zoneId,
            String devicePortAddress, int volumeIndex) {
        AudioGainConfigInfo configInfo = new AudioGainConfigInfo();
        configInfo.zoneId = zoneId;
        configInfo.devicePortAddress = devicePortAddress;
        configInfo.volumeIndex = volumeIndex;
        return new CarAudioGainConfigInfo(configInfo);
    }

    private HalAudioGainCallback getHalAudioGainCallback() {
        ArgumentCaptor<HalAudioGainCallback> captor = ArgumentCaptor.forClass(
                HalAudioGainCallback.class);
        verify(mAudioControlWrapperAidl).registerAudioGainCallback(captor.capture());
        return captor.getValue();
    }

    private AudioPlaybackCallback getCarAudioPlaybackCallback() {
        ArgumentCaptor<AudioPlaybackCallback> captor = ArgumentCaptor.forClass(
                AudioPlaybackCallback.class);
        verify(mAudioManager).registerAudioPlaybackCallback(captor.capture(), any());
        return captor.getValue();
    }

    private CarInputService.KeyEventListener getAudioKeyEventListener() {
        ArgumentCaptor<CarInputService.KeyEventListener> captor =
                ArgumentCaptor.forClass(CarInputService.KeyEventListener.class);
        verify(mMockCarInputService).registerKeyEventListener(captor.capture(), any());
        return captor.getValue();
    }

    private void requestHalAudioFocus(int usage) {
        ArgumentCaptor<HalFocusListener> captor =
                ArgumentCaptor.forClass(HalFocusListener.class);
        verify(mAudioControlWrapperAidl).registerFocusListener(captor.capture());
        HalFocusListener halFocusListener = captor.getValue();
        halFocusListener.requestAudioFocus(usage, PRIMARY_AUDIO_ZONE,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }

    private void mockActivePlayback() {
        AudioPlaybackCallback callback = getCarAudioPlaybackCallback();
        callback.onPlaybackConfigChanged(List.of(getPlaybackConfig()));
    }

    private AudioPlaybackConfiguration getPlaybackConfig() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(USAGE_MEDIA).build();
        AudioPlaybackConfiguration config = mock(AudioPlaybackConfiguration.class);
        when(config.getAudioAttributes()).thenReturn(audioAttributes);
        when(config.getAudioDeviceInfo()).thenReturn(mMediaOutputDevice);
        when(config.isActive()).thenReturn(true);

        return config;
    }

    private CarAudioService getCarAudioServiceUsingCoreAudioRoutingAndVolume() {
        when(mMockResources.getBoolean(audioUseCoreVolume))
                .thenReturn(/* audioUseCoreVolume= */ true);
        when(mMockResources.getBoolean(audioUseCoreRouting))
                .thenReturn(/* audioUseCoreRouting= */ true);
        CarAudioService useCoreAudioCarAudioService =
                new CarAudioService(mMockContext,
                        mTemporaryAudioConfigurationUsingCoreAudioFile.getFile().getAbsolutePath(),
                        mCarVolumeCallbackHandler);
        useCoreAudioCarAudioService.init();
        return useCoreAudioCarAudioService;
    }

    private void mockGrantCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_GRANTED);
    }

    private void mockDenyCarControlAudioSettingsPermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_SETTINGS, PERMISSION_DENIED);
    }

    private void mockDenyCarControlAudioVolumePermission() {
        mockContextCheckCallingOrSelfPermission(mMockContext,
                PERMISSION_CAR_CONTROL_AUDIO_VOLUME, PERMISSION_DENIED);
    }

    private AudioDeviceInfo[] generateInputDeviceInfos() {
        mMicrophoneInputDevice = new AudioDeviceInfoBuilder()
                .setAddressName(PRIMARY_ZONE_MICROPHONE_ADDRESS)
                .setType(TYPE_BUILTIN_MIC)
                .setIsSource(true)
                .build();
        mFmTunerInputDevice = new AudioDeviceInfoBuilder()
                .setAddressName(PRIMARY_ZONE_FM_TUNER_ADDRESS)
                .setType(TYPE_FM_TUNER)
                .setIsSource(true)
                .build();
        return new AudioDeviceInfo[]{mMicrophoneInputDevice, mFmTunerInputDevice};
    }

    private AudioDeviceInfo[] generateOutputDeviceInfos() {
        mMediaOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                .setAddressName(MEDIA_TEST_DEVICE)
                .build();
        return new AudioDeviceInfo[] {
                mMediaOutputDevice,
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(NAVIGATION_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(CALL_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SYSTEM_BUS_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(NOTIFICATION_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(VOICE_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(RING_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(ALARM_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(SECONDARY_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new AudioGain[] {new GainBuilder().build()})
                        .setAddressName(OEM_TEST_DEVICE)
                        .build(),
        };
    }

    private void mockCoreAudioRoutingAndVolume() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(() -> AudioManager.getAudioProductStrategies());
        doReturn(CoreAudioRoutingUtils.getVolumeGroups())
                .when(() -> AudioManager.getAudioVolumeGroups());

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(
                eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(
                eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_MAX_INDEX);
        when(mAudioManager.getVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_AM_INIT_INDEX);
        when(mAudioManager.getLastAudibleVolumeGroupVolume(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(CoreAudioRoutingUtils.MUSIC_AM_INIT_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.MUSIC_GROUP_ID))
                .thenReturn(false);

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.NAV_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.NAV_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.NAV_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.NAV_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.NAV_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.NAV_MAX_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.NAV_GROUP_ID))
                .thenReturn(false);

        when(mAudioManager.getVolumeGroupIdForAttributes(CoreAudioRoutingUtils.OEM_ATTRIBUTES))
                .thenReturn(CoreAudioRoutingUtils.OEM_GROUP_ID);
        when(mAudioManager.getMinVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.OEM_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.OEM_MIN_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(eq(CoreAudioRoutingUtils.OEM_ATTRIBUTES)))
                .thenReturn(CoreAudioRoutingUtils.OEM_MAX_INDEX);
        when(mAudioManager.isVolumeGroupMuted(CoreAudioRoutingUtils.OEM_GROUP_ID))
                .thenReturn(false);
    }

    private static AudioFocusInfo createAudioFocusInfoForMedia() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setUsage(USAGE_MEDIA);

        return new AudioFocusInfo(builder.build(), MEDIA_APP_UID, MEDIA_CLIENT_ID,
                MEDIA_PACKAGE_NAME, AUDIOFOCUS_GAIN, AUDIOFOCUS_LOSS, MEDIA_EMPTY_FLAG, SDK_INT);
    }

    private static final class TestPrimaryZoneMediaAudioRequestCallback extends
            IPrimaryZoneMediaAudioRequestCallback.Stub {
        private long mRequestId = INVALID_REQUEST_ID;
        private CarOccupantZoneManager.OccupantZoneInfo mInfo;
        private CountDownLatch mStatusLatch = new CountDownLatch(1);
        private int mStatus;

        @Override
        public void onRequestMediaOnPrimaryZone(CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId) {
            mInfo = info;
            mRequestId = requestId;
            mStatusLatch.countDown();
        }

        @Override
        public void onMediaAudioRequestStatusChanged(
                @NonNull CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId, int status) {
            mInfo = info;
            mRequestId = requestId;
            mStatus = status;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public void reset() {
            mInfo = null;
            mRequestId = INVALID_REQUEST_ID;
            mStatus = INVALID_STATUS;
            mStatusLatch = new CountDownLatch(1);
        }
    }

    private static final class TestMediaRequestStatusCallback extends
            IMediaAudioRequestStatusCallback.Stub {
        private long mRequestId = INVALID_REQUEST_ID;
        private CarOccupantZoneManager.OccupantZoneInfo mInfo;
        private int mStatus;
        private final CountDownLatch mStatusLatch = new CountDownLatch(1);

        @Override
        public void onMediaAudioRequestStatusChanged(
                @NonNull CarOccupantZoneManager.OccupantZoneInfo info,
                long requestId, @CarAudioManager.MediaAudioRequestStatus int status)
                throws RemoteException {
            mInfo = info;
            mRequestId = requestId;
            mStatus = status;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }
}

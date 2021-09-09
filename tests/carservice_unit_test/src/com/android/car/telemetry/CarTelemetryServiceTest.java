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

package com.android.car.telemetry;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;
import android.content.Context;
import android.os.Handler;

import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.CarPropertyService;
import com.android.car.systeminterface.SystemInterface;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
@SmallTest
public class CarTelemetryServiceTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final String METRICS_CONFIG_NAME = "my_metrics_config";
    private static final MetricsConfigKey KEY_V1 = new MetricsConfigKey(METRICS_CONFIG_NAME, 1);
    private static final MetricsConfigKey KEY_V2 = new MetricsConfigKey(METRICS_CONFIG_NAME, 2);
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(1).setScript("no-op").build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V2 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(2).setScript("no-op").build();

    private CountDownLatch mIdleHandlerLatch = new CountDownLatch(1);
    private CarTelemetryService mService;
    private File mTempSystemCarDir;
    private Handler mTelemetryHandler;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private Context mContext;
    @Mock
    private ICarTelemetryServiceListener mMockListener;
    @Mock
    private SystemInterface mMockSystemInterface;

    @Before
    public void setUp() throws Exception {
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mMockSystemInterface);

        mTempSystemCarDir = Files.createTempDirectory("telemetry_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);

        mService = new CarTelemetryService(mContext, mMockCarPropertyService);
        mService.init();
        mService.setListener(mMockListener);

        mTelemetryHandler = mService.getTelemetryHandler();
        mTelemetryHandler.getLooper().getQueue().addIdleHandler(() -> {
            mIdleHandlerLatch.countDown();
            return true;
        });
        waitForHandlerThreadToFinish();
    }

    @Test
    public void testAddMetricsConfig_newMetricsConfig_shouldSucceed() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));
    }

    @Test
    public void testAddMetricsConfig_duplicateMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());
        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));

        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS));
    }

    @Test
    public void testAddMetricsConfig_invalidMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V1, "bad manifest".getBytes());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED));
    }

    @Test
    public void testAddMetricsConfig_olderMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V2, METRICS_CONFIG_V2.toByteArray());
        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V2), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));

        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD));
    }

    @Test
    public void testAddMetricsConfig_newerMetricsConfig_shouldReplace() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        mService.addMetricsConfig(KEY_V2, METRICS_CONFIG_V2.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V2), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));
    }

    @Test
    public void testRemoveMetricsConfig_manifestExists_shouldSucceed() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        mService.removeMetricsConfig(KEY_V1);

        waitForHandlerThreadToFinish();
        verify(mMockListener).onRemoveMetricsConfigStatus(eq(KEY_V1), eq(true));
    }

    @Test
    public void testRemoveMetricsConfig_manifestDoesNotExist_shouldFail() throws Exception {
        mService.removeMetricsConfig(KEY_V1);

        waitForHandlerThreadToFinish();
        verify(mMockListener).onRemoveMetricsConfigStatus(eq(KEY_V1), eq(false));
    }

    private void waitForHandlerThreadToFinish() throws Exception {
        assertWithMessage("handler not idle in %sms", TIMEOUT_MS)
                .that(mIdleHandlerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        mIdleHandlerLatch = new CountDownLatch(1); // reset idle handler condition
        mTelemetryHandler.runWithScissors(() -> { }, TIMEOUT_MS);
    }
}

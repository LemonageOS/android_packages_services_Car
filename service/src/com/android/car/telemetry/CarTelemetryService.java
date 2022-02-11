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

import static android.car.telemetry.CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_PARSE_FAILED;
import static android.car.telemetry.CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_SUCCEEDED;
import static android.car.telemetry.CarTelemetryManager.STATUS_GET_METRICS_CONFIG_DOES_NOT_EXIST;
import static android.car.telemetry.CarTelemetryManager.STATUS_GET_METRICS_CONFIG_FINISHED;
import static android.car.telemetry.CarTelemetryManager.STATUS_GET_METRICS_CONFIG_INTERIM_RESULTS;
import static android.car.telemetry.CarTelemetryManager.STATUS_GET_METRICS_CONFIG_PENDING;
import static android.car.telemetry.CarTelemetryManager.STATUS_GET_METRICS_CONFIG_RUNTIME_ERROR;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import static java.util.stream.Collectors.toList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.Car;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.builtin.util.TimingsTraceLog;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.ICarTelemetryReportListener;
import android.car.telemetry.ICarTelemetryReportReadyListener;
import android.car.telemetry.ICarTelemetryService;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ResultReceiver;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.OnShutdownReboot;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.telemetry.databroker.DataBroker;
import com.android.car.telemetry.databroker.DataBrokerController;
import com.android.car.telemetry.databroker.DataBrokerImpl;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.sessioncontroller.SessionController;
import com.android.car.telemetry.systemmonitor.SystemMonitor;
import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * CarTelemetryService manages OEM telemetry collection, processing and communication
 * with a data upload service.
 */
public class CarTelemetryService extends ICarTelemetryService.Stub implements CarServiceBase {

    public static final boolean DEBUG = true; // STOPSHIP if true

    private static final String PUBLISHER_DIR = "publisher";
    public static final String TELEMETRY_DIR = "telemetry";

    private final Context mContext;
    private final CarPropertyService mCarPropertyService;
    private final Dependencies mDependencies;
    private final HandlerThread mTelemetryThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mTelemetryHandler = new Handler(mTelemetryThread.getLooper());

    // accessed and updated on the main thread
    private boolean mReleased = false;

    // all the following fields are accessed and updated on the telemetry thread
    private DataBroker mDataBroker;
    private DataBrokerController mDataBrokerController;
    private ICarTelemetryReportReadyListener mReportReadyListener;
    private MetricsConfigStore mMetricsConfigStore;
    private OnShutdownReboot mOnShutdownReboot;
    private PublisherFactory mPublisherFactory;
    private ResultStore mResultStore;
    private SessionController mSessionController;
    private SystemMonitor mSystemMonitor;
    private TimingsTraceLog mTelemetryThreadTraceLog; // can only be used on telemetry thread

    static class Dependencies {
        /**
         * Get a PublisherFactory instance.
         */
        public PublisherFactory getPublisherFactory(CarPropertyService carPropertyService,
                Handler handler, Context context, File publisherDirectory) {
            return new PublisherFactory(carPropertyService, handler, context, publisherDirectory);
        }
    }

    public CarTelemetryService(Context context, CarPropertyService carPropertyService) {
        this(context, carPropertyService, new Dependencies());
    }

    @VisibleForTesting
    CarTelemetryService(Context context, CarPropertyService carPropertyService, Dependencies deps) {
        mContext = context;
        mCarPropertyService = carPropertyService;
        mDependencies = deps;
    }

    @Override
    public void init() {
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog = new TimingsTraceLog(
                    CarLog.TAG_TELEMETRY, TraceHelper.TRACE_TAG_CAR_SERVICE);
            mTelemetryThreadTraceLog.traceBegin("init");
            SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
            // full root directory path is /data/system/car/telemetry
            File rootDirectory = new File(systemInterface.getSystemCarDir(), TELEMETRY_DIR);
            File publisherDirectory = new File(rootDirectory, PUBLISHER_DIR);
            publisherDirectory.mkdirs();
            // initialize all necessary components
            mMetricsConfigStore = new MetricsConfigStore(rootDirectory);
            mResultStore = new ResultStore(rootDirectory);
            mSessionController = new SessionController(mContext, mTelemetryHandler);
            mPublisherFactory = mDependencies.getPublisherFactory(mCarPropertyService,
                    mTelemetryHandler, mContext, publisherDirectory);
            mDataBroker = new DataBrokerImpl(mContext, mPublisherFactory, mResultStore,
                    mTelemetryThreadTraceLog);
            ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
            mSystemMonitor = SystemMonitor.create(activityManager, mTelemetryHandler);
            // controller starts metrics collection after boot complete
            mDataBrokerController = new DataBrokerController(mDataBroker, mTelemetryHandler,
                    mMetricsConfigStore, this::onReportReady, mSystemMonitor,
                    systemInterface.getSystemStateInterface(), mSessionController);
            mTelemetryThreadTraceLog.traceEnd();
            // save state at reboot and shutdown
            mOnShutdownReboot = new OnShutdownReboot(mContext);
            mOnShutdownReboot.addAction((context, intent) -> release());
        });
    }

    @Override
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog.traceBegin("release");
            mResultStore.flushToDisk();
            mOnShutdownReboot.release();
            mSessionController.release();
            mTelemetryThreadTraceLog.traceEnd();
        });
        mTelemetryThread.quitSafely();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarTelemetryService*");
        writer.println();
        // Print active configs with their interim results and errors.
        writer.println("Active Configs");
        writer.println();
        for (TelemetryProto.MetricsConfig config : mMetricsConfigStore.getActiveMetricsConfigs()) {
            writer.println("    Name: " + config.getName());
            writer.println("    Version: " + config.getVersion());
            PersistableBundle interimResult = mResultStore.getInterimResult(config.getName());
            if (interimResult != null) {
                writer.println("    Interim Result");
                writer.println("        Bundle keys: "
                        + Arrays.toString(interimResult.keySet().toArray()));
            }
            writer.println();
        }
        // Print info on stored final results. Configs are inactive after producing final result.
        Map<String, PersistableBundle> finalResults = mResultStore.getFinalResults();
        writer.println("Final Results");
        writer.println();
        for (Map.Entry<String, PersistableBundle> entry : finalResults.entrySet()) {
            writer.println("    Config name: " + entry.getKey());
            writer.println("    Bundle keys: "
                    + Arrays.toString(entry.getValue().keySet().toArray()));
            writer.println();
        }
        // Print info on stored errors. Configs are inactive after producing errors.
        Map<String, TelemetryProto.TelemetryError> errors = mResultStore.getErrorResults();
        writer.println("Errors");
        writer.println();
        for (Map.Entry<String, TelemetryProto.TelemetryError> entry : errors.entrySet()) {
            writer.println("    Config name: " + entry.getKey());
            TelemetryProto.TelemetryError error = entry.getValue();
            writer.println("    Error");
            writer.println("        Type: " + error.getErrorType());
            writer.println("        Message: " + error.getMessage());
            if (error.hasStackTrace() && !error.getStackTrace().isEmpty()) {
                writer.println("        Stack trace: " + error.getStackTrace());
            }
            writer.println();
        }
    }

    /**
     * Send a telemetry metrics config to the service.
     * @param metricsConfigName name of the MetricsConfig.
     * @param config the serialized bytes of a MetricsConfig object.
     * @param callback to send status code to CarTelemetryManager.
     */
    @Override
    public void addMetricsConfig(@NonNull String metricsConfigName, @NonNull byte[] config,
            @NonNull ResultReceiver callback) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog.traceBegin("addMetricsConfig");
            int status = addMetricsConfigInternal(metricsConfigName, config);
            callback.send(status, null);
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /** Adds the MetricsConfig and returns the status. */
    private int addMetricsConfigInternal(
            @NonNull String metricsConfigName, @NonNull byte[] config) {
        Slogf.d(CarLog.TAG_TELEMETRY,
                "Adding metrics config: " + metricsConfigName + " to car telemetry service");
        TelemetryProto.MetricsConfig metricsConfig;
        try {
            metricsConfig = TelemetryProto.MetricsConfig.parseFrom(config);
        } catch (InvalidProtocolBufferException e) {
            Slogf.e(CarLog.TAG_TELEMETRY, "Failed to parse MetricsConfig.", e);
            return STATUS_ADD_METRICS_CONFIG_PARSE_FAILED;
        }
        if (!metricsConfig.getName().equals(metricsConfigName)) {
            Slogf.e(CarLog.TAG_TELEMETRY, "Argument config name " + metricsConfigName
                    + " doesn't match name in MetricsConfig (" + metricsConfig.getName() + ").");
            return STATUS_ADD_METRICS_CONFIG_PARSE_FAILED;
        }
        int status = mMetricsConfigStore.addMetricsConfig(metricsConfig);
        if (status != STATUS_ADD_METRICS_CONFIG_SUCCEEDED) {
            return status;
        }
        // If no error (config is added to the MetricsConfigStore), remove previously collected data
        // for this config and add config to the DataBroker for metrics collection.
        mResultStore.removeResult(metricsConfigName);
        mDataBroker.removeMetricsConfig(metricsConfigName);
        mDataBroker.addMetricsConfig(metricsConfigName, metricsConfig);
        // TODO(b/199410900): update logic once metrics configs have expiration dates
        return STATUS_ADD_METRICS_CONFIG_SUCCEEDED;
    }

    /**
     * Removes a metrics config based on the name. This will also remove outputs produced by the
     * MetricsConfig.
     *
     * @param metricsConfigName the unique identifier of a MetricsConfig.
     */
    @Override
    public void removeMetricsConfig(@NonNull String metricsConfigName) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeMetricsConfig");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Removing metrics config " + metricsConfigName
                        + " from car telemetry service");
            }
            mTelemetryThreadTraceLog.traceBegin("removeMetricsConfig");
            if (mMetricsConfigStore.removeMetricsConfig(metricsConfigName)) {
                mDataBroker.removeMetricsConfig(metricsConfigName);
                mResultStore.removeResult(metricsConfigName);
            }
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    @Override
    public void removeAllMetricsConfigs() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeAllMetricsConfigs");
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog.traceBegin("removeAllMetricsConfig");
            Slogf.d(CarLog.TAG_TELEMETRY,
                    "Removing all metrics config from car telemetry service");
            mDataBroker.removeAllMetricsConfigs();
            mMetricsConfigStore.removeAllMetricsConfigs();
            mResultStore.removeAllResults();
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Sends telemetry reports associated with the given config name using the
     * {@link ICarTelemetryReportListener}.
     *
     * @param metricsConfigName the unique identifier of a MetricsConfig.
     * @param listener to receive finished report or error.
     */
    @Override
    public void getFinishedReport(@NonNull String metricsConfigName,
            @NonNull ICarTelemetryReportListener listener) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "getFinishedReport");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY,
                        "Flushing reports for metrics config " + metricsConfigName);
            }
            mTelemetryThreadTraceLog.traceBegin("getFinishedReport");
            PersistableBundle report;
            TelemetryProto.TelemetryError error;
            if ((report = mResultStore.getFinalResult(metricsConfigName, true)) != null) {
                sendResult(listener, metricsConfigName, /* report = */ report, /* error = */ null,
                        /* status = */ STATUS_GET_METRICS_CONFIG_FINISHED);
            } else if (mResultStore.getInterimResult(metricsConfigName) != null) {
                sendResult(listener, metricsConfigName, /* report = */ null, /* error = */null,
                        /* status = */ STATUS_GET_METRICS_CONFIG_INTERIM_RESULTS);
            } else if ((error = mResultStore.getErrorResult(metricsConfigName, true)) != null) {
                sendResult(listener, metricsConfigName, /* report = */ null, /* error = */ error,
                        /* status = */ STATUS_GET_METRICS_CONFIG_RUNTIME_ERROR);
            } else if (mMetricsConfigStore.containsConfig(metricsConfigName)) {
                sendResult(listener, metricsConfigName, /* report = */ null, /* error = */ null,
                        /* status = */ STATUS_GET_METRICS_CONFIG_PENDING);
            } else {
                sendResult(listener, metricsConfigName, /* report = */ null, /* error = */ null,
                        /* status = */ STATUS_GET_METRICS_CONFIG_DOES_NOT_EXIST);
            }
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Sends all script reports or errors using the {@link ICarTelemetryReportListener}.
     */
    @Override
    public void getAllFinishedReports(@NonNull ICarTelemetryReportListener listener) {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "getAllFinishedReports");
        if (DEBUG) {
            Slogf.d(CarLog.TAG_TELEMETRY, "Flushing all reports");
        }
    }

    /**
     * Sets a listener for report ready notifications.
     */
    @Override
    public void setReportReadyListener(@NonNull ICarTelemetryReportReadyListener listener) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setReportReadyListener");
        mTelemetryHandler.post(() -> mReportReadyListener = listener);
    }

    /**
     * Clears the listener to stop report ready notifications.
     */
    @Override
    public void clearReportReadyListener() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "clearReportReadyListener");
        mTelemetryHandler.post(() -> mReportReadyListener = null);
    }

    /**
     * Implementation of the functional interface {@link DataBrokerController.ReportReadyListener}.
     * Invoked from {@link DataBrokerController} when a script produces a report or a runtime error.
     */
    private void onReportReady(@NonNull String metricsConfigName) {
        if (mReportReadyListener == null) {
            return;
        }
        try {
            mReportReadyListener.onReady(metricsConfigName);
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryReportReadyListener", e);
        }
    }

    /**
     * Adds the MetricsConfig. This methods is expected to be used only by {@code CarShellCommand}
     * class, because CarTelemetryService supports only a single listener and the shell command
     * shouldn't replace the existing listener. Other usages are not supported.
     * @param metricsConfigName name of the MetricsConfig.
     * @param config config body serialized as a binary protobuf.
     * @param statusConsumer receives the status code.
     */
    public void addMetricsConfig(@NonNull String metricsConfigName, @NonNull byte[] config,
            @NonNull IntConsumer statusConsumer) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(
                () -> statusConsumer.accept(addMetricsConfigInternal(metricsConfigName, config)));
    }

    /**
     * Returns the finished reports. This methods is expected to be used only by {@code
     * CarShellCommand} class, because CarTelemetryService supports only a single listener and the
     * shell command shouldn't replace the existing listener. Other usages are not supported.
     *
     * <p>It sends {@code ErrorType.UNSPECIFIED} if there are no reports.
     *
     * @param metricsConfigName MetricsConfig name.
     * @param deleteResult if true, the result will be deleted from the storage.
     * @param consumer receives the final result or error.
     */
    public void getFinishedReports(
            @NonNull String metricsConfigName,
            boolean deleteResult,
            @NonNull BiConsumer<PersistableBundle, TelemetryProto.TelemetryError> consumer) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "getFinishedReports");
        mTelemetryHandler.post(() -> {
            PersistableBundle result = mResultStore.getFinalResult(metricsConfigName, deleteResult);
            TelemetryProto.TelemetryError error =
                    mResultStore.getErrorResult(metricsConfigName, deleteResult);
            if (result != null) {
                consumer.accept(result, null);
            } else if (error != null) {
                consumer.accept(null, error);
            } else {
                // TODO(b/209469238): Create a NO_RESULT error type
                TelemetryProto.TelemetryError unknownError =
                        TelemetryProto.TelemetryError.newBuilder()
                                .setErrorType(
                                        TelemetryProto.TelemetryError.ErrorType.UNSPECIFIED)
                                .setMessage("No results")
                                .build();
                consumer.accept(null, unknownError);
            }
        });
    }

    /**
     * Returns the list of config names and versions. This methods is expected to be used only by
     * {@code CarShellCommand} class. Other usages are not supported.
     */
    @NonNull
    public List<String> getActiveMetricsConfigDetails() {
        return mMetricsConfigStore.getActiveMetricsConfigs().stream()
                .map((config) -> config.getName() + " version=" + config.getVersion())
                .collect(toList());
    }

    @Nullable
    private byte[] getBytes(@Nullable PersistableBundle report) {
        if (report == null) {
            return null;
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            report.writeToStream(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            String msg = "Failed to write PersistableBundle to output stream. ";
            Slogf.w(CarLog.TAG_TELEMETRY, msg, e);
            return null;
        }
    }

    @Nullable
    private byte[] getBytes(@Nullable TelemetryProto.TelemetryError error) {
        if (error == null) {
            return null;
        }
        return error.toByteArray();
    }

    private void sendResult(
            @NonNull ICarTelemetryReportListener listener,
            @NonNull String metricsConfigName,
            @Nullable PersistableBundle report,
            @Nullable TelemetryProto.TelemetryError error,
            @CarTelemetryManager.MetricsReportStatus int status) {
        try {
            listener.onResult(metricsConfigName, getBytes(report), getBytes(error), status);
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryReportListener", e);
        }
    }

    @VisibleForTesting
    Handler getTelemetryHandler() {
        return mTelemetryHandler;
    }

    @VisibleForTesting
    ResultStore getResultStore() {
        return mResultStore;
    }

    @VisibleForTesting
    MetricsConfigStore getMetricsConfigStore() {
        return mMetricsConfigStore;
    }
}

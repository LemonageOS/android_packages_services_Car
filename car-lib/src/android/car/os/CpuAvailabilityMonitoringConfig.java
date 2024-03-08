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

package android.car.os;

import android.annotation.SuppressLint;
import android.car.annotation.ExperimentalFeature;
import android.os.Parcelable;

import com.android.car.internal.util.DataClass;

/**
 * CPU availability monitoring config.
 *
 * @hide
 */
@ExperimentalFeature
@DataClass(genToString = true, genBuilder = true, genHiddenConstDefs = true)
public final class CpuAvailabilityMonitoringConfig implements Parcelable {
    /** Constant to monitor all cpusets. */
    @Cpuset
    public static final int CPUSET_ALL = 1;

    /** Constant to monitor only background cpusets. */
    @Cpuset
    public static final int CPUSET_BACKGROUND = 2;

    /** Constant to ignore the CPU availability lower bound percent. */
    @IgnorePercent
    public static final int IGNORE_PERCENT_LOWER_BOUND = 0;

    /** Constant to ignore the CPU availability upper bound percent. */
    @IgnorePercent
    public static final int IGNORE_PERCENT_UPPER_BOUND = 100;

    /** Constant to avoid timing out when monitoring CPU availability. */
    public static final int MONITORING_TIMEOUT_NEVER = -1;

    /**
     * Constant to notify the listener on timeout.
     *
     * <p>When the timeout action is notification, the timeout resets on each notification and the
     * listener is again on the next timeout. This repeats until the listener is explicitly removed.
     */
    @TimeoutAction
    public static final int TIMEOUT_ACTION_NOTIFICATION = 1;

    /** Constant to remove the listener on timeout. */
    @TimeoutAction
    public static final int TIMEOUT_ACTION_REMOVE = 2;

    /**
     * CPUSETs to monitor.
     */
    private @Cpuset int mCpuset = CPUSET_ALL;

    /**
     * CPU availability lower bound percent.
     *
     * <p>CPU availability change notifications are sent when the CPU availability reaches or
     * decreases below this value. The value of this field must be less than the value of
     * {@link #mUpperBoundPercent}.
     *
     * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
     * percent and lower bound percent must be reasonably large.
     *
     * <p>To ignore this field, specify {@link #IGNORE_PERCENT_LOWER_BOUND} as the value.
     * Must not ignore both this field and {@link #mUpperBoundPercent} in the same configuration.
     */
    private int mLowerBoundPercent;

    /**
     * CPU availability upper bound percent.
     *
     * <p>CPU availability change notifications are sent when the CPU availability reaches or
     * increases above this value. The value of this field must be greater than the value of
     * {@link #mLowerBoundPercent}.
     *
     * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
     * percent and lower bound percent must be reasonably large.
     *
     * <p>To ignore this field, specify {@link #IGNORE_PERCENT_UPPER_BOUND} as the value.
     * Must not ignore both this field and {@link #mLowerBoundPercent} in the same configuration.
     */
    private int mUpperBoundPercent;

    /**
     * CPU availability monitoring timeout in seconds.
     *
     * <p>To avoid timing out, specify {@link #MONITORING_TIMEOUT_NEVER} as the value.
     */
    @SuppressLint({"MethodNameUnits"})
    private long mTimeoutInSeconds;

    /**
     * Action to take on timeout. Specify one of the {@code TIMEOUT_ACTION_*} constants.
     *
     * <p>When the value of {@link #mTimeoutInSeconds} is {@link #MONITORING_TIMEOUT_NEVER},
     * this field is ignored.
     */
    private @TimeoutAction int mTimeoutAction = TIMEOUT_ACTION_NOTIFICATION;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/os/CpuAvailabilityMonitoringConfig.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @android.annotation.IntDef(prefix = "CPUSET_", value = {
        CPUSET_ALL,
        CPUSET_BACKGROUND
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Cpuset {}

    /** @hide */
    @DataClass.Generated.Member
    public static String cpusetToString(@Cpuset int value) {
        switch (value) {
            case CPUSET_ALL:
                    return "CPUSET_ALL";
            case CPUSET_BACKGROUND:
                    return "CPUSET_BACKGROUND";
            default: return Integer.toHexString(value);
        }
    }

    /** @hide */
    @android.annotation.IntDef(prefix = "IGNORE_PERCENT_", value = {
        IGNORE_PERCENT_LOWER_BOUND,
        IGNORE_PERCENT_UPPER_BOUND
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface IgnorePercent {}

    /** @hide */
    @DataClass.Generated.Member
    public static String ignorePercentToString(@IgnorePercent int value) {
        switch (value) {
            case IGNORE_PERCENT_LOWER_BOUND:
                    return "IGNORE_PERCENT_LOWER_BOUND";
            case IGNORE_PERCENT_UPPER_BOUND:
                    return "IGNORE_PERCENT_UPPER_BOUND";
            default: return Integer.toHexString(value);
        }
    }

    /** @hide */
    @android.annotation.IntDef(prefix = "TIMEOUT_ACTION_", value = {
        TIMEOUT_ACTION_NOTIFICATION,
        TIMEOUT_ACTION_REMOVE
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface TimeoutAction {}

    /** @hide */
    @DataClass.Generated.Member
    public static String timeoutActionToString(@TimeoutAction int value) {
        switch (value) {
            case TIMEOUT_ACTION_NOTIFICATION:
                    return "TIMEOUT_ACTION_NOTIFICATION";
            case TIMEOUT_ACTION_REMOVE:
                    return "TIMEOUT_ACTION_REMOVE";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ CpuAvailabilityMonitoringConfig(
            @Cpuset int cpuset,
            int lowerBoundPercent,
            int upperBoundPercent,
            @SuppressLint({ "MethodNameUnits" }) long timeoutInSeconds,
            @TimeoutAction int timeoutAction) {
        this.mCpuset = cpuset;

        if (!(mCpuset == CPUSET_ALL)
                && !(mCpuset == CPUSET_BACKGROUND)) {
            throw new java.lang.IllegalArgumentException(
                    "cpuset was " + mCpuset + " but must be one of: "
                            + "CPUSET_ALL(" + CPUSET_ALL + "), "
                            + "CPUSET_BACKGROUND(" + CPUSET_BACKGROUND + ")");
        }

        this.mLowerBoundPercent = lowerBoundPercent;
        this.mUpperBoundPercent = upperBoundPercent;
        this.mTimeoutInSeconds = timeoutInSeconds;
        this.mTimeoutAction = timeoutAction;

        if (!(mTimeoutAction == TIMEOUT_ACTION_NOTIFICATION)
                && !(mTimeoutAction == TIMEOUT_ACTION_REMOVE)) {
            throw new java.lang.IllegalArgumentException(
                    "timeoutAction was " + mTimeoutAction + " but must be one of: "
                            + "TIMEOUT_ACTION_NOTIFICATION(" + TIMEOUT_ACTION_NOTIFICATION + "), "
                            + "TIMEOUT_ACTION_REMOVE(" + TIMEOUT_ACTION_REMOVE + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * CPUSETs to monitor.
     */
    @DataClass.Generated.Member
    public @Cpuset int getCpuset() {
        return mCpuset;
    }

    /**
     * CPU availability lower bound percent.
     *
     * <p>CPU availability change notifications are sent when the CPU availability reaches or
     * decreases below this value. The value of this field must be less than the value of
     * {@link #mUpperBoundPercent}.
     *
     * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
     * percent and lower bound percent must be reasonably large.
     *
     * <p>To ignore this field, specify {@link #IGNORE_PERCENT_LOWER_BOUND} as the value.
     * Must not ignore both this field and {@link #mUpperBoundPercent} in the same configuration.
     */
    @DataClass.Generated.Member
    public int getLowerBoundPercent() {
        return mLowerBoundPercent;
    }

    /**
     * CPU availability upper bound percent.
     *
     * <p>CPU availability change notifications are sent when the CPU availability reaches or
     * increases above this value. The value of this field must be greater than the value of
     * {@link #mLowerBoundPercent}.
     *
     * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
     * percent and lower bound percent must be reasonably large.
     *
     * <p>To ignore this field, specify {@link #IGNORE_PERCENT_UPPER_BOUND} as the value.
     * Must not ignore both this field and {@link #mLowerBoundPercent} in the same configuration.
     */
    @DataClass.Generated.Member
    public int getUpperBoundPercent() {
        return mUpperBoundPercent;
    }

    /**
     * CPU availability monitoring timeout in seconds.
     *
     * <p>To avoid timing out, specify {@link #MONITORING_TIMEOUT_NEVER} as the value.
     */
    @DataClass.Generated.Member
    public @SuppressLint({ "MethodNameUnits" }) long getTimeoutInSeconds() {
        return mTimeoutInSeconds;
    }

    /**
     * Action to take on timeout. Specify one of the {@code TIMEOUT_ACTION_*} constants.
     *
     * <p>When the value of {@link #mTimeoutInSeconds} is {@link #MONITORING_TIMEOUT_NEVER},
     * this field is ignored.
     */
    @DataClass.Generated.Member
    public @TimeoutAction int getTimeoutAction() {
        return mTimeoutAction;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "CpuAvailabilityMonitoringConfig { " +
                "cpuset = " + cpusetToString(mCpuset) + ", " +
                "lowerBoundPercent = " + mLowerBoundPercent + ", " +
                "upperBoundPercent = " + mUpperBoundPercent + ", " +
                "timeoutInSeconds = " + mTimeoutInSeconds + ", " +
                "timeoutAction = " + timeoutActionToString(mTimeoutAction) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mCpuset);
        dest.writeInt(mLowerBoundPercent);
        dest.writeInt(mUpperBoundPercent);
        dest.writeLong(mTimeoutInSeconds);
        dest.writeInt(mTimeoutAction);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ CpuAvailabilityMonitoringConfig(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int cpuset = in.readInt();
        int lowerBoundPercent = in.readInt();
        int upperBoundPercent = in.readInt();
        long timeoutInSeconds = in.readLong();
        int timeoutAction = in.readInt();

        this.mCpuset = cpuset;

        if (!(mCpuset == CPUSET_ALL)
                && !(mCpuset == CPUSET_BACKGROUND)) {
            throw new java.lang.IllegalArgumentException(
                    "cpuset was " + mCpuset + " but must be one of: "
                            + "CPUSET_ALL(" + CPUSET_ALL + "), "
                            + "CPUSET_BACKGROUND(" + CPUSET_BACKGROUND + ")");
        }

        this.mLowerBoundPercent = lowerBoundPercent;
        this.mUpperBoundPercent = upperBoundPercent;
        this.mTimeoutInSeconds = timeoutInSeconds;
        this.mTimeoutAction = timeoutAction;

        if (!(mTimeoutAction == TIMEOUT_ACTION_NOTIFICATION)
                && !(mTimeoutAction == TIMEOUT_ACTION_REMOVE)) {
            throw new java.lang.IllegalArgumentException(
                    "timeoutAction was " + mTimeoutAction + " but must be one of: "
                            + "TIMEOUT_ACTION_NOTIFICATION(" + TIMEOUT_ACTION_NOTIFICATION + "), "
                            + "TIMEOUT_ACTION_REMOVE(" + TIMEOUT_ACTION_REMOVE + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<CpuAvailabilityMonitoringConfig> CREATOR
            = new Parcelable.Creator<CpuAvailabilityMonitoringConfig>() {
        @Override
        public CpuAvailabilityMonitoringConfig[] newArray(int size) {
            return new CpuAvailabilityMonitoringConfig[size];
        }

        @Override
        public CpuAvailabilityMonitoringConfig createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new CpuAvailabilityMonitoringConfig(in);
        }
    };

    /**
     * A builder for {@link CpuAvailabilityMonitoringConfig}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private @Cpuset int mCpuset;
        private int mLowerBoundPercent;
        private int mUpperBoundPercent;
        private @SuppressLint({ "MethodNameUnits" }) long mTimeoutInSeconds;
        private @TimeoutAction int mTimeoutAction;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param lowerBoundPercent
         *   CPU availability lower bound percent.
         *
         *   <p>CPU availability change notifications are sent when the CPU availability reaches or
         *   decreases below this value. The value of this field must be less than the value of
         *   {@link #mUpperBoundPercent}.
         *
         *   <p>To avoid spurious or very frequent notifications, the delta between the upper bound
         *   percent and lower bound percent must be reasonably large.
         *
         *   <p>To ignore this field, specify {@link #IGNORE_PERCENT_LOWER_BOUND} as the value.
         *   Must not ignore both this field and {@link #mUpperBoundPercent} in the same configuration.
         * @param upperBoundPercent
         *   CPU availability upper bound percent.
         *
         *   <p>CPU availability change notifications are sent when the CPU availability reaches or
         *   increases above this value. The value of this field must be greater than the value of
         *   {@link #mLowerBoundPercent}.
         *
         *   <p>To avoid spurious or very frequent notifications, the delta between the upper bound
         *   percent and lower bound percent must be reasonably large.
         *
         *   <p>To ignore this field, specify {@link #IGNORE_PERCENT_UPPER_BOUND} as the value.
         *   Must not ignore both this field and {@link #mLowerBoundPercent} in the same configuration.
         * @param timeoutInSeconds
         *   CPU availability monitoring timeout in seconds.
         *
         *   <p>To avoid timing out, specify {@link #MONITORING_TIMEOUT_NEVER} as the value.
         */
        public Builder(
                int lowerBoundPercent,
                int upperBoundPercent,
                @SuppressLint({ "MethodNameUnits" }) long timeoutInSeconds) {
            mLowerBoundPercent = lowerBoundPercent;
            mUpperBoundPercent = upperBoundPercent;
            mTimeoutInSeconds = timeoutInSeconds;
        }

        /**
         * CPUSETs to monitor.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setCpuset(@Cpuset int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mCpuset = value;
            return this;
        }

        /**
         * CPU availability lower bound percent.
         *
         * <p>CPU availability change notifications are sent when the CPU availability reaches or
         * decreases below this value. The value of this field must be less than the value of
         * {@link #mUpperBoundPercent}.
         *
         * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
         * percent and lower bound percent must be reasonably large.
         *
         * <p>To ignore this field, specify {@link #IGNORE_PERCENT_LOWER_BOUND} as the value.
         * Must not ignore both this field and {@link #mUpperBoundPercent} in the same configuration.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setLowerBoundPercent(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mLowerBoundPercent = value;
            return this;
        }

        /**
         * CPU availability upper bound percent.
         *
         * <p>CPU availability change notifications are sent when the CPU availability reaches or
         * increases above this value. The value of this field must be greater than the value of
         * {@link #mLowerBoundPercent}.
         *
         * <p>To avoid spurious or very frequent notifications, the delta between the upper bound
         * percent and lower bound percent must be reasonably large.
         *
         * <p>To ignore this field, specify {@link #IGNORE_PERCENT_UPPER_BOUND} as the value.
         * Must not ignore both this field and {@link #mLowerBoundPercent} in the same configuration.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setUpperBoundPercent(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mUpperBoundPercent = value;
            return this;
        }

        /**
         * CPU availability monitoring timeout in seconds.
         *
         * <p>To avoid timing out, specify {@link #MONITORING_TIMEOUT_NEVER} as the value.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setTimeoutInSeconds(@SuppressLint({ "MethodNameUnits" }) long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mTimeoutInSeconds = value;
            return this;
        }

        /**
         * Action to take on timeout. Specify one of the {@code TIMEOUT_ACTION_*} constants.
         *
         * <p>When the value of {@link #mTimeoutInSeconds} is {@link #MONITORING_TIMEOUT_NEVER},
         * this field is ignored.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setTimeoutAction(@TimeoutAction int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mTimeoutAction = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @android.annotation.NonNull CpuAvailabilityMonitoringConfig build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mCpuset = CPUSET_ALL;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mTimeoutAction = TIMEOUT_ACTION_NOTIFICATION;
            }
            CpuAvailabilityMonitoringConfig o = new CpuAvailabilityMonitoringConfig(
                    mCpuset,
                    mLowerBoundPercent,
                    mUpperBoundPercent,
                    mTimeoutInSeconds,
                    mTimeoutAction);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x20) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1673057982096L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/os/CpuAvailabilityMonitoringConfig.java",
            inputSignatures = "public static final @android.car.os.CpuAvailabilityMonitoringConfig.Cpuset @android.car.annotation.AddedInOrBefore int CPUSET_ALL\npublic static final @android.car.os.CpuAvailabilityMonitoringConfig.Cpuset @android.car.annotation.AddedInOrBefore int CPUSET_BACKGROUND\npublic static final @android.car.os.CpuAvailabilityMonitoringConfig.IgnorePercent @android.car.annotation.AddedInOrBefore int IGNORE_PERCENT_LOWER_BOUND\npublic static final @android.car.os.CpuAvailabilityMonitoringConfig.IgnorePercent @android.car.annotation.AddedInOrBefore int IGNORE_PERCENT_UPPER_BOUND\npublic static final @android.car.annotation.AddedInOrBefore int MONITORING_TIMEOUT_NEVER\npublic static final @android.car.os.CpuAvailabilityMonitoringConfig.TimeoutAction @android.car.annotation.AddedInOrBefore int TIMEOUT_ACTION_NOTIFICATION\npublic static final @android.car.os.CpuAvailabilityMonitoringConfig.TimeoutAction @android.car.annotation.AddedInOrBefore int TIMEOUT_ACTION_REMOVE\nprivate @android.car.os.CpuAvailabilityMonitoringConfig.Cpuset int mCpuset\nprivate  int mLowerBoundPercent\nprivate  int mUpperBoundPercent\nprivate @android.annotation.SuppressLint long mTimeoutInSeconds\nprivate @android.car.os.CpuAvailabilityMonitoringConfig.TimeoutAction int mTimeoutAction\nclass CpuAvailabilityMonitoringConfig extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genToString=true, genBuilder=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}

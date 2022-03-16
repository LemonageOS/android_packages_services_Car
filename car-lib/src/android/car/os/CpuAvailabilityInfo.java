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

import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ExperimentalFeature;
import android.os.Parcelable;

import com.android.car.internal.util.DataClass;

/**
 * CPU availability information.
 *
 * @hide
 */
@ExperimentalFeature
@DataClass(genToString = true, genHiddenBuilder = true)
public final class CpuAvailabilityInfo implements Parcelable {
    /** Returns the CPUSET, whose availability info is recorded in this object.
     *
     * <p>The returned CPUSET value is one of the CPUSET_* constants from
     * {@link CpuAvailabilityMonitoringConfig}
     */
    private int mCpuset;

    /** Returns the current average CPU availability percent. */
    private int mAverageAvailabilityPercent;

    /** Returns true, when the listener has timed out. Otherwise, returns false. */
    private boolean mTimeout = false;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/os/CpuAvailabilityInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ CpuAvailabilityInfo(
            int cpuset,
            int averageAvailabilityPercent,
            boolean timeout) {
        this.mCpuset = cpuset;
        this.mAverageAvailabilityPercent = averageAvailabilityPercent;
        this.mTimeout = timeout;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Returns the CPUSET, whose availability info is recorded in this object.
     *
     * <p>The returned CPUSET value is one of the CPUSET_* constants from
     * {@link CpuAvailabilityMonitoringConfig}
     */
    @DataClass.Generated.Member
    @AddedInOrBefore(majorVersion = 33)
    public int getCpuset() {
        return mCpuset;
    }

    /**
     * Returns the current average CPU availability percent.
     */
    @DataClass.Generated.Member
    @AddedInOrBefore(majorVersion = 33)
    public int getAverageAvailabilityPercent() {
        return mAverageAvailabilityPercent;
    }

    /**
     * Returns true, when the listener has timed out. Otherwise, returns false.
     */
    @DataClass.Generated.Member
    @AddedInOrBefore(majorVersion = 33)
    public boolean isTimeout() {
        return mTimeout;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "CpuAvailabilityInfo { " +
                "cpuset = " + mCpuset + ", " +
                "averageAvailabilityPercent = " + mAverageAvailabilityPercent + ", " +
                "timeout = " + mTimeout +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    @AddedInOrBefore(majorVersion = 33)
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mTimeout) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeInt(mCpuset);
        dest.writeInt(mAverageAvailabilityPercent);
    }

    @Override
    @DataClass.Generated.Member
    @AddedInOrBefore(majorVersion = 33)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ CpuAvailabilityInfo(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean timeout = (flg & 0x4) != 0;
        int cpuset = in.readInt();
        int averageAvailabilityPercent = in.readInt();

        this.mCpuset = cpuset;
        this.mAverageAvailabilityPercent = averageAvailabilityPercent;
        this.mTimeout = timeout;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<CpuAvailabilityInfo> CREATOR
            = new Parcelable.Creator<CpuAvailabilityInfo>() {
        @Override
        public CpuAvailabilityInfo[] newArray(int size) {
            return new CpuAvailabilityInfo[size];
        }

        @Override
        public CpuAvailabilityInfo createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new CpuAvailabilityInfo(in);
        }
    };

    /**
     * A builder for {@link CpuAvailabilityInfo}
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private int mCpuset;
        private int mAverageAvailabilityPercent;
        private boolean mTimeout;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param cpuset
         *   Returns the CPUSET, whose availability info is recorded in this object.
         *
         *   <p>The returned CPUSET value is one of the CPUSET_* constants from
         *   {@link CpuAvailabilityMonitoringConfig}
         * @param averageAvailabilityPercent
         *   Returns the current average CPU availability percent.
         */
        public Builder(
                int cpuset,
                int averageAvailabilityPercent) {
            mCpuset = cpuset;
            mAverageAvailabilityPercent = averageAvailabilityPercent;
        }

        /**
         * Returns the CPUSET, whose availability info is recorded in this object.
         *
         * <p>The returned CPUSET value is one of the CPUSET_* constants from
         * {@link CpuAvailabilityMonitoringConfig}
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setCpuset(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mCpuset = value;
            return this;
        }

        /**
         * Returns the current average CPU availability percent.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setAverageAvailabilityPercent(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mAverageAvailabilityPercent = value;
            return this;
        }

        /**
         * Returns true, when the listener has timed out. Otherwise, returns false.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull Builder setTimeout(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mTimeout = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @android.annotation.NonNull CpuAvailabilityInfo build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8; // Mark builder used

            if ((mBuilderFieldsSet & 0x4) == 0) {
                mTimeout = false;
            }
            CpuAvailabilityInfo o = new CpuAvailabilityInfo(
                    mCpuset,
                    mAverageAvailabilityPercent,
                    mTimeout);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x8) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1644001449539L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/os/CpuAvailabilityInfo.java",
            inputSignatures = "private  int mCpuset\nprivate  int mAverageAvailabilityPercent\nprivate  boolean mTimeout\nclass CpuAvailabilityInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genToString=true, genHiddenBuilder=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}

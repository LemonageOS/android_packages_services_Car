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

package android.car.user;

import android.annotation.IntDef;
import android.os.Parcelable;

import com.android.car.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * User remove result.
 *
 * @hide
 */
@DataClass(
        genToString = true,
        genHiddenConstructor = true,
        genHiddenConstDefs = true)
public final class UserStopResult implements Parcelable, OperationResult {

    /**
     * When user stop is successful.
     */
    public static final int STATUS_SUCCESSFUL = CommonResults.STATUS_SUCCESSFUL;

    /**
     * When user stop fails.
     */
    public static final int STATUS_ANDROID_FAILURE = CommonResults.STATUS_ANDROID_FAILURE;

     /**
     * When user to stop doesn't exits.
     */
    public static final int STATUS_USER_DOES_NOT_EXIST = CommonResults.LAST_COMMON_STATUS + 1;

    /**
     * When user to stop is the system user.
     */
    public static final int STATUS_FAILURE_SYSTEM_USER = CommonResults.LAST_COMMON_STATUS + 2;

    /**
     * When user to stop is the current user.
     */
    public static final int STATUS_FAILURE_CURRENT_USER = CommonResults.LAST_COMMON_STATUS + 3;

     /**
     * Gets the user switch result status.
     *
     * @return either {@link UserStopResult#STATUS_SUCCESSFUL},
     * {@link UserStopResult#STATUS_ANDROID_FAILURE},
     * {@link UserStopResult#STATUS_USER_DOES_NOT_EXIST},
     * {@link UserStopResult#STATUS_FAILURE_SYSTEM_USER}, or
     * {@link UserStopResult#STATUS_FAILURE_CURRENT_USER}.
     */
    private final @Status int mStatus;

    /**
     * Checks if the {@code status} represents a success status.
     *
     * @param status to check
     * @return true for a success status
     */
    public static boolean isSuccess(@Status int status) {
        return status == STATUS_SUCCESSFUL;
    }

    @Override
    public boolean isSuccess() {
        return isSuccess(mStatus);
    }


    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/user/UserStopResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(prefix = "STATUS_", value = {
        STATUS_SUCCESSFUL,
        STATUS_ANDROID_FAILURE,
        STATUS_USER_DOES_NOT_EXIST,
        STATUS_FAILURE_SYSTEM_USER,
        STATUS_FAILURE_CURRENT_USER
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Status {}

    /** @hide */
    @DataClass.Generated.Member
    public static String statusToString(@Status int value) {
        switch (value) {
            case STATUS_SUCCESSFUL:
                    return "STATUS_SUCCESSFUL";
            case STATUS_ANDROID_FAILURE:
                    return "STATUS_ANDROID_FAILURE";
            case STATUS_USER_DOES_NOT_EXIST:
                    return "STATUS_USER_DOES_NOT_EXIST";
            case STATUS_FAILURE_SYSTEM_USER:
                    return "STATUS_FAILURE_SYSTEM_USER";
            case STATUS_FAILURE_CURRENT_USER:
                    return "STATUS_FAILURE_CURRENT_USER";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new UserStopResult.
     *
     * @param status
     *   Gets the user switch result status.
     *
     *   @return either {@link UserStopResult#STATUS_SUCCESSFUL},
     *   {@link UserStopResult#STATUS_ANDROID_FAILURE},
     *   {@link UserStopResult#STATUS_USER_DOES_NOT_EXIST},
     *   {@link UserStopResult#STATUS_FAILURE_SYSTEM_USER}, or
     *   {@link UserStopResult#STATUS_FAILURE_CURRENT_USER}.
     * @hide
     */
    @DataClass.Generated.Member
    public UserStopResult(
            @Status int status) {
        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)
                && !(mStatus == STATUS_FAILURE_SYSTEM_USER)
                && !(mStatus == STATUS_FAILURE_CURRENT_USER)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + "), "
                            + "STATUS_FAILURE_SYSTEM_USER(" + STATUS_FAILURE_SYSTEM_USER + "), "
                            + "STATUS_FAILURE_CURRENT_USER(" + STATUS_FAILURE_CURRENT_USER + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Gets the user switch result status.
     *
     * @return either {@link UserStopResult#STATUS_SUCCESSFUL},
     * {@link UserStopResult#STATUS_ANDROID_FAILURE},
     * {@link UserStopResult#STATUS_USER_DOES_NOT_EXIST},
     * {@link UserStopResult#STATUS_FAILURE_SYSTEM_USER}, or
     * {@link UserStopResult#STATUS_FAILURE_CURRENT_USER}.
     */
    @DataClass.Generated.Member
    public @Status int getStatus() {
        return mStatus;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "UserStopResult { " +
                "status = " + statusToString(mStatus) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mStatus);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UserStopResult(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int status = in.readInt();

        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)
                && !(mStatus == STATUS_FAILURE_SYSTEM_USER)
                && !(mStatus == STATUS_FAILURE_CURRENT_USER)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + "), "
                            + "STATUS_FAILURE_SYSTEM_USER(" + STATUS_FAILURE_SYSTEM_USER + "), "
                            + "STATUS_FAILURE_CURRENT_USER(" + STATUS_FAILURE_CURRENT_USER + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<UserStopResult> CREATOR
            = new Parcelable.Creator<UserStopResult>() {
        @Override
        public UserStopResult[] newArray(int size) {
            return new UserStopResult[size];
        }

        @Override
        public UserStopResult createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new UserStopResult(in);
        }
    };

    @DataClass.Generated(
            time = 1619209981496L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/user/UserStopResult.java",
            inputSignatures = "public static final  int STATUS_SUCCESSFUL\npublic static final  int STATUS_ANDROID_FAILURE\npublic static final  int STATUS_USER_DOES_NOT_EXIST\npublic static final  int STATUS_FAILURE_SYSTEM_USER\npublic static final  int STATUS_FAILURE_CURRENT_USER\nprivate final @android.car.user.UserStopResult.Status int mStatus\npublic static  boolean isSuccess(int)\npublic @java.lang.Override boolean isSuccess()\nclass UserStopResult extends java.lang.Object implements [android.os.Parcelable, android.car.user.OperationResult]\n@com.android.car.internal.util.DataClass(genToString=true, genHiddenConstructor=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}

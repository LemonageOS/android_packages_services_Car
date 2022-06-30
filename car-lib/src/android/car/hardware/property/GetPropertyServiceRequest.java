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

package android.car.hardware.property;

import android.os.Parcelable;

import com.android.car.internal.util.DataClass;

/**
 * A request for {@link CarPropertyService.getPropertiesAsync}
 */
@DataClass(genConstructor = false)
public final class GetPropertyServiceRequest implements Parcelable {
    private final int mRequestId;
    private final int mPropId;
    private final int mAreaId;

    /**
     * Get an instance for GetPropertyServiceRequest.
     */
    public GetPropertyServiceRequest(int requestId, int propId, int areaId) {
        mRequestId = requestId;
        mPropId = propId;
        mAreaId = areaId;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/hardware/property/GetPropertyServiceRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public int getRequestId() {
        return mRequestId;
    }

    @DataClass.Generated.Member
    public int getPropId() {
        return mPropId;
    }

    @DataClass.Generated.Member
    public int getAreaId() {
        return mAreaId;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mRequestId);
        dest.writeInt(mPropId);
        dest.writeInt(mAreaId);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ GetPropertyServiceRequest(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int requestId = in.readInt();
        int propId = in.readInt();
        int areaId = in.readInt();

        this.mRequestId = requestId;
        this.mPropId = propId;
        this.mAreaId = areaId;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<GetPropertyServiceRequest> CREATOR
            = new Parcelable.Creator<GetPropertyServiceRequest>() {
        @Override
        public GetPropertyServiceRequest[] newArray(int size) {
            return new GetPropertyServiceRequest[size];
        }

        @Override
        public GetPropertyServiceRequest createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new GetPropertyServiceRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1657577424304L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/hardware/property/GetPropertyServiceRequest.java",
            inputSignatures = "private final  int mRequestId\nprivate final  int mPropId\nprivate final  int mAreaId\nclass GetPropertyServiceRequest extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genConstructor=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}

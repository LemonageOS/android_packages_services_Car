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

#ifndef CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_LARGEPARCELABLEBASE_H_
#define CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_LARGEPARCELABLEBASE_H_

#include "SharedMemory.h"

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <android/binder_auto_utils.h>
#include <android/binder_parcel.h>
#include <android/binder_parcel_utils.h>
#include <android/binder_status.h>
#include <utils/Log.h>

#include <memory>
#include <optional>

namespace android {
namespace automotive {
namespace car_binder_lib {

// Base class to allow passing a 'Parcelable' over binder directly or through shared memory if
// payload size exceeds MAX_DIRECT_PAYLOAD_SIZE.
//
// <p>Child class should inherit this to use this or use 'LargeParcelable' class.
//
// <p>Parcelized data will have following elements
// <ul>
// <li>@Nullable Parcelable
// <li>@Nullable SharedMemory which includes serialized Parcelable if non-null. This will be set
// only when the previous Parcelable is null or this also can be null for no data case.
// </ul>
//
// @hide
class LargeParcelableBase {
public:
    // Payload size bigger than this value will be passed over shared memory.
    static constexpr int32_t MAX_DIRECT_PAYLOAD_SIZE = 4096;

    LargeParcelableBase() = default;

    virtual ~LargeParcelableBase() = default;

    // Initialize this parcelable with input parcel.
    binder_status_t readFromParcel(const AParcel* parcel);

    // Write the owned parcelable object to the given |parcel|.
    binder_status_t writeToParcel(AParcel* parcel) const;

    // Write the input parcelable into a shared memory file that could be passed across binder if
    // the parcel generated by 'in' is larger than MAX_DIRECT_PAYLOAD_SIZE.
    // Returns error if input could not be serialized.
    // Returns a {@Code ScopedFileDescriptor} object if the input has been serialized to
    // the returned shared memory file.
    // Returns nullptr if the input is small enough and could be directly sent through binder.
    template <class T>
    static ::android::base::Result<std::unique_ptr<::ndk::ScopedFileDescriptor>>
    parcelableToStableLargeParcelable(const T& in) {
        std::unique_ptr<::ndk::ScopedFileDescriptor> sharedMemoryFd =
                std::make_unique<::ndk::ScopedFileDescriptor>();
        ::ndk::ScopedAParcel parcel(AParcel_create());

        if (binder_status_t status = ::ndk::AParcel_writeParcelable(parcel.get(), in);
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to write parcelable to parcel";
        }
        int32_t payloadSize = AParcel_getDataPosition(parcel.get());
        bool noSharedMemory = (payloadSize <= MAX_DIRECT_PAYLOAD_SIZE);
        if (noSharedMemory) {
            return nullptr;
        }
        if (binder_status_t status = parcelToMemoryFile(*parcel.get(), sharedMemoryFd.get());
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to write parcel as shared memory file";
        }
        return sharedMemoryFd;
    }

    // Write the input parcelable vector into a shared memory file that could be passed across
    // binder if the parcel generated by 'in' is larger than MAX_DIRECT_PAYLOAD_SIZE.
    // Returns error if input could not be serialized.
    // Returns a {@Code ScopedFileDescriptor} object if the input has been serialized to
    // the returned shared memory file.
    // Returns nullptr if the input is small enough and could be directly sent through binder.
    template <class T>
    static ::android::base::Result<std::unique_ptr<::ndk::ScopedFileDescriptor>>
    parcelableVectorToStableLargeParcelable(const std::vector<T>& in) {
        std::unique_ptr<::ndk::ScopedFileDescriptor> sharedMemoryFd =
                std::make_unique<::ndk::ScopedFileDescriptor>();
        ::ndk::ScopedAParcel parcel(AParcel_create());
        if (binder_status_t status = ::ndk::AParcel_writeVector(parcel.get(), in);
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to write parcelable to parcel";
        }
        int32_t payloadSize = AParcel_getDataPosition(parcel.get());
        bool noSharedMemory = (payloadSize <= MAX_DIRECT_PAYLOAD_SIZE);
        if (noSharedMemory) {
            return nullptr;
        }
        if (binder_status_t status = parcelToMemoryFile(*parcel.get(), sharedMemoryFd.get());
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to write parcel as shared memory file";
        }
        return sharedMemoryFd;
    }

    // Turns the payload and shared memory FD from a largeParcelabe received through binder into a
    // regular parcelable 'out' if the parcelable was passed through shared memory file.
    // This is the opposite operation for 'parcelableToStableLargeParcelable'.
    // Returns error if shared memory file could not be deserialized.
    // Returns a new T if sharedMemoryFd is valid and its content has been deserialized to it.
    // Returns a nullptr if the sharedMemoryFd is not valid and the parcelable is passed through
    // payload. Caller should directly use the payload in the parcelable passed through binder.
    template <class T>
    static ::android::base::Result<std::unique_ptr<T>> stableLargeParcelableToParcelable(
            const ::ndk::ScopedFileDescriptor& sharedMemoryFd) {
        if (sharedMemoryFd.get() == INVALID_MEMORY_FD) {
            return nullptr;
        }

        ::ndk::ScopedAParcel parcel(AParcel_create());
        if (binder_status_t status = getParcelFromMemoryFile(sharedMemoryFd, parcel.get());
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to get parcel from memory file";
        }

        std::unique_ptr<T> out = std::make_unique<T>();
        if (binder_status_t status = ::ndk::AParcel_readParcelable(parcel.get(), out.get());
            status != STATUS_OK) {
            return ::android::base::Error(status)
                    << "failed to read from parcel from shared memory";
        }
        return out;
    }

    // Turns the payload and shared memory FD from a largeParcelabe received through binder into a
    // regular parcelable 'out' if the parcelable was passed through shared memory file.
    // This is the opposite operation for 'parcelableVectorToStableLargeParcelable'.
    // Returns error if shared memory file could not be deserialized.
    // Returns a vector of T if sharedMemoryFd is valid and its content has been deserialized to it.
    // Returns a nullopt if the sharedMemoryFd is not valid and the parcelable is passed through
    // payload. Caller should directly use the payload in the parcelable passed through binder.
    template <class T>
    static ::android::base::Result<std::optional<std::vector<T>>>
    stableLargeParcelableToParcelableVector(const ::ndk::ScopedFileDescriptor& sharedMemoryFd) {
        if (sharedMemoryFd.get() == INVALID_MEMORY_FD) {
            return std::nullopt;
        }

        ::ndk::ScopedAParcel parcel(AParcel_create());
        if (binder_status_t status = getParcelFromMemoryFile(sharedMemoryFd, parcel.get());
            status != STATUS_OK) {
            return ::android::base::Error(status) << "failed to get parcel from memory file";
        }

        std::vector<T> out;
        if (binder_status_t status = ::ndk::AParcel_readVector(parcel.get(), &out);
            status != STATUS_OK) {
            return ::android::base::Error(status)
                    << "failed to read from parcel from shared memory";
        }

        return out;
    }

protected:
    static constexpr bool DBG_PAYLOAD = false;
    static constexpr size_t DBG_DUMP_LENGTH = 64;

    // Whether this object contains a valid parcelable from a previous successful
    // {@Code readFromParcel} call. Subclass must use this function to check before returning
    // the deserialized parcelable object.
    bool hasDeserializedParcelable() const;

    // Serialize (=write Parcelable into given Parcel) a {@code Parcelable} child class wants to
    // pass over binder call.
    virtual binder_status_t serialize(AParcel* dest) const = 0;

    // Serialize null payload to the given {@code Parcel}. For {@code Parcelable}, this can be
    // simply {@code dest.writeParcelable(null)} but non-Parcelable should have other way to
    // mark that there is no payload.
    virtual binder_status_t serializeNullPayload(AParcel* dest) const = 0;

    // Read a {@code Parcelable} from the given {@code Parcel}.
    virtual binder_status_t deserialize(const AParcel& src) = 0;

    static std::unique_ptr<SharedMemory> serializeParcelToSharedMemory(const AParcel& p,
                                                                       int32_t start, int32_t size,
                                                                       binder_status_t* outStatus);

    static binder_status_t copyFromSharedMemory(const SharedMemory& sharedMemory, AParcel* parcel);

    binder_status_t deserializeSharedMemoryAndClose(::android::base::unique_fd memoryFd);

    static binder_status_t getParcelFromMemoryFile(const ::ndk::ScopedFileDescriptor& fd,
                                                   AParcel* parcel);

    static binder_status_t parcelToMemoryFile(const AParcel& parcel,
                                              ::ndk::ScopedFileDescriptor* sharedMemoryFd);

private:
    static constexpr int32_t NULL_PAYLOAD = 0;
    static constexpr int32_t NONNULL_PAYLOAD = 1;
    static constexpr int32_t FD_HEADER = 0;
    static constexpr int32_t INVALID_MEMORY_FD = -1;

    mutable std::optional<bool> mNeedSharedMemory = std::nullopt;
    mutable std::unique_ptr<SharedMemory> mSharedMemory;

    // Whether the contained parcelable is valid.
    bool mHasDeserializedParcelable = false;

    // Write shared memory in compatible way with ParcelFileDescriptor
    static binder_status_t writeSharedMemoryCompatibleToParcel(const SharedMemory* sharedMemory,
                                                               AParcel* dest);
    static int32_t updatePayloadSize(AParcel* dest, int32_t startPosition);

    // Turns a ::ndk::ScopedFileDescriptor into a borrowed file descriptor.
    static ::android::base::borrowed_fd scopedFdToBorrowedFd(const ::ndk::ScopedFileDescriptor& fd);

    // Turns a ::ndk::ScopedFileDescriptor into a borrowed file descriptor. The
    // ::ndk::ScopedFileDescriptor would lose the ownership to the underlying file descriptor.
    static ::android::base::unique_fd scopeFdToUniqueFd(::ndk::ScopedFileDescriptor&& fd);

    // Create a shared memory file containing the marshalled parcelable so that it could be used
    // in writeToParcel.
    binder_status_t prepareSharedMemory(AParcel* fd) const;
};

}  // namespace car_binder_lib
}  // namespace automotive
}  // namespace android

#endif  // CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_LARGEPARCELABLEBASE_H_

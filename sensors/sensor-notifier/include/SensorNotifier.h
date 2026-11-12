/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <android/frameworks/sensorservice/1.0/ISensorManager.h>
#include <thread>

using android::sp;
using android::frameworks::sensorservice::V1_0::IEventQueue;
using android::frameworks::sensorservice::V1_0::IEventQueueCallback;
using android::frameworks::sensorservice::V1_0::ISensorManager;
using android::frameworks::sensorservice::V1_0::Result;

class SensorNotifier {
  public:
    SensorNotifier(sp<ISensorManager> manager);
    virtual ~SensorNotifier();

    void activate();
    void deactivate();

  protected:
    Result initializeSensorQueue(std::string typeAsString, bool wakeup, sp<IEventQueueCallback>);
    virtual void notify() = 0;
    // The sensor service restarts with system_server and leaves our queue dead; reading a failed
    // HIDL Return aborts, so exit instead and let init start us with a fresh connection.
    static Result checkQueueCall(const android::hardware::Return<Result>& ret);

    sp<IEventQueue> mQueue;
    int32_t mSensorHandle = -1;
    bool mActive = false;

  private:
    sp<ISensorManager> mManager;
    std::thread mThread;
};

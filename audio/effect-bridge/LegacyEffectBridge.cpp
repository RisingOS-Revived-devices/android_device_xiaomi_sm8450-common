/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * Runs legacy (HIDL era, AUDIO_EFFECT_LIBRARY_INFO_SYM) effect libraries inside the AIDL
 * effect HAL. Dolby, MiSound and the Dolby spatializer only ship as legacy libraries, and the
 * AIDL effect factory only loads libraries that export createEffect/queryEffect.
 *
 * Parameters pass through untouched: for effect types the framework does not know it sends
 * the whole effect_param_t as a VendorExtension (AidlConversionVendorExtension), and for the
 * spatializer it does the same because this descriptor advertises no spatializer capability.
 */

#define LOG_TAG "AHAL_LegacyEffectBridge"

#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <android-base/logging.h>
#include <android-base/scopeguard.h>
#include <audio_utils/channels.h>
#include <audio_utils/primitives.h>
#include <hardware/audio_effect.h>
#include <media/AidlConversionCppNdk.h>
#include <media/AidlConversionEffect.h>
#include <media/AidlConversionNdk.h>
#include <system/audio.h>

#include "effect-impl/EffectImpl.h"

using aidl::android::hardware::audio::effect::DefaultExtension;
using aidl::android::hardware::audio::effect::Descriptor;
using aidl::android::hardware::audio::effect::IEffect;
using aidl::android::media::audio::common::AudioUuid;

namespace aidl::android::hardware::audio::effect {

namespace {

struct LegacyEffect {
    const char* uuid;   // implementation UUID, the same as the legacy one
    const char* type;   // type UUID, used when the legacy library is missing
    std::vector<const char*> libs;  // candidates under /vendor/lib64/soundfx
};

const std::vector<LegacyEffect> kLegacyEffects = {
        // MiSound
        {"5b8e36a5-144a-4c38-b1d7-0002a5d5c51b", "f46b26a0-dddd-11db-8afd-0002a5d5c51b",
         {"libmisoundfx.so"}},
        // Dolby DAP (LunarisDolby ships libswdap, the DAX flavour libhwdap)
        {"9d4921da-8225-4f29-aefa-39537a04bcaa", "46d279d9-9be7-453d-9d7c-ef937f675587",
         {"libswdap.so", "libhwdap.so"}},
        // Dolby volume listeners
        {"40f66c8b-5aa5-4345-8919-53ec431aaa98", "40f66c8b-5aa5-4345-8919-53ec431aaa98",
         {"libdlbvol.so"}},
        {"21d14087-558a-4f21-94a9-5002dce64bce", "21d14087-558a-4f21-94a9-5002dce64bce",
         {"libdlbvol.so"}},
        {"6aff229c-30c6-4cc8-9957-dbfe5c1bd7f6", "6aff229c-30c6-4cc8-9957-dbfe5c1bd7f6",
         {"libdlbvol.so"}},
        {"874db4d8-051d-4b7b-bd95-a3bebc837e9e", "874db4d8-051d-4b7b-bd95-a3bebc837e9e",
         {"libdlbvol.so"}},
        {"1f0091e3-6ad8-40fe-9b09-5948f9a26e7e", "1f0091e3-6ad8-40fe-9b09-5948f9a26e7e",
         {"libdlbvol.so"}},
        // Dolby game DAP and VQE
        {"3783c334-d3a0-4d13-874f-0032e5fb80e2", "12ea851f-0e12-4bde-a5e5-71f5651e9f96",
         {"libswgamedap.so"}},
        {"64a0f614-7fa4-48b8-b081-d59dc954616f", "733fefa3-667e-44e4-a1a5-4a3d4c980f50",
         {"libswvqe.so"}},
        // Spatializers: LunarisDolby libspatialaudio, DAX libswspatializer
        {"cc4677de-ff72-11eb-9a03-0242ac130003", "ccd4cf09-a79d-46c2-9aae-06a1698d6c8f",
         {"libspatialaudio.so"}},
        {"ccd4cf09-a79d-46c2-9aae-06a1698d6c8f", "ccd4cf09-a79d-46c2-9aae-06a1698d6c8f",
         {"libswspatializer.so"}},
};

constexpr char kSoundFxDir[] = "/vendor/lib64/soundfx/";
// Value room for a GET_PARAM whose caller did not say how much it expects (spatializer path).
constexpr size_t kDefaultValueSize = 1024;

bool parseUuid(const char* str, AudioUuid* uuid) {
    unsigned int tl, tm, th, cs, n[6];
    if (sscanf(str, "%08x-%04x-%04x-%04x-%02x%02x%02x%02x%02x%02x", &tl, &tm, &th, &cs, &n[0],
               &n[1], &n[2], &n[3], &n[4], &n[5]) != 10) {
        return false;
    }
    uuid->timeLow = static_cast<int32_t>(tl);
    uuid->timeMid = static_cast<int32_t>(tm);
    uuid->timeHiAndVersion = static_cast<int32_t>(th);
    uuid->clockSeq = static_cast<int32_t>(cs);
    uuid->node.assign(n, n + 6);
    return true;
}

const LegacyEffect* findEffect(const AudioUuid& uuid) {
    for (const auto& effect : kLegacyEffects) {
        AudioUuid u;
        if (parseUuid(effect.uuid, &u) && u == uuid) return &effect;
    }
    return nullptr;
}

// Legacy libraries stay loaded for the life of the HAL, like in the HIDL effect factory.
audio_effect_library_t* openLibrary(const LegacyEffect& effect) {
    static std::mutex sMutex;
    static std::map<std::string, audio_effect_library_t*> sLibs;
    std::lock_guard lg(sMutex);

    for (const char* name : effect.libs) {
        std::string path = std::string(kSoundFxDir) + name;
        if (auto it = sLibs.find(path); it != sLibs.end()) return it->second;
        if (access(path.c_str(), R_OK) != 0) continue;

        void* handle = dlopen(path.c_str(), RTLD_NOW);
        if (!handle) {
            LOG(ERROR) << __func__ << " dlopen " << path << ": " << dlerror();
            continue;
        }
        auto lib = static_cast<audio_effect_library_t*>(
                dlsym(handle, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR));
        if (!lib || lib->tag != AUDIO_EFFECT_LIBRARY_TAG) {
            LOG(ERROR) << __func__ << " " << path << " is not a legacy effect library";
            dlclose(handle);
            continue;
        }
        sLibs[path] = lib;
        return lib;
    }
    return nullptr;
}

bool legacyUuid(const AudioUuid& aidl, effect_uuid_t* legacy) {
    auto ret = ::aidl::android::aidl2legacy_AudioUuid_audio_uuid_t(aidl);
    if (!ret.ok()) return false;
    *legacy = ret.value();
    return true;
}

bool queryDescriptor(const AudioUuid& uuid, Descriptor* desc) {
    const LegacyEffect* effect = findEffect(uuid);
    if (!effect) return false;

    effect_uuid_t legacy;
    effect_descriptor_t legacyDesc;
    audio_effect_library_t* lib = openLibrary(*effect);
    if (lib && legacyUuid(uuid, &legacy) && lib->get_descriptor(&legacy, &legacyDesc) == 0) {
        auto ret = ::aidl::android::legacy2aidl_effect_descriptor_Descriptor(legacyDesc);
        if (ret.ok()) {
            *desc = ret.value();
            return true;
        }
    }

    // The library is missing: still describe the effect so that one absent blob does not
    // fail queryEffects() for every effect the factory knows; createEffect() fails instead.
    LOG(WARNING) << __func__ << " no legacy library for " << effect->uuid;
    *desc = {};
    desc->common.id.uuid = uuid;
    parseUuid(effect->type, &desc->common.id.type);
    desc->common.name = "Unavailable legacy effect";
    desc->common.implementor = "LineageOS";
    return true;
}

class LegacyEffectBridge final : public EffectImpl {
  public:
    LegacyEffectBridge(const AudioUuid& uuid, const Descriptor& desc, audio_effect_library_t* lib)
        : mUuid(uuid), mDescriptor(desc), mLibrary(lib) {}

    ~LegacyEffectBridge() override {
        cleanUp();
        std::lock_guard lg(mImplMutex);
        releaseLegacy_l();
    }

    ndk::ScopedAStatus getDescriptor(Descriptor* desc) override {
        *desc = mDescriptor;
        return ndk::ScopedAStatus::ok();
    }

    std::string getEffectName() override { return mDescriptor.common.name; }

    std::shared_ptr<EffectContext> createContext(const Parameter::Common& common)
            REQUIRES(mImplMutex) override {
        if (mImplContext) return mImplContext;
        mImplContext = std::make_shared<EffectContext>(1 /* statusFmqDepth */, common);

        effect_uuid_t legacy;
        if (!legacyUuid(mUuid, &legacy) ||
            mLibrary->create_effect(&legacy, common.session, common.ioHandle, &mHandle) != 0 ||
            !mHandle) {
            LOG(ERROR) << __func__ << " create_effect failed for " << getEffectName();
            mHandle = nullptr;
            return mImplContext;
        }

        int32_t reply = 0;
        uint32_t replySize = sizeof(reply);
        (*mHandle)->command(mHandle, EFFECT_CMD_INIT, 0, nullptr, &replySize, &reply);
        configure_l(common);
        return mImplContext;
    }

    RetCode releaseContext() REQUIRES(mImplMutex) override {
        releaseLegacy_l();
        mImplContext.reset();
        return RetCode::SUCCESS;
    }

    ndk::ScopedAStatus setParameterCommon(const Parameter& param) REQUIRES(mImplMutex) override {
        RETURN_IF_ASTATUS_NOT_OK(EffectImpl::setParameterCommon(param), "setCommonFailed");
        if (!mHandle) return ndk::ScopedAStatus::ok();

        switch (param.getTag()) {
            case Parameter::common:
                configure_l(param.get<Parameter::common>());
                break;
            case Parameter::deviceDescription: {
                uint32_t devices = AUDIO_DEVICE_NONE;
                for (const auto& d : param.get<Parameter::deviceDescription>()) {
                    auto ret = ::aidl::android::aidl2legacy_AudioDeviceDescription_audio_devices_t(d);
                    if (ret.ok()) devices |= ret.value();
                }
                command_l(EFFECT_CMD_SET_DEVICE, sizeof(devices), &devices);
                break;
            }
            case Parameter::mode: {
                auto ret = ::aidl::android::aidl2legacy_AudioMode_audio_mode_t(
                        param.get<Parameter::mode>());
                if (ret.ok()) {
                    audio_mode_t mode = ret.value();
                    command_l(EFFECT_CMD_SET_AUDIO_MODE, sizeof(mode), &mode);
                }
                break;
            }
            case Parameter::source: {
                auto ret = ::aidl::android::aidl2legacy_AudioSource_audio_source_t(
                        param.get<Parameter::source>());
                if (ret.ok()) {
                    audio_source_t source = ret.value();
                    command_l(EFFECT_CMD_SET_AUDIO_SOURCE, sizeof(source), &source);
                }
                break;
            }
            case Parameter::volumeStereo: {
                // legacy volumes are 8.24 fixed point
                const auto& v = param.get<Parameter::volumeStereo>();
                uint32_t volume[2] = {static_cast<uint32_t>(v.left * (1 << 24)),
                                      static_cast<uint32_t>(v.right * (1 << 24))};
                uint32_t replySize = sizeof(volume);
                (*mHandle)->command(mHandle, EFFECT_CMD_SET_VOLUME, sizeof(volume), volume,
                                    &replySize, volume);
                break;
            }
            default:
                break;
        }
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setParameterSpecific(const Parameter::Specific& specific)
            REQUIRES(mImplMutex) override {
        std::vector<uint8_t> bytes;
        RETURN_IF(!extensionBytes(specific, &bytes), EX_ILLEGAL_ARGUMENT, "notVendorExtension");
        RETURN_IF(!mHandle, EX_ILLEGAL_STATE, "noLegacyEffect");
        RETURN_IF(!isEffectParam(bytes), EX_ILLEGAL_ARGUMENT, "badEffectParam");

        int32_t reply = 0;
        uint32_t replySize = sizeof(reply);
        int ret = (*mHandle)->command(mHandle, EFFECT_CMD_SET_PARAM, bytes.size(), bytes.data(),
                                      &replySize, &reply);
        RETURN_IF(ret != 0 || reply != 0, EX_ILLEGAL_ARGUMENT, "setParamFailed");
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus getParameterSpecific(const Parameter::Id& id, Parameter::Specific* specific)
            REQUIRES(mImplMutex) override {
        std::vector<uint8_t> bytes;
        RETURN_IF(!idExtensionBytes(id, &bytes), EX_ILLEGAL_ARGUMENT, "notVendorExtension");

        // The framework reads capabilities before open() (the spatializer engine config), so
        // answer those from a short-lived legacy instance instead of failing.
        effect_handle_t handle = mHandle;
        effect_handle_t probe = nullptr;
        if (!handle) {
            effect_uuid_t legacy;
            RETURN_IF(!legacyUuid(mUuid, &legacy) ||
                              mLibrary->create_effect(&legacy, AUDIO_SESSION_OUTPUT_STAGE,
                                                      AUDIO_IO_HANDLE_NONE, &probe) != 0 ||
                              !probe,
                      EX_ILLEGAL_STATE, "noLegacyEffect");
            int32_t initReply = 0;
            uint32_t initReplySize = sizeof(initReply);
            (*probe)->command(probe, EFFECT_CMD_INIT, 0, nullptr, &initReplySize, &initReply);
            handle = probe;
        }
        auto releaseProbe = ::android::base::make_scope_guard([&] {
            if (probe) mLibrary->release_effect(probe);
        });

        // Either a whole effect_param_t (vendor types) or the bare parameter (spatializer).
        std::vector<uint8_t> cmd;
        size_t valueSize;
        if (isEffectParam(bytes)) {
            const auto* p = reinterpret_cast<const effect_param_t*>(bytes.data());
            valueSize = p->vsize;
            cmd.assign(bytes.begin(), bytes.begin() + sizeof(effect_param_t) + paddedSize(p->psize));
        } else {
            valueSize = kDefaultValueSize;
            cmd.resize(sizeof(effect_param_t) + paddedSize(bytes.size()));
            auto* p = reinterpret_cast<effect_param_t*>(cmd.data());
            p->status = 0;
            p->psize = bytes.size();
            p->vsize = valueSize;
            memcpy(p->data, bytes.data(), bytes.size());
        }

        std::vector<uint8_t> reply(cmd.size() + valueSize);
        uint32_t replySize = reply.size();
        int ret = (*handle)->command(handle, EFFECT_CMD_GET_PARAM, cmd.size(), cmd.data(),
                                      &replySize, reply.data());
        RETURN_IF(ret != 0 || replySize < sizeof(effect_param_t), EX_ILLEGAL_ARGUMENT,
                  "getParamFailed");
        reply.resize(replySize);

        DefaultExtension ext;
        ext.bytes = std::move(reply);
        VendorExtension vendor;
        RETURN_IF(vendor.extension.setParcelable(ext) != STATUS_OK, EX_ILLEGAL_ARGUMENT,
                  "setParcelableFailed");
        if (id.getTag() == Parameter::Id::spatializerTag) {
            specific->set<Parameter::Specific::spatializer>(
                    Spatializer::make<Spatializer::vendor>(vendor));
        } else {
            specific->set<Parameter::Specific::vendorEffect>(vendor);
        }
        return ndk::ScopedAStatus::ok();
    }

    IEffect::Status effectProcessImpl(float* in, float* out, int samples)
            REQUIRES(mImplMutex) override {
        const size_t frames = mInChannels ? samples / mInChannels : 0;
        const size_t outSamples = frames * mOutChannels;
        if (!mHandle || !mEnabled || !frames) {
            // in == out under process(); keep the front channels when in/out counts differ
            if (frames) {
                adjust_channels(in, mInChannels, out, mOutChannels, sizeof(float),
                                frames * mInChannels * sizeof(float));
            }
            return {STATUS_OK, samples, static_cast<int32_t>(outSamples)};
        }

        audio_buffer_t inBuf = {.frameCount = frames};
        audio_buffer_t outBuf = {.frameCount = frames};
        if (mFloat) {
            mOut.resize(outSamples);
            inBuf.f32 = in;
            outBuf.f32 = mOut.data();
        } else {
            mIn16.resize(samples);
            mOut16.resize(outSamples);
            memcpy_to_i16_from_float(mIn16.data(), in, samples);
            inBuf.s16 = mIn16.data();
            outBuf.s16 = mOut16.data();
        }

        int ret = (*mHandle)->process(mHandle, &inBuf, &outBuf);
        if (ret != 0 && ret != -ENODATA) {
            LOG(WARNING) << __func__ << " " << getEffectName() << " process returned " << ret;
        }
        if (mFloat) {
            memcpy(out, mOut.data(), outSamples * sizeof(float));
        } else {
            memcpy_to_float_from_i16(out, mOut16.data(), outSamples);
        }
        return {STATUS_OK, samples, static_cast<int32_t>(outSamples)};
    }

    // EffectImpl::process() caps the read at the output FMQ space, so with more input than
    // output channels (spatializer) input is left behind and EffectHalAidl rejects the buffer.
    // Scale the output space like the AOSP downmix effect does.
    void process() override {
        uint32_t efState = 0;
        if (!mEventFlag ||
            mEventFlag->wait(mDataMqNotEmptyEf, &efState, 0 /* no timeout */, true /* retry */) !=
                    ::android::OK ||
            !(efState & mDataMqNotEmptyEf)) {
            return;
        }
        std::lock_guard lg(mImplMutex);
        if ((mState != State::PROCESSING && mState != State::DRAINING) || !mImplContext) return;
        auto statusMQ = mImplContext->getStatusFmq();
        auto inputMQ = mImplContext->getInputDataFmq();
        auto outputMQ = mImplContext->getOutputDataFmq();
        if (!inputMQ || !outputMQ) return;
        auto buffer = mImplContext->getWorkBuffer();
        const size_t samples = std::min<size_t>(
                inputMQ->availableToRead(),
                outputMQ->availableToWrite() * mImplContext->getInputFrameSize() /
                        mImplContext->getOutputFrameSize());
        if (!samples) {
            drainingComplete_l();
            return;
        }
        inputMQ->read(buffer, samples);
        IEffect::Status status = effectProcessImpl(buffer, buffer, samples);
        outputMQ->write(buffer, status.fmqProduced);
        statusMQ->writeBlocking(&status, 1);
    }

  protected:
    ndk::ScopedAStatus commandImpl(CommandId id) REQUIRES(mImplMutex) override {
        RETURN_IF_ASTATUS_NOT_OK(EffectImpl::commandImpl(id), "commandFailed");
        switch (id) {
            case CommandId::START:
                mEnabled = command_l(EFFECT_CMD_ENABLE, 0, nullptr);
                break;
            case CommandId::STOP:
                command_l(EFFECT_CMD_DISABLE, 0, nullptr);
                mEnabled = false;
                break;
            case CommandId::RESET:
                command_l(EFFECT_CMD_RESET, 0, nullptr);
                break;
            default:
                break;
        }
        return ndk::ScopedAStatus::ok();
    }

  private:
    static size_t paddedSize(size_t psize) {
        return ((psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
    }

    static bool isEffectParam(const std::vector<uint8_t>& bytes) {
        if (bytes.size() < sizeof(effect_param_t)) return false;
        const auto* p = reinterpret_cast<const effect_param_t*>(bytes.data());
        return p->psize > 0 && sizeof(effect_param_t) + paddedSize(p->psize) + p->vsize <=
                                       bytes.size();
    }

    static bool toBytes(const VendorExtension& vendor, std::vector<uint8_t>* bytes) {
        std::optional<DefaultExtension> ext;
        if (vendor.extension.getParcelable(&ext) != STATUS_OK || !ext.has_value()) return false;
        *bytes = ext->bytes;
        return true;
    }

    static bool extensionBytes(const Parameter::Specific& specific, std::vector<uint8_t>* bytes) {
        if (specific.getTag() == Parameter::Specific::vendorEffect) {
            return toBytes(specific.get<Parameter::Specific::vendorEffect>(), bytes);
        }
        if (specific.getTag() == Parameter::Specific::spatializer) {
            const auto& s = specific.get<Parameter::Specific::spatializer>();
            return s.getTag() == Spatializer::vendor && toBytes(s.get<Spatializer::vendor>(), bytes);
        }
        return false;
    }

    static bool idExtensionBytes(const Parameter::Id& id, std::vector<uint8_t>* bytes) {
        if (id.getTag() == Parameter::Id::vendorEffectTag) {
            return toBytes(id.get<Parameter::Id::vendorEffectTag>(), bytes);
        }
        if (id.getTag() == Parameter::Id::spatializerTag) {
            const auto& s = id.get<Parameter::Id::spatializerTag>();
            return s.getTag() == Spatializer::Id::vendorExtensionTag &&
                   toBytes(s.get<Spatializer::Id::vendorExtensionTag>(), bytes);
        }
        return false;
    }

    bool command_l(uint32_t cmd, uint32_t size, void* data) REQUIRES(mImplMutex) {
        int32_t reply = 0;
        uint32_t replySize = sizeof(reply);
        return mHandle && (*mHandle)->command(mHandle, cmd, size, data, &replySize, &reply) == 0 &&
               reply == 0;
    }

    // AudioFlinger tries float first and falls back to 16 bit; do the same here.
    void configure_l(const Parameter::Common& common) REQUIRES(mImplMutex) {
        auto in = ::aidl::android::aidl2legacy_AudioConfig_buffer_config_t(common.input, false);
        auto out = ::aidl::android::aidl2legacy_AudioConfig_buffer_config_t(common.output, false);
        if (!in.ok() || !out.ok()) {
            LOG(ERROR) << __func__ << " cannot convert " << common.toString();
            return;
        }

        effect_config_t config = {.inputCfg = in.value(), .outputCfg = out.value()};
        config.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
        config.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
        config.inputCfg.mask = config.outputCfg.mask =
                EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS | EFFECT_CONFIG_FORMAT |
                EFFECT_CONFIG_ACC_MODE;
        mInChannels = audio_channel_count_from_out_mask(config.inputCfg.channels);
        mOutChannels = audio_channel_count_from_out_mask(config.outputCfg.channels);

        for (audio_format_t format : {AUDIO_FORMAT_PCM_FLOAT, AUDIO_FORMAT_PCM_16_BIT}) {
            config.inputCfg.format = config.outputCfg.format = format;
            if (command_l(EFFECT_CMD_SET_CONFIG, sizeof(config), &config)) {
                mFloat = format == AUDIO_FORMAT_PCM_FLOAT;
                return;
            }
        }
        LOG(ERROR) << __func__ << " " << getEffectName() << " rejects float and 16 bit";
    }

    void releaseLegacy_l() REQUIRES(mImplMutex) {
        if (!mHandle) return;
        if (mEnabled) command_l(EFFECT_CMD_DISABLE, 0, nullptr);
        mEnabled = false;
        mLibrary->release_effect(mHandle);
        mHandle = nullptr;
    }

    const AudioUuid mUuid;
    const Descriptor mDescriptor;
    audio_effect_library_t* const mLibrary;
    effect_handle_t mHandle GUARDED_BY(mImplMutex) = nullptr;
    bool mEnabled GUARDED_BY(mImplMutex) = false;
    bool mFloat GUARDED_BY(mImplMutex) = true;
    size_t mInChannels GUARDED_BY(mImplMutex) = 2;
    size_t mOutChannels GUARDED_BY(mImplMutex) = 2;
    std::vector<float> mOut GUARDED_BY(mImplMutex);
    std::vector<int16_t> mIn16 GUARDED_BY(mImplMutex);
    std::vector<int16_t> mOut16 GUARDED_BY(mImplMutex);
};

}  // namespace

}  // namespace aidl::android::hardware::audio::effect

using aidl::android::hardware::audio::effect::LegacyEffectBridge;
using aidl::android::hardware::audio::effect::openLibrary;
using aidl::android::hardware::audio::effect::findEffect;
using aidl::android::hardware::audio::effect::queryDescriptor;

extern "C" binder_exception_t createEffect(const AudioUuid* uuid,
                                           std::shared_ptr<IEffect>* instance) {
    Descriptor desc;
    if (!uuid || !instance || !queryDescriptor(*uuid, &desc)) return EX_ILLEGAL_ARGUMENT;

    audio_effect_library_t* lib = openLibrary(*findEffect(*uuid));
    if (!lib) return EX_ILLEGAL_ARGUMENT;

    *instance = ndk::SharedRefBase::make<LegacyEffectBridge>(*uuid, desc, lib);
    return EX_NONE;
}

extern "C" binder_exception_t queryEffect(const AudioUuid* uuid, Descriptor* desc) {
    if (!uuid || !desc || !queryDescriptor(*uuid, desc)) return EX_ILLEGAL_ARGUMENT;
    return EX_NONE;
}

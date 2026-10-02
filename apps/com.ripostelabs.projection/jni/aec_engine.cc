/* AEC3 behind the plain C face of aec_engine.h; see there for the contract. */
#include "aec_engine.h"

#include <memory>
#include <new>
#include <optional>

#include "api/audio/echo_canceller3_config.h"
#include "api/audio/echo_control.h"
#include "api/scoped_refptr.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"
#include "modules/audio_processing/include/audio_processing.h"

/* The largest downlink frame accepted: 10 ms of 48 kHz stereo. */
static const int MAX_RENDER_RATE = 48000;
static const int MAX_RENDER_CHANNELS = 2;

/*
 * AEC3 tuned for the car, from the offline sweep (share/carlauncher/mic-ns/aec, README):
 *
 *   erle.max_l / max_h    4 / 1.5 → 16 / 6: AEC3 caps how much echo it believes its linear
 *                         filter removes, then suppresses the rest as if it were there. In a
 *                         cabin the filter does better than the cap, so the default ducked the
 *                         owner 2 to 3 dB more under the far end for no echo removed.
 *   dominant near end     enr 0.25 → 1, snr 30 → 10 dB: road noise keeps the owner's voice
 *                         under 30 dB SNR, so the default never saw a near end dominate.
 */
static const float CAR_ERLE_MAX_LF = 16.f;
static const float CAR_ERLE_MAX_HF = 6.f;
static const float CAR_NEAREND_ENR = 1.f;
static const float CAR_NEAREND_SNR_DB = 10.f;

/* The APM asks a factory for its echo canceller; this one hands out AEC3 with the car tuning. */
struct CarAec3Factory : webrtc::EchoControlFactory {
    std::unique_ptr<webrtc::EchoControl> Create(int rate, int render_channels, int capture_channels) override {
        webrtc::EchoCanceller3Config c;
        c.erle.max_l = CAR_ERLE_MAX_LF;
        c.erle.max_h = CAR_ERLE_MAX_HF;
        c.suppressor.dominant_nearend_detection.enr_threshold = CAR_NEAREND_ENR;
        c.suppressor.dominant_nearend_detection.snr_threshold = CAR_NEAREND_SNR_DB;
        return std::make_unique<webrtc::EchoCanceller3>(c, std::nullopt, rate, render_channels, capture_channels);
    }
};

struct AecEngine {
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig mic;
};

AecEngine *aec_open(int capture_rate) {
    AecEngine *aec = new (std::nothrow) AecEngine();
    if (aec == nullptr) {
        return nullptr;
    }

    // AEC3 (mobile_mode false), with the high-pass it is tuned against. Everything else off:
    // RNNoise suppresses noise after this, and gain control would pump the road noise.
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false;
    config.high_pass_filter.enabled = true;
    config.noise_suppression.enabled = false;
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;

    aec->apm = webrtc::AudioProcessingBuilder()
                       .SetConfig(config)
                       .SetEchoControlFactory(std::make_unique<CarAec3Factory>())
                       .Create();
    aec->mic = webrtc::StreamConfig(capture_rate, 1);
    if (!aec->apm) {
        delete aec;
        return nullptr;
    }
    return aec;
}

int aec_render(AecEngine *aec, const int16_t *far, int rate, int channels) {
    // The canceller wants the downlink as mono at the mic's rate; the APM converts from the
    // phone's format (48 kHz stereo media, 16 kHz mono telephony) itself.
    // The output copy is unused, but the API writes one, so it gets a scratch frame.
    if (rate <= 0 || rate > MAX_RENDER_RATE || channels < 1 || channels > MAX_RENDER_CHANNELS) {
        return -1;
    }

    webrtc::StreamConfig in(rate, channels);
    int16_t scratch[MAX_RENDER_RATE / AEC_FRAMES_PER_SECOND * MAX_RENDER_CHANNELS];
    return aec->apm->ProcessReverseStream(far, in, in, scratch);
}

int aec_capture(AecEngine *aec, int16_t *near) {
    return aec->apm->ProcessStream(near, aec->mic, aec->mic, near);
}

void aec_stats(AecEngine *aec, AecStats *out) {
    webrtc::AudioProcessingStats s = aec->apm->GetStatistics();
    out->erle_db = s.echo_return_loss_enhancement ? (float) *s.echo_return_loss_enhancement : AEC_UNKNOWN;
    out->erl_db = s.echo_return_loss ? (float) *s.echo_return_loss : AEC_UNKNOWN;
    out->delay_ms = s.delay_ms ? *s.delay_ms : AEC_UNKNOWN;
}

void aec_close(AecEngine *aec) {
    delete aec;
}

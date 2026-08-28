package com.safeway.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.util.Locale;

final class EmergencySoundPlayer implements TextToSpeech.OnInitListener {
    private static final int WHISTLE_REPEAT_COUNT = 12;
    private static final int WHISTLE_INTERVAL_MS = 320;
    private static final int WHISTLE_TONE_MS = 240;
    private static final int SAMPLE_RATE = 44100;
    private static final double WHISTLE_FREQUENCY = 2400.0;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final TextToSpeech textToSpeech;

    private AudioTrack whistleTrack;
    private boolean textToSpeechReady;
    private int previousAlarmVolume = -1;
    private int previousMusicVolume = -1;

    EmergencySoundPlayer(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        textToSpeech = new TextToSpeech(this.context, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            textToSpeechReady = false;
            return;
        }
        int languageResult = textToSpeech.setLanguage(Locale.KOREAN);
        textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        if (languageResult == TextToSpeech.LANG_MISSING_DATA
                || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            textToSpeech.setLanguage(Locale.getDefault());
        }
        textToSpeechReady = true;
    }

    void playWhistle() {
        boostVolume();
        stopWhistleTrack();
        whistleTrack = createWhistleTrack();
        whistleTrack.play();
        handler.postDelayed(this::restoreVolume, WHISTLE_REPEAT_COUNT * WHISTLE_INTERVAL_MS + 900L);
        handler.postDelayed(this::stopWhistleTrack, WHISTLE_REPEAT_COUNT * WHISTLE_INTERVAL_MS + 1200L);
        Toast.makeText(context, "호루라기 소리를 재생합니다.", Toast.LENGTH_SHORT).show();
    }

    void playHelpVoice() {
        boostVolume();
        if (!textToSpeechReady) {
            Toast.makeText(context, "도움 요청 음성을 준비 중이라 경고음을 먼저 재생합니다.", Toast.LENGTH_SHORT).show();
            playWhistle();
            return;
        }
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_ALARM));
        params.putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0");
        textToSpeech.speak(
                "도와주세요. 도와주세요. 주변에 계신 분 도와주세요.",
                TextToSpeech.QUEUE_FLUSH,
                params,
                "safeway_help_voice"
        );
        handler.postDelayed(this::restoreVolume, 6000L);
        Toast.makeText(context, "도움 요청 음성을 재생합니다.", Toast.LENGTH_SHORT).show();
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        restoreVolume();
        stopWhistleTrack();
        textToSpeech.stop();
        textToSpeech.shutdown();
    }

    private AudioTrack createWhistleTrack() {
        short[] samples = createWhistleSamples();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = new AudioTrack(
                attributes,
                format,
                samples.length * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        track.write(samples, 0, samples.length);
        track.setVolume(AudioTrack.getMaxVolume());
        return track;
    }

    private short[] createWhistleSamples() {
        int totalMs = WHISTLE_REPEAT_COUNT * WHISTLE_INTERVAL_MS;
        int totalSamples = SAMPLE_RATE * totalMs / 1000;
        short[] samples = new short[totalSamples];
        int intervalSamples = SAMPLE_RATE * WHISTLE_INTERVAL_MS / 1000;
        int toneSamples = SAMPLE_RATE * WHISTLE_TONE_MS / 1000;
        for (int i = 0; i < totalSamples; i++) {
            int offset = i % intervalSamples;
            if (offset >= toneSamples) {
                samples[i] = 0;
                continue;
            }
            double envelope = Math.min(1.0, Math.min(offset / 400.0, (toneSamples - offset) / 800.0));
            double wave = Math.sin(2.0 * Math.PI * WHISTLE_FREQUENCY * i / SAMPLE_RATE);
            samples[i] = (short) (Short.MAX_VALUE * 0.9 * envelope * wave);
        }
        return samples;
    }

    private void stopWhistleTrack() {
        if (whistleTrack != null) {
            try {
                whistleTrack.stop();
            } catch (IllegalStateException ignored) {
                // 이미 정지된 트랙일 수 있습니다.
            }
            whistleTrack.release();
            whistleTrack = null;
        }
    }

    private void boostVolume() {
        if (audioManager == null) {
            return;
        }
        if (previousAlarmVolume < 0) {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        }
        if (previousMusicVolume < 0) {
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        safeSetVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM));
        safeSetVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void restoreVolume() {
        if (audioManager == null) {
            return;
        }
        if (previousAlarmVolume >= 0) {
            safeSetVolume(AudioManager.STREAM_ALARM, previousAlarmVolume);
            previousAlarmVolume = -1;
        }
        if (previousMusicVolume >= 0) {
            safeSetVolume(AudioManager.STREAM_MUSIC, previousMusicVolume);
            previousMusicVolume = -1;
        }
    }

    private void safeSetVolume(int streamType, int volume) {
        try {
            audioManager.setStreamVolume(streamType, volume, 0);
        } catch (RuntimeException ignored) {
            // 일부 기기에서는 방해금지 모드 등으로 볼륨 변경이 제한될 수 있습니다.
        }
    }
}

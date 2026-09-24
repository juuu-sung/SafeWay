package com.safeway.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

final class WalkingVoice {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private TextToSpeech tts;
    private boolean ready, closed;
    private volatile String utterance = "";
    private long sequence;
    private final Runnable release = this::releaseFocus;

    WalkingVoice(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) stop();
                }, main).build();
        tts = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> {
            if (closed || tts == null || status != TextToSpeech.SUCCESS) return;
            int result = tts.setLanguage(Locale.KOREAN);
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setAudioAttributes(attributes);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(() -> { if (id.equals(utterance)) releaseFocus(); }); }
                @Override public void onError(String id) { main.post(() -> { if (id.equals(utterance)) releaseFocus(); }); }
            });
        }));
    }

    boolean available() { return ready && !closed; }

    boolean speak(String text) {
        if (!available() || audio == null || audio.getMode() != AudioManager.MODE_NORMAL
                || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        // No queue of stale instructions: latest guidance wins.
        main.removeCallbacks(release);
        utterance = "walking-" + (++sequence);
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance);
        main.postDelayed(release, 20000);
        if (result == TextToSpeech.ERROR) { releaseFocus(); return false; }
        return true;
    }

    void stop() {
        utterance = "";
        if (tts != null) tts.stop();
        main.removeCallbacks(release);
        releaseFocus();
    }

    private void releaseFocus() { if (audio != null) audio.abandonAudioFocusRequest(focus); }

    void close() {
        closed = true;
        stop();
        if (tts != null) { tts.shutdown(); tts = null; }
    }
}

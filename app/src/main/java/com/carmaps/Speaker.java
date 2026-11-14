package com.carmaps;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/** Spoken directions through whatever text-to-speech engine the car has. Silent if it has none. */
final class Speaker {

    private TextToSpeech tts;
    private boolean ready;
    private String pending;

    Speaker(Context c) {
        try {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    Log.w("Speaker", "No text-to-speech engine");
                    return;
                }
                try {
                    tts.setLanguage(Locale.getDefault());
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                } catch (RuntimeException ignored) {
                    // keep the engine's defaults
                }
                ready = true;
                if (pending != null) say(pending);
                pending = null;
            });
        } catch (RuntimeException e) {
            tts = null;
        }
    }

    void say(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        if (!ready) {
            pending = text;
            return;
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav");
        } catch (RuntimeException ignored) {
            // the engine went away
        }
    }

    void release() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            tts = null;
        }
    }
}

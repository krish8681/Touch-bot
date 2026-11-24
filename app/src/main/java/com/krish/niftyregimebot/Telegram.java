package com.krish.niftyregimebot;

import com.krish.niftyregimebot.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Optional Telegram alerts. Fire-and-forget on its own thread; failures are ignored. */
public final class Telegram {
    private final OkHttpClient http;
    private final String token, chat;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public Telegram(OkHttpClient http, String token, String chat) {
        this.http = http;
        this.token = token;
        this.chat = chat;
    }

    public boolean enabled() { return !token.isEmpty() && !chat.isEmpty(); }

    public void send(String text) {
        if (!enabled()) return;
        exec.execute(() -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chat);
            body.put("text", "Nifty Regime Bot: " + text);
            Request r = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(RequestBody.create(Json.write(body), MediaType.get("application/json")))
                    .build();
            try (Response ignored = http.newCall(r).execute()) {
                // nothing to do
            } catch (Exception ignored) {
                // alerts are best-effort
            }
        });
    }

    public void shutdown() { exec.shutdown(); }
}

package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import app.nekogram.translator.DeepLTranslator;

public class DeepLTokenHelper {

    private static final boolean ENABLE_DEEPL_TOKEN = true;
    private static final Gson GSON = new Gson();
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekodeepl", Context.MODE_PRIVATE);

    private static String accessToken;
    private static long expiresAt;
    private static int failedAttempts = 0;

    static {
        accessToken = preferences.getString("access_token", null);
        expiresAt = preferences.getLong("expires_at", 0);
        DeepLTranslator.setJwt(accessToken);
    }

    public static void configureAccessToken() {
        if (!ENABLE_DEEPL_TOKEN) return;
        synchronized (DeepLTokenHelper.class) {
            // do not try anymore after failing twice
            if (failedAttempts >= 2) return;
            if (accessToken == null || expiresAt < System.currentTimeMillis() / 1000) {
                SettableFuture<TokenResponse> future = SettableFuture.create();
                InlineBotHelper.queryText("deepl_token", (result, error) -> {
                    if (error != null) {
                        future.setException(new Exception(error));
                        return;
                    }
                    try {
                        var response = GSON.fromJson(result, TokenResponse.class);
                        if (response == null || response.accessToken == null) {
                            future.setException(new Exception("INVALID_RESULT"));
                            return;
                        }
                        future.set(response);
                    } catch (Exception e) {
                        future.setException(e);
                    }
                });
                try {
                    var token = future.get();
                    accessToken = token.accessToken;
                    expiresAt = token.expiresAt;
                    failedAttempts = 0;
                } catch (Exception e) {
                    FileLog.e("Failed to get DeepL access token", e);
                    accessToken = null;
                    // wait 5 mins before try again
                    expiresAt = System.currentTimeMillis() / 1000 + 300;
                    failedAttempts++;
                }
                preferences.edit().putString("access_token", accessToken).putLong("expires_at", expiresAt).apply();
                DeepLTranslator.setJwt(accessToken);
            }
        }
    }

    private static class TokenResponse {
        @SerializedName("access_token")
        @Expose
        public String accessToken;

        @SerializedName("expires_at")
        @Expose
        public long expiresAt;
    }
}

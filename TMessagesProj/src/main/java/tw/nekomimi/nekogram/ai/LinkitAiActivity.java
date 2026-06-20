package tw.nekomimi.nekogram.ai;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class LinkitAiActivity extends Activity {

    private static final String CHAT_ENDPOINT = "https://telegram-ai-bot.shamsaver1.workers.dev/api/chat";
    private static final String PREFS_NAME = "linkit_ai_prefs";

    private static final List<String> THINKING_MESSAGES = new ArrayList<String>() {{
        add("Thinking\u2026"); add("Searching the web\u2026"); add("Reading\u2026");
        add("Checking\u2026"); add("Analyzing\u2026"); add("Working\u2026"); add("Almost there\u2026");
    }};

    private ScrollView scrollView;
    private LinearLayout messagesContainer;
    private EditText inputField;
    private TextView sendButton, backButton, clearButton;
    private final List<String[]> history = new ArrayList<>();
    private boolean isSending = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView thinkingView = null;
    private int thinkingIndex = 0;
    private boolean sessionSaved = false;

    private final Runnable thinkingRunnable = new Runnable() {
        @Override public void run() {
            TextView tv = thinkingView;
            if (tv == null || tv.getParent() == null) return;
            thinkingIndex = (thinkingIndex + 1) % THINKING_MESSAGES.size();
            tv.setText(THINKING_MESSAGES.get(thinkingIndex));
            mainHandler.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF161B22);
        topBar.setPadding(dp(8), dp(10), dp(8), dp(10));
        backButton = makeBarButton("\u2190 Back");
        topBar.addView(backButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView titleView = new TextView(this);
        titleView.setText("Linkit AI");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearButton = makeBarButton("Clear");
        topBar.addView(clearButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView modelLabel = new TextView(this);
        modelLabel.setText("Claude AI \u00b7 GitHub \u00b7 Web \u00b7 Telegram");
        modelLabel.setTextColor(0xFF8B949E);
        modelLabel.setTextSize(11);
        modelLabel.setGravity(Gravity.CENTER);
        modelLabel.setBackgroundColor(0xFF0D1117);
        modelLabel.setPadding(0, dp(5), 0, dp(5));
        root.addView(modelLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF0D1117);
        scrollView.setFillViewport(true);
        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(messagesContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        inputRow.setBackgroundColor(0xFF161B22);
        inputRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputField = new EditText(this);
        inputField.setHint("Message Linkit AI\u2026");
        inputField.setHintTextColor(0xFF6E7681);
        inputField.setTextColor(Color.WHITE);
        inputField.setBackgroundColor(0xFF21262D);
        inputField.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputField.setMaxLines(5);
        inputField.setTextSize(15);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputField.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        inputRow.addView(inputField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendButton = new TextView(this);
        sendButton.setText("Send");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(14);
        sendButton.setTypeface(Typeface.DEFAULT_BOLD);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackgroundColor(0xFF1F6FEB);
        sendButton.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams sendP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendP.leftMargin = dp(8);
        inputRow.addView(sendButton, sendP);
        root.addView(inputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        backButton.setOnClickListener(v -> { saveSession(); finish(); });
        clearButton.setOnClickListener(v -> { saveSession(); history.clear(); sessionSaved = false; messagesContainer.removeAllViews(); addSystemBubble("Conversation saved and cleared."); });
        sendButton.setOnClickListener(v -> sendMessage());

        addSystemBubble("Hello! I'm Linkit AI \u2014 I actually do things, not just talk.\n\n" +
            "\u2022 Search the web for anything\n\u2022 Browse, read and edit your GitHub repo\n" +
            "\u2022 Watch builds, read errors, fix them\n\u2022 Send Telegram messages\n" +
            "\u2022 Read any webpage or API\n\u2022 Translate Arabic \u2194 English\n\nJust tell me what you need.");
    }

    @Override public void onBackPressed() { saveSession(); super.onBackPressed(); }
    @Override protected void onDestroy() { mainHandler.removeCallbacks(thinkingRunnable); super.onDestroy(); }

    private void sendMessage() {
        if (isSending) return;
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        addUserBubble(text);
        history.add(new String[]{"user", text});
        sessionSaved = false;
        sendToAi();
    }

    private void sendToAi() {
        isSending = true;
        setInputsEnabled(false);
        TextView thinking = addThinkingBubble();
        thinkingView = thinking;
        thinkingIndex = 0;
        mainHandler.postDelayed(thinkingRunnable, 1500);
        new Thread(() -> {
            String reply;
            try { reply = callChatServer(); } catch (Exception e) { reply = "Connection error: " + e.getMessage(); }
            final String fr = reply;
            mainHandler.post(() -> {
                mainHandler.removeCallbacks(thinkingRunnable);
                thinkingView = null;
                if (thinking.getParent() != null) messagesContainer.removeView(thinking);
                history.add(new String[]{"assistant", fr});
                addAssistantBubble(fr);
                setInputsEnabled(true);
                isSending = false;
                scrollToBottom();
            });
        }).start();
    }

    private String callChatServer() throws Exception {
        JSONArray msgs = new JSONArray();
        for (String[] m : history) { JSONObject o = new JSONObject(); o.put("role", m[0]); o.put("content", m[1]); msgs.put(o); }
        JSONObject body = new JSONObject();
        body.put("messages", msgs);
        HttpURLConnection conn = (HttpURLConnection) new URL(CHAT_ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setDoOutput(true);
        try (OutputStreamWriter w = new OutputStreamWriter(conn.getOutputStream(), "UTF-8")) { w.write(body.toString()); }
        int code = conn.getResponseCode();
        java.io.InputStream s = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (s == null) return "No response.";
        BufferedReader r = new BufferedReader(new InputStreamReader(s, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return new JSONObject(sb.toString()).optString("reply", "No reply.");
    }

    private void setInputsEnabled(boolean e) {
        inputField.setEnabled(e); sendButton.setEnabled(e);
        sendButton.setBackgroundColor(e ? 0xFF1F6FEB : 0xFF21262D);
        clearButton.setEnabled(e); backButton.setEnabled(e);
    }

    private void addUserBubble(String t) { messagesContainer.addView(makeBubble(t, 0xFF1F6FEB, Color.WHITE, true)); scrollToBottom(); }
    private void addAssistantBubble(String t) {
        TextView tv = makeBubble(t, 0xFF21262D, 0xFFE6EDF3, false);
        Linkify.addLinks(tv, Linkify.WEB_URLS);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        messagesContainer.addView(tv); scrollToBottom();
    }
    private void addSystemBubble(String t) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF8B949E);
        tv.setTextSize(13); tv.setGravity(Gravity.CENTER); tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        messagesContainer.addView(tv); scrollToBottom();
    }
    private TextView addThinkingBubble() {
        TextView tv = makeBubble(THINKING_MESSAGES.get(0), 0xFF21262D, 0xFF8B949E, false);
        messagesContainer.addView(tv); scrollToBottom(); return tv;
    }
    private TextView makeBubble(String t, int bg, int tc, boolean isUser) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(tc);
        tv.setTextSize(14); tv.setPadding(dp(12), dp(8), dp(12), dp(8)); tv.setBackgroundColor(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4); p.bottomMargin = dp(4);
        if (isUser) { p.gravity = Gravity.END; p.leftMargin = dp(60); }
        else { p.gravity = Gravity.START; p.rightMargin = dp(60); }
        tv.setLayoutParams(p); return tv;
    }
    private void scrollToBottom() { scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN)); }
    private void saveSession() {
        if (!history.isEmpty() && !sessionSaved) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor ed = prefs.edit();
            JSONArray arr = new JSONArray();
            for (String[] m : history) { JSONObject o = new JSONObject(); try { o.put("role", m[0]); o.put("content", m[1]); } catch (JSONException ignored) {} arr.put(o); }
            ed.putString("session_" + System.currentTimeMillis(), arr.toString()); ed.apply();
            sessionSaved = true;
        }
    }
    private TextView makeBarButton(String label) {
        TextView tv = new TextView(this); tv.setText(label); tv.setTextColor(0xFF58A6FF);
        tv.setTextSize(13); tv.setGravity(Gravity.CENTER); tv.setPadding(dp(8), dp(4), dp(8), dp(4)); return tv;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

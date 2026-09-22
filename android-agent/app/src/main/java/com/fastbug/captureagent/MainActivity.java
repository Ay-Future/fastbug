package com.fastbug.captureagent;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final int NAVY = Color.rgb(12, 20, 39);
    private static final int SURFACE = Color.rgb(24, 35, 59);
    private static final int SURFACE_LIGHT = Color.rgb(34, 48, 76);
    private static final int TEXT = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(164, 181, 211);
    private static final int BLUE = Color.rgb(83, 139, 255);

    private EditText collectorUrl;
    private EditText sessionId;
    private TextView status;
    private TextView statusPill;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        buildUi();
        applyIntentConfig(getIntent());
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentConfig(intent);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setPadding(0, 0, 0, dp(24));
        TextView mark = text("F", 22, TEXT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(BLUE, dp(15)));
        masthead.addView(mark, size(dp(48), dp(48)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(14), 0, 0, 0);
        brand.addView(text("FastBug", 22, TEXT, Typeface.BOLD));
        brand.addView(text("CAPTURE AGENT  ·  POC", 11, MUTED, Typeface.BOLD));
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(masthead);

        LinearLayout hero = card();
        hero.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(36, 70, 134), Color.rgb(31, 50, 91)});
        heroBg.setCornerRadius(dp(22));
        hero.setBackground(heroBg);
        hero.addView(text("捕获每一个关键瞬间", 24, TEXT, Typeface.BOLD));
        TextView heroCopy = text("一键录制、自动整理证据，让缺陷报告更完整。", 14, Color.rgb(218, 230, 255), Typeface.NORMAL);
        heroCopy.setPadding(0, dp(7), 0, dp(16));
        hero.addView(heroCopy);
        LinearLayout current = new LinearLayout(this);
        current.setGravity(Gravity.CENTER_VERTICAL);
        current.setPadding(dp(12), dp(10), dp(12), dp(10));
        current.setBackground(round(Color.argb(70, 255, 255, 255), dp(14)));
        statusPill = text("准备就绪", 13, Color.rgb(216, 255, 239), Typeface.BOLD);
        current.addView(statusPill, new LinearLayout.LayoutParams(0, -2, 1));
        current.addView(text("本机采集", 12, Color.rgb(214, 227, 255), Typeface.NORMAL));
        hero.addView(current, new LinearLayout.LayoutParams(-1, -2));
        root.addView(hero, margin(-1, -2, 0, 0, 0, 0));

        root.addView(sectionTitle("采集配置", "连接到你的 Collector 服务"));
        LinearLayout config = card();
        config.setPadding(dp(18), dp(6), dp(18), dp(14));
        config.addView(field("COLLECTOR 地址", "http://127.0.0.1:52741", "collector_url"));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(53, 70, 101));
        config.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        config.addView(field("会话 ID", "由 Collector 生成", "session_id"));
        root.addView(config);

        root.addView(sectionTitle("开始采集", "首次使用请完成悬浮窗与录屏授权"));
        LinearLayout actions = card();
        actions.setPadding(dp(16), dp(16), dp(16), dp(16));
        Button start = button("开始采集", BLUE, TEXT);
        start.setOnClickListener(v -> requestProjection());
        actions.addView(start, new LinearLayout.LayoutParams(-1, dp(52)));
        Button overlay = button("授权悬浮窗", SURFACE_LIGHT, TEXT);
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        actions.addView(overlay, margin(-1, dp(48), 0, dp(10), 0, 0));
        LinearLayout secondary = new LinearLayout(this);
        secondary.setGravity(Gravity.CENTER_VERTICAL);
        Button stop = button("停止采集", Color.TRANSPARENT, MUTED);
        stop.setBackground(stroke(SURFACE, MUTED, dp(12), 1));
        stop.setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            refreshStatus();
        });
        secondary.addView(stop, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button refresh = button("刷新状态", Color.TRANSPARENT, MUTED);
        refresh.setBackground(stroke(SURFACE, MUTED, dp(12), 1));
        refresh.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams refreshParams = margin(0, dp(44), dp(10), 0, 0, 0);
        refreshParams.weight = 1;
        secondary.addView(refresh, refreshParams);
        actions.addView(secondary, new LinearLayout.LayoutParams(-1, -2));
        root.addView(actions);

        root.addView(sectionTitle("采集状态", "服务运行情况与文件位置"));
        LinearLayout statusCard = card();
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        status = text("", 14, MUTED, Typeface.NORMAL);
        status.setLineSpacing(dp(4), 1f);
        statusCard.addView(status);
        root.addView(statusCard);
        TextView foot = text("FastBug  ·  Evidence first", 12, Color.rgb(106, 126, 164), Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, 0);
        root.addView(foot);
        setContentView(scroll);
        refreshStatus();
    }

    private View field(String caption, String hint, String key) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(14), 0, dp(12));
        group.addView(text(caption, 11, MUTED, Typeface.BOLD));
        EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(103, 124, 161));
        input.setHint(hint);
        input.setSingleLine(true);
        input.setPadding(0, dp(5), 0, 0);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setText(prefs().getString(key, ""));
        if ("collector_url".equals(key)) {
            if (input.getText().length() == 0) input.setText("http://127.0.0.1:52741");
            collectorUrl = input;
            input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            sessionId = input;
        }
        group.addView(input, new LinearLayout.LayoutParams(-1, dp(38)));
        return group;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(round(SURFACE, dp(18)));
        return view;
    }

    private View sectionTitle(String title, String note) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(2), dp(26), dp(2), dp(10));
        header.addView(text(title, 16, TEXT, Typeface.BOLD));
        TextView sub = text(note, 12, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        return header;
    }

    private Button button(String label, int color, int textColor) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(round(color, dp(12)));
        return button;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable stroke(int color, int strokeColor, int radius, int width) {
        GradientDrawable drawable = round(color, radius);
        drawable.setStroke(dp(width), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams size(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void applyIntentConfig(Intent intent) {
        if (intent == null || collectorUrl == null) return;
        String url = intent.getStringExtra("collector_url");
        String session = intent.getStringExtra("session_id");
        if (url != null) collectorUrl.setText(url);
        if (session != null) sessionId.setText(session);
        if (url != null || session != null) saveConfig();
    }

    private void requestProjection() {
        saveConfig();
        if (sessionId.getText().toString().trim().isEmpty()) {
            updateStatus("需要会话 ID", "请先填入 Collector 输出的会话 ID。");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START);
            service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            updateStatus("正在启动", "正在启动采集服务…");
        } else if (requestCode == REQUEST_PROJECTION) {
            updateStatus("等待授权", "录屏授权被取消，未开始采集。");
        }
    }

    private void saveConfig() {
        prefs().edit().putString("collector_url", collectorUrl.getText().toString().trim())
                .putString("session_id", sessionId.getText().toString().trim()).apply();
    }

    private SharedPreferences prefs() { return getSharedPreferences("fastbug", MODE_PRIVATE); }

    private void refreshStatus() {
        String state = prefs().getString("state", "未采集");
        String detail = prefs().getString("state_detail", "");
        updateStatus(state, (detail.isEmpty() ? "等待开始一次新的屏幕采集。" : detail)
                + "\n\n文件保存至\nAndroid/data/" + getPackageName() + "/files/Movies/fastbug/");
    }

    private void updateStatus(String headline, String detail) {
        if (statusPill != null) statusPill.setText("●  " + headline);
        if (status != null) status.setText(detail);
    }
}

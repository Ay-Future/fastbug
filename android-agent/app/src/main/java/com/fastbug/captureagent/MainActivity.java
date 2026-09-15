package com.fastbug.captureagent;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private EditText collectorUrl;
    private EditText sessionId;
    private TextView status;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 36);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("FastBug Capture Agent (POC)");
        title.setTextSize(22);
        root.addView(title);
        root.addView(label("Collector 地址（USB ADB 默认保持不变）"));
        collectorUrl = new EditText(this);
        collectorUrl.setSingleLine(true);
        collectorUrl.setText(prefs().getString("collector_url", "http://127.0.0.1:52741"));
        root.addView(collectorUrl);
        root.addView(label("会话 ID（由 Collector 生成）"));
        sessionId = new EditText(this);
        sessionId.setSingleLine(true);
        sessionId.setText(prefs().getString("session_id", ""));
        root.addView(sessionId);

        Button overlay = new Button(this);
        overlay.setText("授权悬浮窗");
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(overlay);
        Button start = new Button(this);
        start.setText("开始采集并授权录屏");
        start.setOnClickListener(v -> requestProjection());
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("停止采集");
        stop.setOnClickListener(v -> startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)));
        root.addView(stop);
        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);
        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 24, 0, 0);
        root.addView(status);
        setContentView(scroll);
        refreshStatus();
    }

    private TextView label(String value) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setPadding(0, 20, 0, 4);
        return v;
    }

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
            status.setText("请先填入 Collector 输出的会话 ID。");
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
            status.setText("正在启动采集服务…");
        } else if (requestCode == REQUEST_PROJECTION) {
            status.setText("录屏授权被取消，未开始采集。");
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
        status.setText("状态：" + state + (detail.isEmpty() ? "" : "\n" + detail)
                + "\n录屏文件：Android/data/" + getPackageName() + "/files/Movies/fastbug/");
    }
}


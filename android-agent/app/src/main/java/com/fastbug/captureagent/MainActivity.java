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

/** Local configuration and control surface for the FastBug capture foreground service. */
public class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    // Shared visual language with the Collector dashboard: ink header, light canvas, blue actions.
    private static final int NAVY = Color.rgb(13, 23, 48);
    private static final int INK = Color.rgb(23, 40, 72);
    private static final int CANVAS = Color.rgb(242, 246, 252);
    private static final int WHITE = Color.WHITE;
    private static final int FIELD = Color.rgb(249, 251, 255);
    private static final int BORDER = Color.rgb(218, 227, 241);
    private static final int MUTED = Color.rgb(103, 122, 151);
    private static final int LIGHT_MUTED = Color.rgb(218, 228, 246);
    private static final int BLUE = Color.rgb(62, 111, 242);
    private static final int BLUE_DARK = Color.rgb(38, 81, 197);
    private static final int BLUE_PALE = Color.rgb(232, 239, 255);
    private static final int SUCCESS = Color.rgb(30, 157, 105);
    private static final int SUCCESS_PALE = Color.rgb(230, 248, 239);
    private static final int WARNING = Color.rgb(190, 115, 43);
    private static final int WARNING_PALE = Color.rgb(255, 244, 222);

    private EditText collectorUrl;
    private EditText sessionId;
    private TextView status;
    private TextView statusPill;
    private TextView statusHeadline;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(CANVAS);
        buildUi();
        applyIntentConfig(getIntent());
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
        scroll.setBackgroundColor(CANVAS);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(buildMasthead());
        root.addView(buildHero(), margin(-1, -2, 0, dp(16), 0, 0));

        root.addView(sectionHeader("CONNECTION", "连接设置", "连接电脑上的 Collector 服务"));
        LinearLayout config = card();
        config.setPadding(dp(16), dp(15), dp(16), dp(16));
        config.addView(field("COLLECTOR 地址", "http://127.0.0.1:52741", "collector_url"));
        config.addView(divider(), margin(-1, dp(1), 0, dp(14), 0, dp(14)));
        config.addView(field("会话 ID", "由 Collector 启动时生成", "session_id"));
        root.addView(config);

        root.addView(sectionHeader("CAPTURE CONTROL", "开始一次采集", "完成授权后，在被测应用中点击悬浮按钮报缺陷"));
        LinearLayout controls = card();
        controls.setPadding(dp(16), dp(16), dp(16), dp(16));

        Button start = button("开始采集", gradient(BLUE, BLUE_DARK), WHITE, 14);
        start.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.presence_video_online, 0, 0, 0);
        start.setCompoundDrawablePadding(dp(9));
        start.setOnClickListener(v -> requestProjection());
        controls.addView(start, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout assistance = new LinearLayout(this);
        assistance.setGravity(Gravity.CENTER_VERTICAL);
        Button overlay = button("授权悬浮窗", round(BLUE_PALE, dp(12)), BLUE_DARK, 12);
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        assistance.addView(overlay, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button reportArchived = button("补传封存记录", round(FIELD, dp(12)), INK, 12);
        reportArchived.setBackground(stroke(FIELD, BORDER, dp(12), 1));
        reportArchived.setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_RETRY_ARCHIVED));
            updateStatus("正在补传", "正在主动上报已封存但尚未传到电脑的记录…");
        });
        LinearLayout.LayoutParams reportParams = margin(0, dp(46), dp(10), 0, 0, 0);
        reportParams.weight = 1;
        assistance.addView(reportArchived, reportParams);
        controls.addView(assistance, margin(-1, -2, 0, dp(10), 0, 0));

        LinearLayout secondary = new LinearLayout(this);
        secondary.setGravity(Gravity.CENTER_VERTICAL);
        Button stop = button("停止采集", round(WHITE, dp(11)), MUTED, 11);
        stop.setBackground(stroke(WHITE, BORDER, dp(11), 1));
        stop.setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            refreshStatus();
        });
        secondary.addView(stop, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button refresh = button("刷新状态", round(WHITE, dp(11)), MUTED, 11);
        refresh.setBackground(stroke(WHITE, BORDER, dp(11), 1));
        refresh.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams refreshParams = margin(0, dp(42), dp(10), 0, 0, 0);
        refreshParams.weight = 1;
        secondary.addView(refresh, refreshParams);
        controls.addView(secondary, margin(-1, -2, 0, dp(12), 0, 0));
        root.addView(controls);

        root.addView(sectionHeader("LIVE STATUS", "采集状态", "确认服务、补传和设备端文件状态"));
        LinearLayout statusCard = card();
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout statusTop = new LinearLayout(this);
        statusTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView indicator = text("●", 18, BLUE, Typeface.BOLD);
        statusTop.addView(indicator, size(dp(24), dp(28)));
        statusHeadline = text("正在读取状态", 16, INK, Typeface.BOLD);
        statusTop.addView(statusHeadline, new LinearLayout.LayoutParams(0, -2, 1));
        statusCard.addView(statusTop);
        status = text("", 13, MUTED, Typeface.NORMAL);
        status.setLineSpacing(dp(4), 1f);
        status.setPadding(0, dp(7), 0, 0);
        statusCard.addView(status);
        root.addView(statusCard);

        LinearLayout privacy = new LinearLayout(this);
        privacy.setOrientation(LinearLayout.VERTICAL);
        privacy.setPadding(dp(14), dp(13), dp(14), dp(13));
        privacy.setBackground(round(Color.rgb(235, 242, 255), dp(14)));
        privacy.addView(text("设备端留存规则", 12, BLUE_DARK, Typeface.BOLD));
        TextView privacyCopy = text("采集时仅保留最近约 60 秒画面；证据成功传到电脑后，设备端录像会自动清理。", 12, MUTED, Typeface.NORMAL);
        privacyCopy.setLineSpacing(dp(3), 1f);
        privacyCopy.setPadding(0, dp(4), 0, 0);
        privacy.addView(privacyCopy);
        root.addView(privacy, margin(-1, -2, 0, dp(14), 0, 0));

        TextView foot = text("FastBug  ·  Evidence capture workspace", 11, MUTED, Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(22), 0, 0);
        root.addView(foot);

        setContentView(scroll);
        refreshStatus();
    }

    private View buildMasthead() {
        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("F", 21, WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(gradient(Color.rgb(137, 119, 255), Color.rgb(58, 144, 255)));
        masthead.addView(mark, size(dp(44), dp(44)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("FastBug", 21, INK, Typeface.BOLD));
        brand.addView(text("EVIDENCE CAPTURE AGENT", 10, MUTED, Typeface.BOLD));
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        TextView local = text("USB 本地", 11, SUCCESS, Typeface.BOLD);
        local.setGravity(Gravity.CENTER);
        local.setPadding(dp(10), 0, dp(10), 0);
        local.setBackground(round(SUCCESS_PALE, dp(14)));
        masthead.addView(local, new LinearLayout.LayoutParams(-2, dp(30)));
        return masthead;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(19), dp(18), dp(19), dp(17));
        hero.setBackground(gradient(Color.rgb(22, 45, 91), Color.rgb(38, 93, 183)));
        hero.setElevation(dp(4));

        hero.addView(text("CAPTURE WORKSPACE", 10, Color.rgb(187, 211, 255), Typeface.BOLD));
        TextView title = text("让每一次缺陷，都带着现场", 23, WHITE, Typeface.BOLD);
        title.setPadding(0, dp(6), 0, 0);
        hero.addView(title);
        TextView copy = text("录屏、现场证据和本地交付，都在一次操作中完成。", 13, LIGHT_MUTED, Typeface.NORMAL);
        copy.setPadding(0, dp(7), 0, dp(16));
        hero.addView(copy);

        LinearLayout state = new LinearLayout(this);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(12), dp(10), dp(12), dp(10));
        state.setBackground(round(Color.argb(55, 255, 255, 255), dp(13)));
        statusPill = text("●  正在读取", 12, Color.rgb(222, 240, 255), Typeface.BOLD);
        state.addView(statusPill, new LinearLayout.LayoutParams(0, -2, 1));
        state.addView(text("设备端临时留存", 11, Color.rgb(205, 223, 255), Typeface.NORMAL));
        hero.addView(state, new LinearLayout.LayoutParams(-1, -2));
        return hero;
    }

    private View field(String caption, String hint, String key) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(text(caption, 10, MUTED, Typeface.BOLD));

        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setHintTextColor(Color.rgb(150, 164, 187));
        input.setHint(hint);
        input.setSingleLine(true);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(stroke(FIELD, BORDER, dp(11), 1));
        input.setText(prefs().getString(key, ""));
        if ("collector_url".equals(key)) {
            if (input.getText().length() == 0) input.setText("http://127.0.0.1:52741");
            collectorUrl = input;
            input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            sessionId = input;
        }
        group.addView(input, margin(-1, dp(46), 0, dp(7), 0, 0));
        return group;
    }

    private View sectionHeader(String eyebrow, String title, String note) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(2), dp(25), dp(2), dp(10));
        header.addView(text(eyebrow, 10, BLUE, Typeface.BOLD));
        TextView heading = text(title, 18, INK, Typeface.BOLD);
        heading.setPadding(0, dp(3), 0, 0);
        header.addView(heading);
        TextView sub = text(note, 12, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        return header;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(stroke(WHITE, BORDER, dp(18), 1));
        view.setElevation(dp(2));
        return view;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        return divider;
    }

    private Button button(String label, GradientDrawable background, int textColor, int textSize) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(textSize);
        button.setTextColor(textColor);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(background);
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

    private GradientDrawable gradient(int start, int end) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        drawable.setCornerRadius(dp(18));
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
                + "\n\n设备临时文件\nAndroid/data/" + getPackageName() + "/files/Movies/fastbug/\n成功传到电脑后会自动清理。"
        );
    }

    private void updateStatus(String headline, String detail) {
        int accent = headline.contains("采集中") || headline.contains("已补传") ? SUCCESS
                : headline.contains("错误") || headline.contains("失败") || headline.contains("需要") ? WARNING : BLUE;
        if (statusPill != null) {
            statusPill.setText("●  " + headline);
            statusPill.setTextColor(accent == SUCCESS ? Color.rgb(208, 255, 230) : Color.rgb(219, 234, 255));
        }
        if (statusHeadline != null) statusHeadline.setText(headline);
        if (status != null) status.setText(detail);
        // The hero status uses a dark surface, while the detail card gets an inline semantic dot.
        if (statusHeadline != null) statusHeadline.setTextColor(accent);
    }
}

package com.fastbug.captureagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that records 10-second H.264 segments and owns the overlay trigger. */
public class CaptureService extends Service {
    public static final String ACTION_START = "com.fastbug.captureagent.START";
    public static final String ACTION_STOP = "com.fastbug.captureagent.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final int NOTIFICATION_ID = 41;
    private static final long SEGMENT_MS = 10_000L;
    private static final int RING_SEGMENTS = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayDeque<File> ring = new ArrayDeque<>();
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private File currentFile;
    private int generation;
    private boolean running;
    private PendingCapture pending;
    private WindowManager windowManager;
    private View overlay;
    private TextView overlayLabel;
    private WindowManager.LayoutParams overlayParams;
    private final Runnable resetOverlayRunnable = () -> showIdleOverlay();

    private final Runnable rollRunnable = new Runnable() {
        @Override public void run() {
            synchronized (CaptureService.this) {
                if (!running || pending != null) return;
                finishCurrentSegment();
                startRegularSegment();
            }
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture("用户停止");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                updateState("错误", "没有获得媒体投影授权。");
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, notification("正在持续保留最近 60 秒屏幕录像"));
            startCapture(resultCode, resultData);
        }
        return START_NOT_STICKY;
    }

    private synchronized void startCapture(int resultCode, Intent resultData) {
        if (running) stopCapture("重新开始");
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopCapture("系统撤销了录屏权限"); }
            }, handler);
            running = true;
            updateState("采集中", "正在保留最近 60 秒录像；点击悬浮按钮即可捕获。");
            showOverlay();
            startRegularSegment();
        } catch (Exception e) {
            updateState("错误", "启动录屏失败：" + e.getMessage());
            stopCapture("启动失败");
        }
    }

    private void startRegularSegment() {
        startSegment("segment_" + System.currentTimeMillis() + ".mp4", false);
        handler.postDelayed(rollRunnable, SEGMENT_MS);
    }

    private void startSegment(String filename, boolean postCapture) {
        try {
            generation++;
            File dir = recordingDir();
            currentFile = new File(dir, filename);
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            // Encoder compatibility is more important than native-resolution video in this POC.
            int targetWidth = Math.min(width, 1080);
            int targetHeight = Math.round(height * (targetWidth / (float) width));
            targetHeight = targetHeight - (targetHeight % 2);
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(5_000_000);
            recorder.setVideoFrameRate(20);
            recorder.setVideoSize(targetWidth, targetHeight);
            recorder.prepare();
            display = projection.createVirtualDisplay("FastBugCapture", targetWidth, targetHeight,
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, handler);
            recorder.start();
        } catch (Exception e) {
            updateState("错误", "无法开始视频片段：" + e.getMessage());
            stopCapture("视频编码失败");
        }
    }

    private File finishCurrentSegment() {
        File finished = currentFile;
        handler.removeCallbacks(rollRunnable);
        if (display != null) { display.release(); display = null; }
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { if (finished != null) finished.delete(); }
            recorder.reset();
            recorder.release();
            recorder = null;
        }
        currentFile = null;
        if (finished != null && finished.exists() && finished.length() > 0) {
            ring.addLast(finished);
            while (ring.size() > RING_SEGMENTS) {
                File stale = ring.removeFirst();
                if (pending == null || !pending.files.contains(stale)) stale.delete();
            }
        }
        return finished;
    }

    private synchronized void triggerCapture() {
        if (!running) return;
        if (pending != null) {
            updateState("采集中", "上一次捕获尚在收尾，请稍后再试。");
            showSavingOverlay();
            return;
        }
        String captureId = "cap_" + UUID.randomUUID().toString().replace("-", "");
        long wallTime = System.currentTimeMillis();
        long monotonicMs = android.os.SystemClock.elapsedRealtime();
        postEvent("triggered", captureId, wallTime, monotonicMs, null);
        // Keep six completed segments plus the just-finished partial segment.
        // This avoids silently losing the oldest seconds at the ring boundary.
        pending = new PendingCapture(captureId, wallTime, monotonicMs, new ArrayList<>(ring));
        File current = finishCurrentSegment();
        if (current != null && current.exists()) pending.files.add(current);
        updateState("封存中", "已触发 " + captureId + "，正在额外录制约 10 秒。");
        showSavingOverlay();
        startSegment("post_" + captureId + ".mp4", true);
        handler.postDelayed(() -> completeCapture(captureId), SEGMENT_MS);
    }

    private synchronized void completeCapture(String captureId) {
        if (!running || pending == null || !pending.captureId.equals(captureId)) return;
        File post = finishCurrentSegment();
        if (post != null && post.exists()) pending.files.add(post);
        PendingCapture completed = pending;
        // Recording resumes before remuxing. The pending file list prevents ring cleanup
        // from deleting the source segments until Collector has pulled the evidence.
        startRegularSegment();
        network.execute(() -> {
            File replay = remuxVideo(completed.files, new File(recordingDir(), "replay_" + completed.captureId + ".mp4"));
            boolean delivered = postReady(completed, replay);
            synchronized (CaptureService.this) {
                if (pending == completed) pending = null;
            }
            if (delivered) {
                updateState("采集中", "证据已封存并通知 Collector：" + captureId);
                handler.post(this::showSavedOverlay);
            } else {
                handler.post(this::showTransferFailedOverlay);
            }
        });
    }

    private void postEvent(String type, String captureId, long wallTime, long monotonicMs, List<File> files) {
        String collector = prefs().getString("collector_url", "http://127.0.0.1:52741");
        String session = prefs().getString("session_id", "");
        network.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", type);
                body.put("captureId", captureId);
                body.put("sessionId", session);
                body.put("triggeredAtWallMs", wallTime);
                body.put("triggeredAtMonotonicMs", monotonicMs);
                if (files != null) {
                    JSONArray paths = new JSONArray();
                    for (File f : files) paths.put("/sdcard/Android/data/" + getPackageName() + "/files/Movies/fastbug/" + f.getName());
                    body.put("videoPaths", paths);
                }
                HttpURLConnection connection = (HttpURLConnection) new URL(collector + "/v1/events").openConnection();
                connection.setConnectTimeout(3_000);
                connection.setReadTimeout(5_000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("Collector 返回 HTTP " + status);
                connection.disconnect();
            } catch (Exception e) {
                updateState("采集中", "Collector 通知失败：" + e.getMessage());
            }
        });
    }

    private boolean postReady(PendingCapture completed, File replay) {
        String collector = prefs().getString("collector_url", "http://127.0.0.1:52741");
        String session = prefs().getString("session_id", "");
        try {
            JSONObject body = new JSONObject();
            body.put("type", "ready");
            body.put("captureId", completed.captureId);
            body.put("sessionId", session);
            body.put("triggeredAtWallMs", completed.wallTime);
            body.put("triggeredAtMonotonicMs", completed.monotonicMs);
            JSONArray paths = new JSONArray();
            for (File f : completed.files) paths.put(remotePath(f));
            body.put("videoPaths", paths);
            if (replay != null && replay.exists() && replay.length() > 0) body.put("replayPath", remotePath(replay));
            HttpURLConnection connection = (HttpURLConnection) new URL(collector + "/v1/events").openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(60_000); // Collector may pull several video files before replying.
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            connection.disconnect();
            if (status < 200 || status >= 300) throw new IOException("Collector 返回 HTTP " + status);
            return true;
        } catch (Exception e) {
            updateState("传输失败", "录像已封存在平板，但未传到电脑：" + e.getMessage());
            return false;
        }
    }

    private String remotePath(File file) {
        return "/sdcard/Android/data/" + getPackageName() + "/files/Movies/fastbug/" + file.getName();
    }

    /** Remuxes identical H.264 segments without recompressing; returns null on any incompatibility. */
    private File remuxVideo(List<File> inputs, File output) {
        if (inputs.isEmpty()) return null;
        MediaExtractor probe = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            probe.setDataSource(inputs.get(0).getAbsolutePath());
            int sourceTrack = findVideoTrack(probe);
            if (sourceTrack < 0) throw new IOException("首片段没有视频轨道");
            MediaFormat format = probe.getTrackFormat(sourceTrack);
            int maxSize = Math.max(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0, 2 * 1024 * 1024);
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxSize);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            long outputOffsetUs = 0L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (File input : inputs) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(input.getAbsolutePath());
                    int track = findVideoTrack(extractor);
                    if (track < 0) continue;
                    extractor.selectTrack(track);
                    long firstSampleUs = -1L;
                    long lastSampleUs = -1L;
                    while (true) {
                        info.offset = 0;
                        info.size = extractor.readSampleData(buffer, 0);
                        if (info.size < 0) break;
                        long sampleUs = extractor.getSampleTime();
                        if (firstSampleUs < 0) firstSampleUs = sampleUs;
                        info.presentationTimeUs = outputOffsetUs + Math.max(0L, sampleUs - firstSampleUs);
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(outputTrack, buffer, info);
                        lastSampleUs = info.presentationTimeUs;
                        extractor.advance();
                    }
                    if (lastSampleUs >= 0) outputOffsetUs = lastSampleUs + 1L;
                } finally { extractor.release(); }
            }
            muxer.stop();
            muxer.release();
            return output;
        } catch (Exception e) {
            if (muxer != null) { try { muxer.release(); } catch (Exception ignored) {} }
            output.delete();
            updateState("采集中", "单文件录像拼接失败，仍将保留原始片段：" + e.getMessage());
            return null;
        } finally { probe.release(); }
    }

    private int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView button = new TextView(this);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setLineSpacing(dp(2), 1f);
        button.setPadding(dp(18), dp(12), dp(18), dp(12));
        button.setElevation(dp(10));
        button.setOnClickListener(v -> triggerCapture());
        configureDrag(button);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs().getInt("overlay_x", screenWidth() - dp(100));
        params.y = prefs().getInt("overlay_y", screenHeight() / 2 - dp(40));
        overlayParams = params;
        try {
            windowManager.addView(button, params);
            overlay = button;
            overlayLabel = button;
            showIdleOverlay();
        }
        catch (Exception e) { updateState("采集中", "悬浮窗不可用：" + e.getMessage()); }
    }

    private void configureDrag(View button) {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downX;
            private int downY;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (overlayParams == null || windowManager == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = overlayParams.x;
                        downY = overlayParams.y;
                        dragging = false;
                        handler.removeCallbacks(resetOverlayRunnable);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                        if (dragging) {
                            overlayParams.x = clamp(downX + Math.round(dx), 0, screenWidth() - overlayWidth(view));
                            overlayParams.y = clamp(downY + Math.round(dy), dp(24), screenHeight() - overlayHeight(view) - dp(24));
                            updateOverlayLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            snapOverlayToEdge(view);
                            if (pending == null) handler.postDelayed(resetOverlayRunnable, 600L);
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            view.performClick();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void snapOverlayToEdge(View view) {
        int maxX = Math.max(0, screenWidth() - overlayWidth(view));
        overlayParams.x = overlayParams.x + overlayWidth(view) / 2 < screenWidth() / 2 ? 0 : maxX;
        overlayParams.y = clamp(overlayParams.y, dp(24), screenHeight() - overlayHeight(view) - dp(24));
        prefs().edit().putInt("overlay_x", overlayParams.x).putInt("overlay_y", overlayParams.y).apply();
        updateOverlayLayout();
    }

    private void updateOverlayLayout() {
        if (windowManager != null && overlay != null && overlayParams != null) {
            try { windowManager.updateViewLayout(overlay, overlayParams); } catch (Exception ignored) {}
        }
    }

    private void showIdleOverlay() {
        if (overlayLabel == null) return;
        overlayLabel.setText("报缺陷");
        overlayLabel.setTextColor(Color.WHITE);
        overlayLabel.setTextSize(15);
        overlayLabel.setAlpha(0.84f);
        overlayLabel.setBackground(overlayBackground(Color.rgb(218, 59, 70), Color.argb(125, 255, 255, 255)));
    }

    private void showSavingOverlay() {
        if (overlayLabel == null) return;
        handler.removeCallbacks(resetOverlayRunnable);
        overlayLabel.setText("●  正在封存\n请稍候…");
        overlayLabel.setTextColor(Color.WHITE);
        overlayLabel.setTextSize(15);
        overlayLabel.setAlpha(0.92f);
        overlayLabel.setBackground(overlayBackground(Color.rgb(239, 143, 48), Color.argb(145, 255, 255, 255)));
    }

    private void showSavedOverlay() {
        if (overlayLabel == null) return;
        overlayLabel.setText("✓  已封存\n继续采集中");
        overlayLabel.setTextColor(Color.WHITE);
        overlayLabel.setTextSize(15);
        overlayLabel.setAlpha(0.9f);
        overlayLabel.setBackground(overlayBackground(Color.rgb(44, 166, 125), Color.argb(145, 255, 255, 255)));
        handler.removeCallbacks(resetOverlayRunnable);
        handler.postDelayed(resetOverlayRunnable, 2_500L);
    }

    private void showTransferFailedOverlay() {
        if (overlayLabel == null) return;
        overlayLabel.setText("!  传输失败\n请检查电脑连接");
        overlayLabel.setTextColor(Color.WHITE);
        overlayLabel.setTextSize(15);
        overlayLabel.setAlpha(0.92f);
        overlayLabel.setBackground(overlayBackground(Color.rgb(190, 57, 67), Color.argb(145, 255, 255, 255)));
        handler.removeCallbacks(resetOverlayRunnable);
        handler.postDelayed(resetOverlayRunnable, 4_000L);
    }

    private GradientDrawable overlayBackground(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(22));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenHeight() { return getResources().getDisplayMetrics().heightPixels; }
    private int overlayWidth(View view) { return Math.max(view.getWidth(), dp(100)); }
    private int overlayHeight(View view) { return Math.max(view.getHeight(), dp(52)); }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(Math.max(min, max), value)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) { try { windowManager.removeView(overlay); } catch (Exception ignored) {} }
        overlay = null;
        overlayLabel = null;
        overlayParams = null;
    }

    private synchronized void stopCapture(String reason) {
        handler.removeCallbacksAndMessages(null);
        running = false;
        try { finishCurrentSegment(); } catch (Exception ignored) {}
        MediaProjection activeProjection = projection;
        projection = null;
        if (activeProjection != null) { activeProjection.stop(); }
        removeOverlay();
        updateState("已停止", reason);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private File recordingDir() {
        File root = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "fastbug");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建录屏目录");
        return root;
    }

    private Notification notification(String text) {
        String channelId = "capture";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channelId, "FastBug 录屏采集", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        return new Notification.Builder(this, channelId).setContentTitle("FastBug 正在采集").setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
    }

    private SharedPreferences prefs() { return getSharedPreferences("fastbug", MODE_PRIVATE); }
    private void updateState(String state, String detail) { prefs().edit().putString("state", state).putString("state_detail", detail).apply(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { network.shutdownNow(); super.onDestroy(); }

    private static final class PendingCapture {
        final String captureId; final long wallTime; final long monotonicMs; final List<File> files;
        PendingCapture(String id, long wall, long mono, List<File> files) { captureId = id; wallTime = wall; monotonicMs = mono; this.files = files; }
    }
}

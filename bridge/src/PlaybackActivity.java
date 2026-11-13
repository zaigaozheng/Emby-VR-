package com.rplayer.embybridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackActivity extends Activity {
    private static final String PLAYER_PACKAGE = "com.wangyuehuan.rplayer";
    private static final String PLAYER_HOME = PLAYER_PACKAGE + ".activity.HomeActivity";
    private ExecutorService probeExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent request = getIntent();
        if (Intent.ACTION_MAIN.equals(request.getAction())) {
            showMessage("转接入口已启用。请回到 Emby，在外部播放器列表中选择 rPlayer（Emby）。");
            return;
        }
        Uri stream = request.getData();
        if (!Intent.ACTION_VIEW.equals(request.getAction()) || stream == null) {
            showMessage("没有收到视频地址。请从 Emby 的外部播放器菜单打开影片。");
            return;
        }

        String scheme = stream.getScheme();
        boolean isContent = "content".equals(scheme);
        boolean isNetwork = "http".equals(scheme) || "https".equals(scheme);
        if ((!isContent && !isNetwork) || stream.getHost() == null
                || stream.getHost().isEmpty()) {
            showMessage("此视频地址不可用。支持 HTTP、HTTPS 和内容 URI。");
            return;
        }

        String mimeType = request.getType();
        if (mimeType == null || !mimeType.startsWith("video/")) {
            mimeType = "video/*";
        }

        Uri directStream = EmbyStream.directStreamFor(stream);
        if (directStream == null) {
            openPlayer(stream, mimeType);
            return;
        }

        String requestedType = mimeType;
        probeExecutor = Executors.newSingleThreadExecutor();
        probeExecutor.execute(() -> {
            boolean seekable = EmbyStream.supportsSeeking(directStream);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    openPlayer(seekable ? directStream : stream, requestedType);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (probeExecutor != null) {
            probeExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void openPlayer(Uri stream, String mimeType) {
        boolean isContent = "content".equals(stream.getScheme());
        // HomeActivity accepts a typed SEND with EXTRA_STREAM. Leave its data
        // field empty so Android matches the original video-sharing filter.
        Intent playback = new Intent(Intent.ACTION_SEND)
                .setClassName(PLAYER_PACKAGE, PLAYER_HOME)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, stream);
        if (isContent) {
            playback.setClipData(ClipData.newRawUri("", stream));
            playback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            startActivity(playback);
            finish();
        } catch (ActivityNotFoundException unavailable) {
            showMessage(isPlayerInstalled()
                    ? "已安装的 rPlayer 无法接收播放请求。此转接包适用于 rPlayer 1.1.2。"
                    : "没有找到原版 rPlayer。请先安装原版再试。");
        } catch (SecurityException denied) {
            showMessage(isContent
                    ? "Android 未授权 rPlayer 读取该视频。请从 Emby 重新打开影片。"
                    : "Android 拒绝启动 rPlayer。请先在手机上打开一次原版 rPlayer 再试。");
        }
    }

    private boolean isPlayerInstalled() {
        try {
            getPackageManager().getApplicationInfo(PLAYER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private void showMessage(String message) {
        new AlertDialog.Builder(this)
                .setTitle("rPlayer（Emby）")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, button) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}

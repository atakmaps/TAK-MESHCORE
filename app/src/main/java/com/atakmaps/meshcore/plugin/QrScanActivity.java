package com.atakmaps.meshcore.plugin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import com.atakmap.android.ipc.AtakBroadcast;

/**
 * Native QR scanner — uses the device camera directly with ZXing core decoding.
 * No AndroidX or external app required. Requests CAMERA permission at runtime.
 */
@SuppressWarnings("deprecation")  // Camera1 API — no AndroidX CameraX available in this context
public class QrScanActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "MeshCore.QrScan";
    private static final int RC_CAMERA = 1;

    private Camera camera;
    private SurfaceView surfaceView;
    private boolean decoding = false;
    private boolean resultSent = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final QRCodeReader qrReader = new QRCodeReader();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView prompt = new TextView(this);
        prompt.setText("Point camera at QR code");
        prompt.setTextColor(Color.WHITE);
        prompt.setTextSize(16f);
        prompt.setBackgroundColor(0xAA000000);
        prompt.setPadding(24, 16, 24, 16);
        FrameLayout.LayoutParams promptLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        promptLp.topMargin = 64;
        root.addView(prompt, promptLp);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            broadcastResult(null);
            finish();
        });
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cancelLp.bottomMargin = 64;
        root.addView(cancel, cancelLp);

        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        if (requestCode == RC_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Log.w(TAG, "Camera permission denied");
            broadcastResult(null);
            finish();
        }
    }

    private void openCamera() {
        if (surfaceView.getHolder().getSurface().isValid()) {
            startCameraPreview();
        }
    }

    private void startCameraPreview() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes().contains(
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (params.getSupportedFocusModes().contains(
                    Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(params);
            camera.setPreviewDisplay(surfaceView.getHolder());
            camera.setPreviewCallback(this::onPreviewFrame);
            camera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Camera open failed", e);
            broadcastResult(null);
            finish();
        }
    }

    private void onPreviewFrame(byte[] data, Camera cam) {
        if (decoding || resultSent) return;
        decoding = true;
        Camera.Size size = cam.getParameters().getPreviewSize();
        new Thread(() -> {
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        data, size.width, size.height,
                        0, 0, size.width, size.height, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = qrReader.decode(bitmap);
                String text = result.getText();
                Log.d(TAG, "QR decoded: " + text);
                resultSent = true;
                broadcastResult(text);
                uiHandler.post(this::finish);
            } catch (Exception ignored) {
            } finally {
                decoding = false;
            }
        }).start();
    }

    private void broadcastResult(String content) {
        QrResultProvider.storePending(this, content);
        try {
            Intent broadcast = new Intent(MeshCoreDropDownReceiver.ACTION_QR_CHANNEL_RESULT);
            if (content != null) {
                broadcast.putExtra(MeshCoreDropDownReceiver.EXTRA_QR_RESULT, content);
            }
            AtakBroadcast.getInstance().sendBroadcast(broadcast);
        } catch (Exception e) {
            Log.w(TAG, "AtakBroadcast failed", e);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }
}

package com.jenkinskg.spintach;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

public class SpinTachActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int CAMERA_REQUEST = 802;
    private static final int BASE = Color.rgb(8, 15, 34);
    private static final int TEXT = Color.rgb(219, 232, 250);
    private final Object meterLock = new Object();
    private TextureView texture;
    private TextView speedView, statusView, detailsView, modeButton, cameraButton, hueValue;
    private ReticleView reticle;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private boolean wantsCamera = true;
    private boolean darkMark = true;
    private int ledColor = Color.rgb(38, 255, 99);
    private final ArrayDeque<Long> pulseTimes = new ArrayDeque<>();
    private double minLight = -1, maxLight = -1, lastLight = 0, fps = 0, measuredRpm = 0;
    private boolean armed = false;
    private long lastFrameNs = 0, lastPulseNs = 0, lastUiNs = 0;
    private long revolutions = 0;
    private volatile boolean opening = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BASE);
        getWindow().setNavigationBarColor(BASE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        texture.setSurfaceTextureListener(this);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    private int dp(float x) { return (int)(getResources().getDisplayMetrics().density*x + 0.5f); }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private LinearLayout line() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(13, 29, 62), BASE, Color.rgb(26, 11, 56)}));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = text("SPIN  /  TACHOMETER", 23, Color.WHITE, true);
        heading.setLetterSpacing(0.07f);
        root.addView(heading);
        TextView subtitle = text("CAMERA OPTICAL RPM METER", 12, Color.rgb(117, 202, 250), true);
        subtitle.setLetterSpacing(0.13f);
        root.addView(subtitle);
        gap(root, 14);

        LinearLayout displayCard = new LinearLayout(this);
        displayCard.setOrientation(LinearLayout.VERTICAL);
        displayCard.setGravity(Gravity.CENTER);
        displayCard.setPadding(dp(8), dp(18), dp(8), dp(16));
        GradientDrawable displayBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(3, 13, 19), Color.rgb(8, 26, 34), Color.rgb(3, 11, 20)});
        displayBg.setCornerRadius(dp(20));
        displayBg.setStroke(dp(2), Color.rgb(36, 94, 101));
        displayCard.setBackground(displayBg);
        root.addView(displayCard, new LinearLayout.LayoutParams(-1, -2));

        TextView digitsLabel = text("ROTATIONAL SPEED", 12, Color.rgb(122, 168, 193), true);
        digitsLabel.setLetterSpacing(0.18f);
        displayCard.addView(digitsLabel);
        speedView = text("0.000", 52, ledColor, true);
        speedView.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        speedView.setIncludeFontPadding(false);
        speedView.setGravity(Gravity.CENTER);
        speedView.setSingleLine(true);
        speedView.setAutoSizeTextTypeUniformWithConfiguration(24, 56, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        speedView.setShadowLayer(dp(18), 0, 0, ledColor);
        displayCard.addView(speedView, new LinearLayout.LayoutParams(-1, dp(80)));
        TextView rpmLabel = text("R P M", 18, Color.rgb(177, 208, 229), true);
        rpmLabel.setLetterSpacing(0.3f);
        displayCard.addView(rpmLabel);
        gap(root, 12);

        LinearLayout cameraFrame = new LinearLayout(this);
        cameraFrame.setOrientation(LinearLayout.VERTICAL);
        cameraFrame.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable cameraBg = rounded(Color.rgb(15, 33, 55), 17);
        cameraBg.setStroke(dp(2), Color.rgb(51, 105, 160));
        cameraFrame.setBackground(cameraBg);
        root.addView(cameraFrame, new LinearLayout.LayoutParams(-1, dp(285)));
        FrameLayout viewport = new FrameLayout(this);
        cameraFrame.addView(viewport, new LinearLayout.LayoutParams(-1, -1));
        texture = new TextureView(this);
        texture.setOpaque(true);
        viewport.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        reticle = new ReticleView(this);
        viewport.addView(reticle, new FrameLayout.LayoutParams(-1, -1));

        gap(root, 10);
        statusView = text("Ready — align the crosshair with a contrasting mark", 14,
                Color.rgb(130, 228, 234), true);
        root.addView(statusView);
        detailsView = text("REVOLUTIONS  0     CAMERA  -- FPS     CONTRAST  --", 12, TEXT, false);
        detailsView.setPadding(0, dp(6), 0, dp(3));
        root.addView(detailsView);
        gap(root, 12);

        LinearLayout actions = line();
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        Button toggle = button("PAUSE", Color.rgb(23, 112, 182));
        cameraButton = toggle;
        actions.addView(toggle, weight(1));
        gapHorizontal(actions, 8);
        Button reset = button("RESET", Color.rgb(97, 61, 176));
        actions.addView(reset, weight(1));
        gapHorizontal(actions, 8);
        Button mark = button("DARK MARK", Color.rgb(164, 67, 116));
        modeButton = mark;
        actions.addView(mark, weight(1.3f));
        toggle.setOnClickListener(v -> {
            wantsCamera = !wantsCamera;
            cameraButton.setText(wantsCamera ? "PAUSE" : "START");
            if (wantsCamera) startCamera(); else { stopCamera(); statusView.setText("Camera paused"); }
        });
        reset.setOnClickListener(v -> resetMeter());
        mark.setOnClickListener(v -> {
            darkMark = !darkMark;
            modeButton.setText(darkMark ? "DARK MARK" : "BRIGHT MARK");
            resetMeter();
        });
        gap(root, 16);

        TextView colorLabel = text("LED DISPLAY COLOR", 14, TEXT, true);
        colorLabel.setLetterSpacing(0.08f);
        root.addView(colorLabel);
        LinearLayout swatches = line();
        swatches.setPadding(0, dp(10), 0, dp(4));
        root.addView(swatches);
        int[] colors = { Color.rgb(38,255,99), Color.CYAN, Color.YELLOW,
                Color.rgb(255,134,37), Color.MAGENTA, Color.rgb(177,134,255) };
        int[] hues = { 135, 180, 60, 29, 300, 270 };
        SeekBar hueBar = new SeekBar(this);
        for (int i = 0; i < colors.length; i++) {
            final int h = hues[i];
            final int c = colors[i];
            Button sw = new Button(this);
            GradientDrawable swBg = rounded(c, 100);
            swBg.setStroke(dp(2), Color.rgb(230, 242, 255));
            sw.setBackground(swBg);
            swatches.addView(sw, new LinearLayout.LayoutParams(0, dp(29), 1));
            if(i < colors.length-1) gapHorizontal(swatches, 9);
            sw.setOnClickListener(v -> {
                hueBar.setProgress(h);
                setLedColor(c);
            });
        }
        hueValue = text("Hue: green", 12, Color.rgb(161, 186, 224), false);
        root.addView(hueValue);
        hueBar.setMax(359);
        hueBar.setProgress(135);
        hueBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ledColor));
        hueBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ledColor));
        root.addView(hueBar);
        hueBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) setLedColor(Color.HSVToColor(new float[]{value, .91f, 1f}));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        gap(root, 8);
        TextView instructions = text(
            "HOW TO USE  •  Put one dark or reflective mark on the rotating object. " +
            "Aim the center reticle at the path the mark crosses. " +
            "Switch DARK/BRIGHT to match the mark. Use good, steady lighting. " +
            "Keep clear of moving machinery.", 13, Color.rgb(181, 204, 226), false);
        instructions.setLineSpacing(dp(3), 1f);
        root.addView(instructions);
        gap(root, 9);
        TextView caveat = text(
            "Shows 3 decimal places. Camera frame rate, lighting and motion limit actual precision; " +
            "this is not a calibrated 0.001-RPM instrument.", 12, Color.rgb(255, 194, 132), false);
        root.addView(caveat);
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, -1, w);
    }
    private void gap(LinearLayout l, int h) {
        l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }
    private void gapHorizontal(LinearLayout l, int w) {
        l.addView(new View(this), new LinearLayout.LayoutParams(dp(w), 1));
    }
    private Button button(String label, int fill) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setBackground(rounded(fill, 12));
        return b;
    }
    private void setLedColor(int color) {
        ledColor = color;
        speedView.setTextColor(color);
        speedView.setShadowLayer(dp(18), 0, 0, color);
        // The text below uses the actual HSV value, without assuming a limited palette.
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hueValue.setText(String.format(Locale.US,"Hue: %d°  •  Custom LED color", Math.round(hsv[0])));
    }

    private void resetMeter() {
        synchronized(meterLock) {
            minLight = -1; maxLight = -1; lastLight = 0; fps = 0;
            lastFrameNs = 0; lastPulseNs = 0; lastUiNs = 0;
            revolutions = 0; measuredRpm = 0; armed = false;
            pulseTimes.clear();
        }
        speedView.setText("0.000");
        detailsView.setText("REVOLUTIONS  0     CAMERA  -- FPS     CONTRAST  --");
        statusView.setText("Align the center reticle with one mark");
    }

    @Override protected void onResume() {
        super.onResume();
        startThread();
        if (texture != null && texture.isAvailable() && wantsCamera) startCamera();
    }
    @Override protected void onPause() {
        stopCamera();
        stopThread();
        super.onPause();
    }
    private void startThread() {
        if(cameraThread != null) return;
        cameraThread = new HandlerThread("SpinTachCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }
    private void stopThread() {
        if(cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(500); } catch (InterruptedException ignored) {}
            cameraThread = null;
            cameraHandler = null;
        }
    }
    private void startCamera() {
        if (!wantsCamera || texture == null || !texture.isAvailable() || camera != null || opening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        startThread();
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            CameraCharacteristics cc = null;
            for(String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics candidate = manager.getCameraCharacteristics(cameraId);
                if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
                        .equals(candidate.get(CameraCharacteristics.LENS_FACING))) {
                    id = cameraId; cc = candidate; break;
                }
            }
            if(id == null) { statusView.setText("No rear camera available"); return; }
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map == null || map.getOutputSizes(ImageFormat.YUV_420_888) == null) {
                statusView.setText("Camera cannot provide analysis frames"); return;
            }
            Size chosen = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            texture.getSurfaceTexture().setDefaultBufferSize(chosen.getWidth(), chosen.getHeight());
            previewSurface = new Surface(texture.getSurfaceTexture());
            imageReader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(reader -> analyzeFrame(reader), cameraHandler);
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    opening = false;
                    if (!wantsCamera || cameraHandler == null) { opened.close(); return; }
                    camera = opened;
                    configureSession(cc);
                }
                @Override public void onDisconnected(CameraDevice opened) {
                    opening = false; opened.close(); camera = null;
                    runOnUiThread(() -> statusView.setText("Camera disconnected"));
                }
                @Override public void onError(CameraDevice opened, int error) {
                    opening = false; opened.close(); camera = null;
                    runOnUiThread(() -> statusView.setText("Camera error: " + error));
                }
            }, cameraHandler);
            statusView.setText("Starting camera • aim at one contrasting mark");
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            opening = false;
            statusView.setText("Camera start failed: " + e.getMessage());
            stopCamera();
        }
    }
    private Size chooseSize(Size[] sizes) {
        Size best = sizes[0];
        long closest = Long.MAX_VALUE;
        for(Size s : sizes) {
            long area = (long)s.getWidth()*s.getHeight();
            long score = Math.abs(area - 640L*480);
            if(score < closest) { closest = score; best = s; }
        }
        return best;
    }
    private void configureSession(CameraCharacteristics characteristics) {
        if(camera == null || previewSurface == null || imageReader == null) return;
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null && ranges.length > 0) {
                Range<Integer> pick = null;
                for(Range<Integer> range : ranges) {
                    if(range.getUpper() >= 30 && range.getUpper() <= 60 &&
                            (pick == null || range.getUpper() > pick.getUpper() ||
                             (range.getUpper().equals(pick.getUpper()) && range.getLower() > pick.getLower()))) {
                        pick = range;
                    }
                }
                if(pick != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick);
            }
            camera.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if(camera == null || !wantsCamera) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        runOnUiThread(() -> statusView.setText("Camera stream failed: " + e.getMessage()));
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    runOnUiThread(() -> statusView.setText("Camera stream not supported"));
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> statusView.setText("Camera setup failed: " + e.getMessage()));
        }
    }
    private void stopCamera() {
        opening = false;
        if(session != null) { try{ session.close(); }catch(Exception ignored){} session = null; }
        if(camera != null) { try{ camera.close(); }catch(Exception ignored){} camera = null; }
        if(imageReader != null) { try{ imageReader.close(); }catch(Exception ignored){} imageReader = null; }
        if(previewSurface != null) { try{ previewSurface.release(); }catch(Exception ignored){} previewSurface = null; }
    }
    private void analyzeFrame(ImageReader reader) {
        Image frame = null;
        try {
            frame = reader.acquireLatestImage();
            if(frame == null) return;
            Image.Plane plane = frame.getPlanes()[0];
            ByteBuffer pixels = plane.getBuffer();
            int w = frame.getWidth(), h = frame.getHeight();
            int stride = plane.getRowStride(), step = plane.getPixelStride();
            int cx = w / 2, cy = h / 2;
            int radius = Math.max(6, Math.min(w, h) / 45);
            int sampleStep = Math.max(1, radius / 5);
            long total = 0; int n = 0;
            for(int y = Math.max(0, cy-radius); y <= Math.min(h-1, cy+radius); y+=sampleStep) {
                for(int x = Math.max(0, cx-radius); x <= Math.min(w-1, cx+radius); x+=sampleStep) {
                    int pos = y*stride + x*step;
                    if (pos >= 0 && pos < pixels.limit()) {
                        total += pixels.get(pos) & 255; n++;
                    }
                }
            }
            if(n == 0) return;
            long timestamp = frame.getTimestamp();
            if(timestamp <= 0) timestamp = SystemClock.elapsedRealtimeNanos();
            processLight((double)total/n, timestamp);
        } catch (IllegalStateException ignored) {
        } finally {
            if(frame != null) frame.close();
        }
    }

    private void processLight(double brightness, long timeNs) {
        final long uiNow = SystemClock.elapsedRealtimeNanos();
        double rpmOut, fpsOut, contrast;
        long turnsOut;
        String statusOut;
        synchronized(meterLock) {
            if(lastFrameNs > 0 && timeNs > lastFrameNs) {
                double instantFps = 1_000_000_000.0/(timeNs-lastFrameNs);
                if(instantFps >= 1 && instantFps < 200) fps = (fps == 0) ? instantFps : fps*.88 + instantFps*.12;
            }
            lastFrameNs = timeNs;
            if(minLight < 0) { minLight = brightness; maxLight = brightness; }
            minLight = Math.min(brightness, minLight + .24);
            maxLight = Math.max(brightness, maxLight - .24);
            contrast = Math.max(0, maxLight - minLight);
            double enter = minLight + contrast*.34;
            double exit = minLight + contrast*.66;
            if(contrast >= 10.0) {
                boolean isReady = darkMark ? (brightness > exit) : (brightness < enter);
                boolean isMark = darkMark ? (brightness < enter) : (brightness > exit);
                if(isReady) armed = true;
                if(armed && isMark) {
                    armed = false;
                    long interval = timeNs - lastPulseNs;
                    if(lastPulseNs == 0 || interval >= 75_000_000L) {
                        if(lastPulseNs != 0 && interval > 4_000_000_000L) pulseTimes.clear();
                        lastPulseNs = timeNs;
                        revolutions++;
                        pulseTimes.addLast(timeNs);
                        while(pulseTimes.size() > 12) pulseTimes.removeFirst();
                        if(pulseTimes.size() >= 2) {
                            long period = pulseTimes.peekLast() - pulseTimes.peekFirst();
                            if(period > 0) {
                                measuredRpm = 60_000_000_000.0*(pulseTimes.size()-1) / period;
                            }
                        }
                    }
                }
            } else {
                armed = false;
            }
            if(lastPulseNs != 0 && timeNs - lastPulseNs > 3_000_000_000L) {
                measuredRpm = 0;
                pulseTimes.clear();
            }
            rpmOut = measuredRpm;
            fpsOut = fps;
            turnsOut = revolutions;
            if(contrast < 10) statusOut = "LOW CONTRAST • center the target on the mark";
            else if(pulseTimes.size() < 2) statusOut = "Mark visible • counting revolutions…";
            else statusOut = "TRACKING • live camera measurement";
            if(uiNow - lastUiNs < 120_000_000L) return;
            lastUiNs = uiNow;
        }
        final double c = contrast;
        runOnUiThread(() -> {
            if(!wantsCamera || isFinishing()) return;
            speedView.setText(String.format(Locale.US, "%,.3f", rpmOut));
            statusView.setText(statusOut);
            detailsView.setText(String.format(Locale.US,
                    "REVOLUTIONS  %,d     CAMERA  %.0f FPS     CONTRAST  %.0f", turnsOut, fpsOut, c));
            reticle.setTracking(c >= 10);
        });
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int width, int height) {
        if (wantsCamera) startCamera();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
        stopCamera(); return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if(requestCode == CAMERA_REQUEST) {
            if(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else statusView.setText("Camera permission required • enable in Android Settings");
        }
    }

    private class ReticleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean tracking;
        ReticleView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        void setTracking(boolean yes) { if(tracking != yes) {tracking = yes; invalidate();} }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth()/2f, cy = getHeight()/2f;
            int color = tracking ? Color.rgb(38, 255, 110) : Color.rgb(255, 191, 66);
            p.setColor(color); p.setStrokeWidth(dp(2)); p.setStyle(Paint.Style.STROKE);
            p.setShadowLayer(dp(8), 0, 0, color);
            canvas.drawCircle(cx, cy, dp(21), p);
            canvas.drawLine(cx-dp(45), cy, cx-dp(25), cy, p);
            canvas.drawLine(cx+dp(25), cy, cx+dp(45), cy, p);
            canvas.drawLine(cx, cy-dp(45), cx, cy-dp(25), p);
            canvas.drawLine(cx, cy+dp(25), cx, cy+dp(45), p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.FILL); p.setTextSize(dp(12));
            p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            canvas.drawText("TARGET  •  MARK PASSAGE", dp(13), getHeight()-dp(15), p);
        }
    }
}

package com.jenkinskg.spintach;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.TypedValue;
import java.util.Locale;

public final class TurntableActivity extends Activity implements SensorEventListener {
    private static final double RAD_TO_RPM = 30.0 / Math.PI;
    private static final int BG = Color.rgb(9, 16, 38);
    private static final int SECONDARY = Color.rgb(177, 205, 232);

    private SensorManager sensorManager;
    private Sensor gyroscope;
    private TextView rpmText, directionText, statusText, diagnosticText, colorName;
    private Button stabilizeButton, zeroButton;
    private Dial dial;
    private FrameLayout stableGauge;
    private boolean stabilizeDigits = true;
    private int ledColor;

    private double biasZ = 0.0;
    private double smoothedRpm = 0.0;
    private double accumulatedAngle = 0.0;
    private long priorEventTime = 0L, priorUiTime = 0L;
    private boolean hasMeasurement = false;

    private boolean zeroCalibrating = false;
    private long calibrationStart = 0L;
    private int calibrationCount = 0;
    private double calibrationSum = 0, calibrationPeak = 0;
    private String feedback = "Hold still and tap ZERO before starting the turntable.";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ledColor = getIntent().getIntExtra("ledColor", Color.rgb(38,255,99));
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        buildInterface();
        if (gyroscope == null) {
            statusText.setText("No gyroscope detected on this device");
            diagnosticText.setText("This mode needs a physical gyroscope sensor.");
            zeroButton.setEnabled(false);
        } else {
            diagnosticText.setText(String.format(Locale.US,
                    "GYRO LIMIT: approximately %.1f RPM   •   3 decimals are display resolution",
                    gyroscope.getMaximumRange()*RAD_TO_RPM));
        }
    }

    private int dp(float x) { return (int)(getResources().getDisplayMetrics().density*x+0.5f); }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if(bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private GradientDrawable panel(int fill, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(border != 0) d.setStroke(dp(1.5f), border);
        return d;
    }
    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setBackground(panel(color, 0, 14));
        return b;
    }
    private void space(LinearLayout l, int height) {
        l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }
    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(19,39,76), BG, Color.rgb(37,14,68)}));
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(17), dp(16), dp(17), dp(26));
        scroll.addView(main);
        setContentView(scroll);

        Button back = button("◀  CAMERA MODE", Color.rgb(38,68,115));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(43));
        main.addView(back, backLp);
        back.setOnClickListener(v -> finish());
        space(main, 14);
        TextView heading = label("VINYL  /  TURNTABLE", 22, Color.WHITE, true);
        heading.setLetterSpacing(.065f);
        main.addView(heading);
        TextView subtitle = label("GYROSCOPE  •  NO CAMERA NEEDED", 12, Color.rgb(113,224,248), true);
        subtitle.setLetterSpacing(.11f);
        main.addView(subtitle);
        space(main, 15);

        FrameLayout readout = new FrameLayout(this);
        GradientDrawable readoutBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(2,14,27), Color.rgb(7,31,33), Color.rgb(9,13,24)});
        readoutBg.setCornerRadius(dp(26));
        readoutBg.setStroke(dp(2), Color.rgb(40,105,97));
        readout.setBackground(readoutBg);
        main.addView(readout, new LinearLayout.LayoutParams(-1, dp(335)));

        // The circular gauge, digits and direction label rotate together in the
        // opposite direction to the physical platter. The stationary rounded
        // outer panel is symmetric; the information stays upright in the room.
        stableGauge = new FrameLayout(this);
        stableGauge.setClipChildren(false);
        stableGauge.setClipToPadding(false);
        readout.addView(stableGauge, new FrameLayout.LayoutParams(-1,-1));
        dial = new Dial(this);
        stableGauge.addView(dial, new FrameLayout.LayoutParams(-1,-1));
        rpmText = label("0.000", 46, ledColor, true);
        rpmText.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        rpmText.setIncludeFontPadding(false);
        rpmText.setGravity(Gravity.CENTER);
        rpmText.setSingleLine(true);
        rpmText.setAutoSizeTextTypeUniformWithConfiguration(25, 50, 1,
                TypedValue.COMPLEX_UNIT_SP);
        rpmText.setShadowLayer(dp(15), 0, 0, ledColor);
        FrameLayout.LayoutParams digitsLp = new FrameLayout.LayoutParams(
                -1, dp(320), Gravity.CENTER);
        stableGauge.addView(rpmText, digitsLp);

        directionText = label("PLATTER STOPPED", 15, Color.rgb(255,199,113), true);
        directionText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams directionLp = new FrameLayout.LayoutParams(-1,dp(34),Gravity.TOP);
        directionLp.topMargin = dp(236);
        stableGauge.addView(directionText,directionLp);

        space(main, 10);
        space(main, 10);
        statusText = label(feedback, 14, Color.rgb(137,222,225), true);
        main.addView(statusText);
        space(main, 6);
        diagnosticText = label("GYROSCOPE: checking…", 12, SECONDARY, false);
        main.addView(diagnosticText);
        space(main, 17);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        main.addView(controls, new LinearLayout.LayoutParams(-1, dp(48)));
        zeroButton = button("ZERO • STILL", Color.rgb(15,135,115));
        controls.addView(zeroButton,new LinearLayout.LayoutParams(0,-1,1));
        View separator = new View(this);
        controls.addView(separator, new LinearLayout.LayoutParams(dp(10),1));
        stabilizeButton = button("UPRIGHT GAUGE: ON", Color.rgb(112,67,183));
        controls.addView(stabilizeButton,new LinearLayout.LayoutParams(0,-1,1.4f));

        zeroButton.setOnClickListener(v -> {
            zeroCalibrating = true;
            calibrationStart = 0;
            calibrationSum = 0;
            calibrationPeak = 0;
            calibrationCount = 0;
            feedback = "ZEROING • leave the phone completely still for 2 seconds";
            statusText.setText(feedback);
            zeroButton.setEnabled(false);
        });
        stabilizeButton.setOnClickListener(v -> {
            stabilizeDigits = !stabilizeDigits;
            stabilizeButton.setText(stabilizeDigits ? "UPRIGHT GAUGE: ON" : "UPRIGHT GAUGE: OFF");
            // Re-align to the current phone position if the feature is enabled.
            accumulatedAngle = 0;
            stableGauge.setRotation(0f);
        });

        space(main, 19);
        TextView ledLabel = label("CHOOSE LED DISPLAY COLOR", 13, Color.WHITE, true);
        main.addView(ledLabel);
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colorsLp = new LinearLayout.LayoutParams(-1,dp(43));
        colorsLp.topMargin = dp(6);
        main.addView(colors, colorsLp);
        int[] colorList = {Color.rgb(38,255,99), Color.CYAN, Color.YELLOW,
                Color.rgb(255,147,39), Color.MAGENTA, Color.rgb(165,127,255)};
        int[] colorHues = {135,180,60,30,300,270};
        SeekBar hue = new SeekBar(this);
        for(int i=0;i<colorList.length;i++) {
            final int color=colorList[i], val=colorHues[i];
            View swatch = new View(this);
            GradientDrawable bg = panel(color, Color.WHITE, 8);
            swatch.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(26),1);
            lp.setMargins(dp(3),0,dp(3),0);
            colors.addView(swatch,lp);
            swatch.setOnClickListener(v -> { hue.setProgress(val); changeLed(color); });
        }
        colorName = label("Custom color",12, SECONDARY,false);
        main.addView(colorName);
        hue.setMax(359);
        float[] hsv = new float[3];
        Color.colorToHSV(ledColor,hsv);
        hue.setProgress(Math.round(hsv[0]));
        main.addView(hue);
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int value,boolean user) {
                if(user) changeLed(Color.HSVToColor(new float[]{value,.91f,1f}));
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        space(main, 9);
        TextView instruction = label(
                "HOW TO USE  •  With the platter STOPPED, put the phone screen-up near the center on a secure non-slip surface. " +
                "Tap ZERO and leave it still for 2 seconds. Then start the turntable. " +
                "UPRIGHT GAUGE makes the entire illuminated dial stay facing the viewer as the phone spins. " +
                "Use the switch to turn stabilization on or off.", 13,
                SECONDARY, false);
        instruction.setLineSpacing(dp(3),1f);
        main.addView(instruction);
        space(main, 12);
        TextView caution = label(
                "WARNING  •  Keep the phone away from the tonearm and stylus. Do not place it directly on a record. " +
                "Start at low speed; an unsecured phone can be thrown off. " +
                "Phone mass may affect the measured turntable speed. " +
                "Three decimal places do not guarantee 0.001 RPM accuracy.", 12,
                Color.rgb(255,191,135), false);
        caution.setLineSpacing(dp(2),1f);
        main.addView(caution);
    }

    private void changeLed(int newColor) {
        ledColor = newColor;
        rpmText.setTextColor(ledColor);
        rpmText.setShadowLayer(dp(16), 0, 0, ledColor);
        dial.invalidate();
        float[] hsv = new float[3];
        Color.colorToHSV(ledColor,hsv);
        colorName.setText("LED hue: "+Math.round(hsv[0])+"°");
    }

    @Override protected void onResume() {
        super.onResume();
        if(gyroscope != null) {
            priorEventTime=0;
            sensorManager.registerListener(this,gyroscope,SensorManager.SENSOR_DELAY_FASTEST);
        }
    }
    @Override protected void onPause() {
        if(sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if(gyroscope == null || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        double z = event.values[2];
        long time = event.timestamp;
        if(zeroCalibrating) {
            if(calibrationStart == 0) calibrationStart = time;
            calibrationSum += z;
            calibrationPeak = Math.max(calibrationPeak,Math.abs(z));
            calibrationCount++;
            if(time-calibrationStart>=2_000_000_000L) {
                zeroCalibrating=false;
                zeroButton.setEnabled(true);
                if(calibrationCount < 15 || calibrationPeak > .25) {
                    feedback="ZERO FAILED • keep the platter STOPPED and try again";
                } else {
                    biasZ=calibrationSum/calibrationCount;
                    smoothedRpm=0;
                    hasMeasurement=false;
                    priorEventTime=0;
                    accumulatedAngle=0;
                    stableGauge.setRotation(0f);
                    feedback="CALIBRATED • start the turntable to measure RPM";
                }
                statusText.setText(feedback);
            }
            return;
        }

        double corrected = z-biasZ;
        double dt = priorEventTime>0 ? (time-priorEventTime)/1_000_000_000.0 : 0;
        priorEventTime=time;
        if(dt>0 && dt<.25) {
            accumulatedAngle = Math.IEEEremainder(accumulatedAngle + corrected*dt, Math.PI*2.0);
            // Android view rotation is clockwise-positive. Gyro Z reports
            // counterclockwise-positive with screen facing up, so rotating
            // the gauge by +integrated Z cancels phone rotation in the room.
            if(stabilizeDigits) {
                stableGauge.setRotation((float)Math.toDegrees(accumulatedAngle));
            }
            double actual = corrected*RAD_TO_RPM;
            if(!hasMeasurement) { smoothedRpm=actual; hasMeasurement=true; }
            else {
                double smoothing=1-Math.exp(-dt/.55);
                smoothedRpm += smoothing*(actual-smoothedRpm);
            }
        }
        if(time-priorUiTime < 60_000_000L) return;
        priorUiTime=time;
        double current=Math.abs(smoothedRpm);
        if(current<.008) current=0;
        rpmText.setText(String.format(Locale.US,"%,.3f",current));
        if(Math.abs(smoothedRpm)<.08) directionText.setText("PLATTER STOPPED");
        else directionText.setText(smoothedRpm<0 ? "CLOCKWISE  ↻" : "COUNTERCLOCKWISE  ↺");

        double limit = gyroscope.getMaximumRange()*RAD_TO_RPM;
        if(Math.abs(z) > gyroscope.getMaximumRange()*.965) {
            statusText.setText("GYRO AT LIMIT • RPM may be inaccurate");
        } else if(Math.abs(smoothedRpm)>=.08) {
            statusText.setText("LIVE  •  gyroscope measuring rotation");
        } else {
            statusText.setText(feedback);
        }
        diagnosticText.setText(String.format(Locale.US,
                "SENSOR MAX: %.1f RPM   |   OFFSET: %.4f RPM   |   FAST SENSOR",
                limit, biasZ*RAD_TO_RPM));
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}

    private class Dial extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Dial(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float radius=Math.min(getWidth(),getHeight())*.43f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(155,Color.red(ledColor),Color.green(ledColor),Color.blue(ledColor)));
            canvas.drawCircle(cx,cy,radius,paint);
            paint.setColor(Color.rgb(38,78,88));
            paint.setStrokeWidth(dp(1));
            canvas.drawCircle(cx,cy,radius-dp(12),paint);
            for(int i=0;i<60;i++) {
                double angle=2*Math.PI*i/60-Math.PI/2;
                float x1=cx+(float)Math.cos(angle)*(radius-dp(i%5==0?15:8));
                float y1=cy+(float)Math.sin(angle)*(radius-dp(i%5==0?15:8));
                float x2=cx+(float)Math.cos(angle)*(radius-dp(2));
                float y2=cy+(float)Math.sin(angle)*(radius-dp(2));
                paint.setColor(i%5==0?ledColor:Color.rgb(61,114,131));
                paint.setStrokeWidth(dp(i%5==0?2:1));
                canvas.drawLine(x1,y1,x2,y2,paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(153,190,204));
            paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            paint.setTextSize(dp(13));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("LIVE  RPM",cx,cy-radius*.50f,paint);
        }
    }
}

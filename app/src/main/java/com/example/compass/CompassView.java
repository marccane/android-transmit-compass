package com.example.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class CompassView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleNorthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleSouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint degreePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needlePath = new Path();

    // Current heading in degrees 0-360 (0=North, 90=East)
    private float azimuth = 0f;

    public CompassView(Context context) {
        super(context);
        init();
    }

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CompassView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.parseColor("#1A1A2E"));
        bgPaint.setStyle(Paint.Style.FILL);

        ringPaint.setColor(Color.parseColor("#4A90E2"));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(6f);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        cardinalPaint.setColor(Color.WHITE);
        cardinalPaint.setTextAlign(Paint.Align.CENTER);
        cardinalPaint.setFakeBoldText(true);

        northPaint.setColor(Color.RED);
        northPaint.setTextAlign(Paint.Align.CENTER);
        northPaint.setFakeBoldText(true);

        needleNorthPaint.setColor(Color.RED);
        needleNorthPaint.setStyle(Paint.Style.FILL);
        needleNorthPaint.setAntiAlias(true);

        needleSouthPaint.setColor(Color.WHITE);
        needleSouthPaint.setStyle(Paint.Style.FILL);
        needleSouthPaint.setAntiAlias(true);

        centerPaint.setColor(Color.parseColor("#4A90E2"));
        centerPaint.setStyle(Paint.Style.FILL);

        degreePaint.setColor(Color.WHITE);
        degreePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setAzimuth(float azimuth) {
        this.azimuth = azimuth;
        invalidate();
    }

    public float getAzimuth() {
        return azimuth;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Keep it square
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(cx, cy) * 0.88f;

        // Background
        canvas.drawCircle(cx, cy, radius, bgPaint);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // Inner guide ring
        Paint innerRing = new Paint(ringPaint);
        innerRing.setAlpha(60);
        innerRing.setStrokeWidth(1f);
        canvas.drawCircle(cx, cy, radius * 0.75f, innerRing);

        // --- Tick marks and labels (fixed, N always at top) ---
        float cardinalSize = radius * 0.16f;
        cardinalPaint.setTextSize(cardinalSize);
        northPaint.setTextSize(cardinalSize);

        for (int deg = 0; deg < 360; deg += 5) {
            float angleRad = (float) Math.toRadians(deg);
            float sin = (float) Math.sin(angleRad);
            float cos = -(float) Math.cos(angleRad);

            float outerR = radius - 5f;
            float innerR;
            float tickWidth;
            int tickColor;

            if (deg % 90 == 0) {
                innerR = radius * 0.78f;
                tickWidth = 4f;
                tickColor = Color.WHITE;
            } else if (deg % 45 == 0) {
                innerR = radius * 0.82f;
                tickWidth = 3f;
                tickColor = Color.parseColor("#AACCFF");
            } else if (deg % 10 == 0) {
                innerR = radius * 0.87f;
                tickWidth = 2f;
                tickColor = Color.parseColor("#4A90E2");
            } else {
                innerR = radius * 0.90f;
                tickWidth = 1f;
                tickColor = Color.parseColor("#2A5090");
            }

            tickPaint.setStrokeWidth(tickWidth);
            tickPaint.setColor(tickColor);
            canvas.drawLine(
                    cx + sin * innerR, cy + cos * innerR,
                    cx + sin * outerR, cy + cos * outerR,
                    tickPaint
            );

            // Cardinal / intercardinal labels
            if (deg % 45 == 0) {
                String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
                String label = labels[deg / 45];
                float labelR = radius * 0.63f;
                float lx = cx + sin * labelR;
                float ly = cy + cos * labelR + cardinalSize * 0.35f;

                if (deg == 0) {
                    canvas.drawText(label, lx, ly, northPaint);
                } else {
                    Paint p = cardinalPaint;
                    p.setTextSize(deg % 90 == 0 ? cardinalSize : cardinalSize * 0.65f);
                    canvas.drawText(label, lx, ly, p);
                }
            }

            // Degree numbers every 30°
            if (deg % 30 == 0 && deg != 0) {
                float numSize = radius * 0.07f;
                degreePaint.setTextSize(numSize);
                float numR = radius * 0.50f;
                float nx = cx + sin * numR;
                float ny = cy + cos * numR + numSize * 0.35f;
                canvas.drawText(String.valueOf(deg), nx, ny, degreePaint);
            }
        }

        // --- Needle (rotates with azimuth) ---
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(azimuth); // rotate so needle points to current heading

        float needleLen = radius * 0.55f;
        float needleShort = radius * 0.25f;
        float needleWidth = radius * 0.05f;

        // North half (red, points up when azimuth=0)
        needlePath.reset();
        needlePath.moveTo(0, -needleLen);
        needlePath.lineTo(-needleWidth, 0);
        needlePath.lineTo(needleWidth, 0);
        needlePath.close();
        canvas.drawPath(needlePath, needleNorthPaint);

        // South half (white)
        needlePath.reset();
        needlePath.moveTo(0, needleShort);
        needlePath.lineTo(-needleWidth, 0);
        needlePath.lineTo(needleWidth, 0);
        needlePath.close();
        canvas.drawPath(needlePath, needleSouthPaint);

        canvas.restore();

        // Center dot
        canvas.drawCircle(cx, cy, radius * 0.04f, centerPaint);

        // Degree readout in center
        float degSize = radius * 0.11f;
        degreePaint.setTextSize(degSize);
        degreePaint.setFakeBoldText(true);
        degreePaint.setColor(Color.WHITE);
        canvas.drawText(String.format("%.1f°", azimuth), cx, cy + radius * 0.22f, degreePaint);
    }
}

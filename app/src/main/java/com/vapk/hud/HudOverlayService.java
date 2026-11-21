package com.vapk.hud;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class HudOverlayService extends Service {
    private static volatile HudOverlayService instance;
    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView title;
    private TextView output;
    private WindowManager.LayoutParams params;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createWindow();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        return view;
    }

    private void createWindow() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(2), dp(2), dp(2), dp(2));
        panel.setBackgroundColor(Color.rgb(12, 20, 34));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(20, 65, 105));

        title = text("V-APK • HUD", 14, Color.WHITE);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));

        TextView close = text("×", 22, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        bar.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        close.setOnClickListener(v -> stopSelf());

        output = text("HUD ativa. Scripts podem controlar apps autorizados.", 12,
            Color.rgb(224, 235, 248));
        output.setMinHeight(dp(64));
        output.setMaxWidth(dp(280));
        panel.addView(bar);
        panel.addView(output);

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            dp(290),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(10);
        params.y = dp(100);

        bar.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = params.x;
                    startY = params.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX + (int) (event.getRawX() - touchX);
                    params.y = startY + (int) (event.getRawY() - touchY);
                    if (panel != null && panel.getParent() != null) {
                        windowManager.updateViewLayout(panel, params);
                    }
                    return true;
                }
                return false;
            }
        });

        windowManager.addView(panel, params);
    }

    public static void command(String command, String value) {
        HudOverlayService service = instance;
        if (service == null) return;
        service.mainHandler.post(() -> service.apply(command, value == null ? "" : value));
    }

    private void apply(String command, String value) {
        if (panel == null) return;
        switch (command) {
            case "log":
                String current = output.getText().toString();
                String next = current + "\n" + value;
                if (next.length() > 1200) next = next.substring(next.length() - 1200);
                output.setText(next);
                break;
            case "setText":
                output.setText(value);
                break;
            case "setTitle":
                title.setText(value);
                break;
            case "setColor":
                try {
                    panel.setBackgroundColor(Color.parseColor(value));
                } catch (IllegalArgumentException error) {
                    output.setText("Cor inválida: " + value);
                }
                break;
            case "setVisible":
                panel.setVisibility(Boolean.parseBoolean(value) ? View.VISIBLE : View.GONE);
                break;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (panel != null && panel.getParent() != null) windowManager.removeView(panel);
        panel = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

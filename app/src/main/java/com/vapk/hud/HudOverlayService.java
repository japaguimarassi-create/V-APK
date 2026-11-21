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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class HudOverlayService extends Service {
    private WindowManager windowManager;
    private View overlay;
    private TextView titleView;
    private TextView bodyView;
    private LinearLayout panel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static HudOverlayService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        return view;
    }

    private void createOverlay() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(13, 22, 39));
        panel.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.rgb(20, 49, 78));

        titleView = label("V-APK | IA EM ESPERA", 13, Color.WHITE);
        titleView.setTypeface(null, 1);
        header.addView(titleView, new LinearLayout.LayoutParams(0, dp(40), 1));

        TextView close = label("×", 22, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(40)));
        close.setOnClickListener(v -> stopSelf());

        bodyView = label("Arquivo analisado. IA aguardando acionamento.", 12,
                Color.rgb(220, 232, 245));
        bodyView.setMinHeight(dp(58));
        bodyView.setMaxWidth(dp(260));
        panel.addView(header);
        panel.addView(bodyView);

        Button activate = new Button(this);
        activate.setText("LIGAR IA + EXECUTAR");
        activate.setTextSize(11);
        activate.setAllCaps(false);
        activate.setTextColor(Color.WHITE);
        activate.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(26, 104, 177)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        buttonParams.setMargins(dp(5), dp(3), dp(5), dp(5));
        panel.addView(activate, buttonParams);
        activate.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_HUD_START, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(270),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(110);

        header.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - touchX);
                        params.y = initialY + (int) (event.getRawY() - touchY);
                        if (overlay != null && overlay.getParent() != null) {
                            windowManager.updateViewLayout(overlay, params);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        overlay = panel;
        windowManager.addView(overlay, params);
    }

    public static void command(String command, String value) {
        HudOverlayService service = instance;
        if (service == null) return;
        service.handler.post(() -> service.apply(command, value));
    }

    private void apply(String command, String value) {
        if (overlay == null) return;
        switch (command) {
            case "log":
                bodyView.setText(bodyView.getText() + "\n" + value);
                break;
            case "setText":
            case "setStatus":
                bodyView.setText(value);
                break;
            case "setTitle":
                titleView.setText(value);
                break;
            case "setColor":
                try {
                    panel.setBackgroundColor(Color.parseColor(value));
                } catch (IllegalArgumentException ignored) {
                    bodyView.setText("Cor inválida: " + value);
                }
                break;
            case "setVisible":
                overlay.setVisibility(Boolean.parseBoolean(value) ? View.VISIBLE : View.GONE);
                break;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (overlay != null && overlay.getParent() != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

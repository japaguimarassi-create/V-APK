package com.vapk.hud;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

public final class HudOverlayService extends Service {
    private static volatile HudOverlayService instance;
    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView title;
    private TextView output;
    private WindowManager.LayoutParams params;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelScript = new AtomicBoolean(false);
    private volatile boolean scriptRunning;
    private Thread scriptThread;

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
        view.setPadding(dp(9), dp(7), dp(9), dp(7));
        return view;
    }

    private TextView action(String label) {
        TextView view = text(label, 16, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(27, 79, 125));
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

        title = text("V-APK", 13, Color.WHITE);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));

        TextView run = action("▶");
        bar.addView(run, new LinearLayout.LayoutParams(dp(42), dp(38)));
        run.setOnClickListener(v -> startSavedScript());

        TextView stop = action("■");
        bar.addView(stop, new LinearLayout.LayoutParams(dp(42), dp(38)));
        stop.setOnClickListener(v -> stopSavedScript());

        TextView close = text("×", 22, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        bar.addView(close, new LinearLayout.LayoutParams(dp(38), dp(42)));
        close.setOnClickListener(v -> stopSelf());

        output = text("HUD pronta. Abra o app-alvo e toque em ▶.", 11,
            Color.rgb(224, 235, 248));
        output.setMinHeight(dp(58));
        output.setMaxWidth(dp(290));
        panel.addView(bar);
        panel.addView(output);

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            dp(300),
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

        title.setOnTouchListener(new View.OnTouchListener() {
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

    private boolean accessibilityIsEnabled() {
        String enabled = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) return false;
        String component = new ComponentName(this, AutomationAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (component.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private void startSavedScript() {
        if (scriptRunning) {
            command("log", "Já existe um script em execução.");
            return;
        }
        String source = getSharedPreferences("vapk", MODE_PRIVATE).getString("active_script", "");
        if (source.trim().isEmpty()) {
            command("log", "Carregue um script no V-APK primeiro.");
            return;
        }

        cancelScript.set(false);
        scriptRunning = true;
        scriptThread = new Thread(() -> {
            try {
                String result = new ScriptEngine(new ScriptEngine.Host() {
                    @Override
                    public void hud(String command, String value) {
                        HudOverlayService.command(command, value);
                    }

                    @Override
                    public String app(String command, String value) throws Exception {
                        if (!accessibilityIsEnabled()) {
                            throw new IllegalStateException(
                                "Ative V-APK Automation nas configurações de Acessibilidade."
                            );
                        }
                        String actionResult = AutomationAccessibilityService.executeCommand(command, value);
                        HudOverlayService.command("log", actionResult);
                        return actionResult;
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelScript.get();
                    }
                }).execute(source);
                command("log", result);
            } catch (Exception error) {
                command("log", "Script encerrado: " + error.getMessage());
            } finally {
                scriptRunning = false;
                scriptThread = null;
            }
        }, "VAPK-HudScript");
        scriptThread.start();
        command("log", "Executando script salvo...");
    }

    private void stopSavedScript() {
        if (!scriptRunning) {
            command("log", "Nenhum script está em execução.");
            return;
        }
        cancelScript.set(true);
        Thread running = scriptThread;
        if (running != null) running.interrupt();
        command("log", "Cancelamento solicitado.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelScript.set(true);
        if (scriptThread != null) scriptThread.interrupt();
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

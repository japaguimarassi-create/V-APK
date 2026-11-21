package com.vapk.hud;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 6101;
    private static final int REQUEST_SAVE = 6102;
    private static final int TRAINING_EPISODES = 150;

    private EditText editor;
    private TextView status;
    private NeuralAgent agent;
    private File modelFile;
    private final AtomicBoolean cancelScript = new AtomicBoolean(false);
    private volatile boolean training;
    private volatile boolean scriptRunning;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelFile = new File(getFilesDir(), "vapk-agent-4x100.bin");
        agent = new NeuralAgent(modelFile);
        buildUi();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(15), dp(15), dp(15), dp(18));
        page.setBackgroundColor(Color.rgb(8, 15, 27));

        TextView heading = new TextView(this);
        heading.setText("V-APK");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(25);
        heading.setTypeface(null, Typeface.BOLD);
        page.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("JAVA HUD  •  CROSS-APP AUTOMATION  •  NEURAL 4×100");
        subtitle.setTextColor(Color.rgb(136, 174, 213));
        subtitle.setTextSize(10);
        subtitle.setPadding(0, dp(3), 0, dp(13));
        page.addView(subtitle);

        LinearLayout first = row(page);
        addButton(first, "Abrir arquivo", v -> openFile());
        addButton(first, "Salvar como", v -> saveFile());

        LinearLayout second = row(page);
        addButton(second, "Ativar HUD", v -> showHud());
        addButton(second, "Acessibilidade", v -> openAccessibilitySettings());

        LinearLayout third = row(page);
        addButton(third, "Executar script", v -> runScript());
        addButton(third, "Parar script", v -> stopScript());

        LinearLayout fourth = row(page);
        addButton(fourth, "Treinar IA", v -> startTraining());
        addButton(fourth, "Parar treino", v -> stopTraining());

        TextView editorLabel = new TextView(this);
        editorLabel.setText("SCRIPT EDITOR");
        editorLabel.setTextColor(Color.rgb(113, 187, 255));
        editorLabel.setTextSize(12);
        editorLabel.setTypeface(null, Typeface.BOLD);
        editorLabel.setPadding(0, dp(13), 0, dp(7));
        page.addView(editorLabel);

        editor = new EditText(this);
        editor.setTextColor(Color.rgb(231, 239, 249));
        editor.setHintTextColor(Color.rgb(113, 132, 154));
        editor.setHint("Escreva ou abra um arquivo Java...");
        editor.setTextSize(12);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setMinLines(16);
        editor.setPadding(dp(11), dp(11), dp(11), dp(11));
        editor.setBackgroundColor(Color.rgb(16, 27, 44));
        page.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(330)));

        TextView help = new TextView(this);
        help.setText(
            "HUD.setTitle(\"V-APK\");\n"
            + "HUD.setText(\"Olá\");\n"
            + "HUD.log(\"Executando\");\n"
            + "HUD.setColor(\"#102030\");\n"
            + "APP.tapText(\"Continuar\");\n"
            + "APP.typeText(\"texto\");\n"
            + "APP.scroll(\"forward\");\n"
            + "APP.back();\n"
            + "APP.waitMs(300);"
        );
        help.setTextColor(Color.rgb(146, 167, 190));
        help.setTextSize(11);
        help.setTypeface(Typeface.MONOSPACE);
        help.setPadding(0, dp(9), 0, dp(8));
        page.addView(help);

        TextView warning = new TextView(this);
        warning.setText("Scripts APP controlam a interface visível por Acessibilidade. "
            + "Não há injeção de código em outros processos; campos de senha não são preenchidos.");
        warning.setTextColor(Color.rgb(218, 180, 115));
        warning.setTextSize(11);
        page.addView(warning);

        status = new TextView(this);
        status.setTextColor(Color.rgb(130, 215, 174));
        status.setTextSize(12);
        status.setPadding(0, dp(10), 0, dp(8));
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);

        editor.setText(
            "public class Example {\n"
            + "    public static void run() {\n"
            + "        HUD.setTitle(\"V-APK / Java\");\n"
            + "        HUD.setText(\"HUD pronta\");\n"
            + "        HUD.log(\"Script iniciado\");\n"
            + "        APP.waitMs(300);\n"
            + "        APP.tapText(\"Continuar\");\n"
            + "    }\n"
            + "}\n"
        );
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                saveScriptToPrefs();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        saveScriptToPrefs();
        refreshStatus();
    }


    private void saveScriptToPrefs() {
        if (editor == null) return;
        String source = editor.getText().toString();
        if (source.length() <= 100_000) {
            getSharedPreferences("vapk", MODE_PRIVATE)
                .edit()
                .putString("active_script", source)
                .apply();
        }
    }

    private LinearLayout row(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        page.addView(row);
        return row;
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(27, 79, 125)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(45), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, params);
        button.setOnClickListener(click);
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private boolean isAccessibilityEnabled() {
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

    private void refreshStatus() {
        setStatus("HUD: " + (hasOverlayPermission() ? "permitida" : "aguardando permissão")
            + "  •  Acessibilidade: " + (isAccessibilityEnabled() ? "ativa" : "desativada")
            + "  •  IA: " + (modelFile.exists() ? "modelo salvo" : "inicializando"));
    }

    private void showHud() {
        if (!hasOverlayPermission()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
            toast("Permita exibir sobre outros apps e toque em Ativar HUD novamente.");
            return;
        }
        startService(new Intent(this, HudOverlayService.class));
        setStatus("HUD flutuante iniciada.");
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        toast("Ative V-APK Automation em Serviços instalados.");
    }

    private void runScript() {
        if (scriptRunning) {
            setStatus("Já existe um script em execução.");
            return;
        }
        if (!hasOverlayPermission()) {
            showHud();
            return;
        }

        saveScriptToPrefs();
        startService(new Intent(this, HudOverlayService.class));
        cancelScript.set(false);
        scriptRunning = true;
        String source = editor.getText().toString();
        setStatus("Validando script...");

        new Thread(() -> {
            try {
                ScriptEngine.Host host = new ScriptEngine.Host() {
                    @Override
                    public void hud(String command, String value) {
                        HudOverlayService.command(command, value);
                    }

                    @Override
                    public String app(String command, String value) throws Exception {
                        if (!isAccessibilityEnabled()) {
                            throw new IllegalStateException(
                                "Ative Acessibilidade antes de executar comandos APP."
                            );
                        }
                        String result = AutomationAccessibilityService.executeCommand(command, value);
                        HudOverlayService.command("log", result);
                        return result;
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelScript.get();
                    }
                };

                String result = new ScriptEngine(host).execute(source);
                runOnUiThread(() -> setStatus(result));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("Script encerrado: " + error.getMessage()));
            } finally {
                scriptRunning = false;
            }
        }, "VAPK-Script").start();
    }

    private void stopScript() {
        if (!scriptRunning) {
            setStatus("Nenhum script está em execução.");
            return;
        }
        cancelScript.set(true);
        setStatus("Cancelamento solicitado.");
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    private void saveFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "vapk-automation.java");
        startActivityForResult(intent, REQUEST_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQUEST_OPEN) {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("Não foi possível abrir o arquivo.");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 100_000) throw new IllegalArgumentException("Limite de 100 KB excedido.");
                }
                editor.setText(output.toString("UTF-8"));
                setStatus("Arquivo carregado.");
            } catch (Exception error) {
                setStatus("Falha ao abrir: " + error.getMessage());
            }
        } else if (requestCode == REQUEST_SAVE) {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Não foi possível salvar o arquivo.");
                output.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                setStatus("Arquivo salvo.");
            } catch (Exception error) {
                setStatus("Falha ao salvar: " + error.getMessage());
            }
        }
    }

    private void startTraining() {
        if (training) {
            setStatus("O treinamento já está em andamento.");
            return;
        }
        training = true;
        setStatus("Treinando agente 4×100 contra inimigos simulados...");
        new Thread(() -> agent.train(modelFile, TRAINING_EPISODES, new NeuralAgent.Listener() {
            @Override
            public void onProgress(int episode, int total, int recentWins, double epsilon) {
                runOnUiThread(() -> setStatus("Episódio " + episode + "/" + total
                    + "  •  vitórias recentes: " + recentWins
                    + "  •  exploração: " + String.format(Locale.US, "%.2f", epsilon)));
            }

            @Override
            public void onComplete(int wins, int games, File saved) {
                training = false;
                runOnUiThread(() -> {
                    if (games == 0) setStatus("Treino parado. Pesos parciais salvos.");
                    else setStatus("Avaliação do simulador: " + wins + "/" + games
                        + " vitórias. Modelo salvo no dispositivo.");
                });
            }

            @Override
            public void onError(Exception error) {
                training = false;
                runOnUiThread(() -> setStatus("Falha no treino: " + error.getMessage()));
            }
        })).start();
    }

    private void stopTraining() {
        if (!training) {
            setStatus("Nenhum treinamento ativo.");
            return;
        }
        agent.stop();
        setStatus("Parada solicitada; salvando o modelo...");
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        cancelScript.set(true);
        if (training && agent != null) agent.stop();
        super.onDestroy();
    }
}

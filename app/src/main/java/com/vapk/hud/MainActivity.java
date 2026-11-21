package com.vapk.hud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    public static final String EXTRA_HUD_START = "com.vapk.hud.HUD_START";
    private static final int REQUEST_OPEN = 4101;
    private static final int REQUEST_SAVE = 4102;
    private static final int DEFAULT_TRAINING_EPISODES = 120;
    private static final int MAX_FILE_BYTES = 100_000;

    private EditText editor;
    private TextView status;
    private NeuralAgent agent;
    private ScriptEngine.Plan pendingPlan;
    private Thread scriptThread;
    private boolean training;
    private volatile boolean scriptRunning;
    private volatile boolean aiActive;
    private File modelFile;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelFile = new File(getFilesDir(), "model_4x100.bin");
        installSeedModel();
        agent = new NeuralAgent(modelFile);
        buildInterface();
        prepareScript();

        if (!modelFile.isFile()) startTraining();
        handleHudIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHudIntent(intent);
    }

    private void handleHudIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_HUD_START, false)) {
            intent.removeExtra(EXTRA_HUD_START);
            executePreparedScript();
        }
    }


    private void installSeedModel() {
        if (modelFile.exists()) return;
        try (InputStream input = getAssets().open("pretrained_4x100.b64");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            byte[] decoded = Base64.decode(output.toByteArray(), Base64.DEFAULT);
            try (java.io.FileOutputStream model = new java.io.FileOutputStream(modelFile)) {
                model.write(decoded);
                model.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void buildInterface() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(14));
        page.setBackgroundColor(Color.rgb(8, 15, 27));

        TextView heading = new TextView(this);
        heading.setText("V-APK  /  NEURAL HUD");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(21);
        heading.setTypeface(null, Typeface.BOLD);
        page.addView(heading);

        TextView subheading = new TextView(this);
        subheading.setText("Java 17 • HUD flutuante • Aprendizado por reforço 4×100");
        subheading.setTextColor(Color.rgb(146, 170, 198));
        subheading.setTextSize(12);
        subheading.setPadding(0, dp(4), 0, dp(14));
        page.addView(subheading);

        LinearLayout rowOne = row(page);
        addButton(rowOne, "Abrir arquivo", v -> openFile());
        addButton(rowOne, "Salvar como", v -> saveFile());

        LinearLayout rowTwo = row(page);
        addButton(rowTwo, "Mostrar HUD", v -> showHud());
        addButton(rowTwo, "Analisar / Esperar", v -> prepareScript());

        LinearLayout rowThree = row(page);
        addButton(rowThree, "Treinar IA", v -> startTraining());
        addButton(rowThree, "Parar treino", v -> stopTraining());

        LinearLayout rowFour = row(page);
        addButton(rowFour, "Parar arquivo", v -> stopScript());

        TextView editorLabel = new TextView(this);
        editorLabel.setText("ARQUIVO JAVA  (.java / .txt / .vapk)");
        editorLabel.setTextColor(Color.rgb(125, 190, 255));
        editorLabel.setTextSize(12);
        editorLabel.setTypeface(null, Typeface.BOLD);
        editorLabel.setPadding(0, dp(14), 0, dp(7));
        page.addView(editorLabel);

        editor = new EditText(this);
        editor.setTextColor(Color.rgb(227, 237, 249));
        editor.setHintTextColor(Color.rgb(110, 130, 153));
        editor.setHint("Cole aqui os comandos da API HUD...");
        editor.setTextSize(13);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setMinLines(14);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackgroundColor(Color.rgb(16, 27, 44));
        page.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(290)));

        TextView api = new TextView(this);
        api.setText("API permitida: HUD.setTitle(\"texto\"); HUD.setText(\"texto\");\n"
            + "HUD.log(\"texto\"); HUD.setColor(\"#123456\");\n"
            + "HUD.setVisible(true); HUD.waitMs(250);\n"
            + "O arquivo é lido e validado primeiro. A execução só começa pelo botão no HUD.");
        api.setTextColor(Color.rgb(145, 165, 188));
        api.setTextSize(11);
        api.setPadding(0, dp(8), 0, dp(8));
        page.addView(api);

        status = new TextView(this);
        status.setText("Inicializando...");
        status.setTextColor(Color.rgb(125, 210, 170));
        status.setTextSize(12);
        status.setPadding(0, dp(8), 0, dp(8));
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);

        editor.setText(
            "public class Example {\n"
            + "    public static void run() {\n"
            + "        HUD.setTitle(\"V-APK / Java\");\n"
            + "        HUD.setText(\"Agente neural pronto.\");\n"
            + "        HUD.log(\"Script iniciado\");\n"
            + "        HUD.setColor(\"#0D1627\");\n"
            + "        HUD.waitMs(250);\n"
            + "        HUD.log(\"Comandos finalizados\");\n"
            + "    }\n"
            + "}\n"
        );
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        parent.addView(row);
        return row;
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(26, 79, 126)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, params);
        button.setOnClickListener(listener);
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void showHud() {
        if (!hasOverlayPermission()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "Autorize a sobreposição e toque em Mostrar HUD novamente.",
                Toast.LENGTH_LONG).show();
            return;
        }
        startService(new Intent(this, HudOverlayService.class));
        setStatus("HUD iniciada. Use o botão LIGAR IA + EXECUTAR.");
    }

    private void prepareScript() {
        try {
            pendingPlan = ScriptEngine.analyze(editor.getText().toString());
            aiActive = false;
            setStatus("ARQUIVO LIDO: " + pendingPlan.summary + " • IA EM ESPERA");
            HudOverlayService.command("setTitle", "V-APK | IA EM ESPERA");
            HudOverlayService.command("setStatus", "Arquivo analisado. Aguardando botão de ativação.");
        } catch (Exception error) {
            pendingPlan = null;
            setStatus("ANÁLISE RECUSADA: " + error.getMessage());
            HudOverlayService.command("setStatus", "Arquivo inválido. Nada foi executado.");
        }
    }

    private void executePreparedScript() {
        if (scriptRunning) {
            setStatus("Já existe um arquivo em execução.");
            return;
        }
        if (pendingPlan == null) {
            prepareScript();
            if (pendingPlan == null) {
                Toast.makeText(this, "Carregue ou corrija um arquivo antes de executar.",
                    Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (!hasOverlayPermission()) {
            showHud();
            return;
        }

        ScriptEngine.Plan plan = pendingPlan;
        scriptRunning = true;
        aiActive = false;
        setStatus("HUD acionada. Aguardando o evento de início do arquivo...");
        scriptThread = new Thread(() -> {
            try {
                String result = ScriptEngine.execute(plan, HudOverlayService::command, () -> {
                    aiActive = true;
                    HudOverlayService.command("setTitle", "V-APK | IA ATIVA");
                    HudOverlayService.command("setStatus", "SCRIPT_STARTED detectado • IA ligada");
                    runOnUiThread(() -> setStatus("INÍCIO DO ARQUIVO DETECTADO — IA ATIVA"));
                });

                runOnUiThread(() -> {
                    aiActive = false;
                    HudOverlayService.command("setTitle", "V-APK | IA EM ESPERA");
                    HudOverlayService.command("setStatus", "Arquivo concluído. Aguardando novo acionamento.");
                    setStatus(result + " • IA voltou ao modo de espera.");
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                runOnUiThread(() -> {
                    aiActive = false;
                    HudOverlayService.command("setTitle", "V-APK | IA EM ESPERA");
                    HudOverlayService.command("setStatus", "Execução cancelada.");
                    setStatus("Execução interrompida.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("Falha na execução: " + error.getMessage()));
            } finally {
                scriptRunning = false;
                scriptThread = null;
            }
        }, "VAPK-Script");
        scriptThread.start();
    }

    private void stopScript() {
        Thread current = scriptThread;
        if (current != null) current.interrupt();
        scriptRunning = false;
        aiActive = false;
        HudOverlayService.command("setTitle", "V-APK | IA EM ESPERA");
        HudOverlayService.command("setStatus", "Execução parada pelo usuário.");
        setStatus("Execução parada.");
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
        intent.putExtra(Intent.EXTRA_TITLE, "vapk-script.java");
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
                    if (output.size() > MAX_FILE_BYTES) {
                        throw new IllegalArgumentException("Limite de 100 KB excedido.");
                    }
                }
                editor.setText(output.toString("UTF-8"));
                prepareScript();
            } catch (Exception error) {
                pendingPlan = null;
                setStatus("Falha ao abrir/analisar: " + error.getMessage());
            }
        } else if (requestCode == REQUEST_SAVE) {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Não foi possível salvar o arquivo.");
                output.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                prepareScript();
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
        setStatus("Treinando rede 4×100 contra inimigos simulados...");
        new Thread(() -> agent.train(modelFile, DEFAULT_TRAINING_EPISODES, new NeuralAgent.Listener() {
            @Override
            public void onProgress(int episode, int total, int recentWins, double epsilon) {
                runOnUiThread(() -> setStatus("Treino " + episode + "/" + total
                    + " • vitórias recentes: " + recentWins
                    + " • exploração: " + String.format(java.util.Locale.US, "%.2f", epsilon)));
            }

            @Override
            public void onComplete(int wins, int games, File file) {
                runOnUiThread(() -> {
                    training = false;
                    if (games == 0) {
                        setStatus("Treino interrompido. Pesos parciais salvos.");
                    } else {
                        setStatus("Treinamento concluído. Avaliação: " + wins + "/" + games
                            + " vitórias. Modelo salvo.");
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    training = false;
                    setStatus("Erro no treinamento: " + error.getMessage());
                });
            }
        })).start();
    }

    private void stopTraining() {
        if (!training) {
            setStatus("Nenhum treinamento ativo.");
            return;
        }
        agent.stop();
        setStatus("Solicitando parada segura e salvamento dos pesos...");
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    @Override
    protected void onDestroy() {
        if (training && agent != null) agent.stop();
        if (scriptThread != null) scriptThread.interrupt();
        super.onDestroy();
    }
}

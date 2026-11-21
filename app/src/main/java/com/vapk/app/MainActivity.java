package com.vapk.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    public static final String EXTRA_HUD_START = "com.vapk.app.HUD_START";
    private static final int PICK_FILE = 41;
    private static final int OVERLAY_PERMISSION = 42;
    private static final int MAX_FILE_BYTES = 131072;

    private final ExecutorService scriptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService trainingExecutor = Executors.newSingleThreadExecutor();
    private final NeuralAgent neuralAgent = new NeuralAgent();
    private final ArenaTrainer trainer = new ArenaTrainer();

    private TextView statusView;
    private TextView fileView;
    private TextView trainingView;
    private TextView logView;
    private ScriptEngine.Plan loadedPlan;
    private String loadedSource;
    private String loadedName = "Nenhum arquivo";
    private Future<?> scriptTask;
    private File modelFile;
    private boolean modelReady;
    private boolean training;
    private boolean scriptRunning;
    private boolean pendingStart;
    private int botX = 5;
    private int botY = 5;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        modelFile = new File(getFilesDir(), "neural-4x100.bin");
        buildInterface();
        restoreScript();

        if (neuralAgent.load(modelFile)) {
            modelReady = true;
            setTrainingText("Modelo 4×100 carregado");
            setStatus("IA EM ESPERA — arquivo pronto");
        } else {
            trainModel(120, true);
        }
        handleIntent(getIntent());
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(20));
        root.setBackgroundColor(Color.rgb(8, 13, 23));

        TextView title = new TextView(this);
        title.setText("V-APK  /  NEURAL SCRIPT RUNNER");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("JAVA 17  •  HUD FLUTUANTE  •  DQN 4×100");
        subtitle.setTextColor(Color.rgb(104, 176, 245));
        subtitle.setTextSize(11);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(5);
        root.addView(subtitle, subParams);

        statusView = makeLabel("Inicializando...");
        statusView.setTextColor(Color.rgb(255, 203, 92));
        addGap(root, 16);
        root.addView(statusView);

        fileView = makeLabel(loadedName);
        fileView.setTextColor(Color.LTGRAY);
        addGap(root, 8);
        root.addView(fileView);

        Button open = makeButton("ABRIR ARQUIVO JAVA");
        open.setOnClickListener(v -> pickFile());
        addGap(root, 14);
        root.addView(open);

        Button run = makeButton("LIGAR IA + EXECUTAR ARQUIVO");
        run.setOnClickListener(v -> startProgram());
        addGap(root, 8);
        root.addView(run);

        Button stop = makeButton("PARAR EXECUÇÃO");
        stop.setOnClickListener(v -> stopProgram());
        addGap(root, 8);
        root.addView(stop);

        Button hud = makeButton("ATIVAR JANELA HUD");
        hud.setOnClickListener(v -> enableHud());
        addGap(root, 8);
        root.addView(hud);

        Button train = makeButton("TREINAR NOVAMENTE CONTRA INIMIGOS");
        train.setOnClickListener(v -> trainModel(250, false));
        addGap(root, 8);
        root.addView(train);

        trainingView = makeLabel("Rede: 8 entradas → 4×100 → 5 ações");
        trainingView.setTextColor(Color.rgb(110, 190, 255));
        addGap(root, 14);
        root.addView(trainingView);

        TextView logTitle = makeLabel("LOG DE EXECUÇÃO");
        logTitle.setTextColor(Color.WHITE);
        logTitle.setTypeface(null, 1);
        addGap(root, 16);
        root.addView(logTitle);

        logView = makeLabel("O arquivo será analisado antes de qualquer execução.");
        logView.setTextColor(Color.rgb(180, 194, 213));
        logView.setTextSize(12);
        logView.setGravity(Gravity.TOP);
        addGap(root, 6);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.rgb(17, 25, 39));
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(logScroll, logParams);

        setContentView(root);
    }

    private TextView makeLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        return view;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        return button;
    }

    private void addGap(LinearLayout parent, int dp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            readAndAnalyze(data.getData());
        }
    }

    private void readAndAnalyze(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Não foi possível abrir o arquivo.");
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_BYTES) throw new IllegalArgumentException("O limite é 128 KB.");
                output.write(buffer, 0, count);
            }

            String source = new String(output.toByteArray(), StandardCharsets.UTF_8);
            ScriptEngine.Plan plan = ScriptEngine.analyze(source);
            loadedSource = source;
            loadedPlan = plan;
            loadedName = queryName(uri);
            getPreferences(MODE_PRIVATE).edit()
                    .putString("script_source", source)
                    .putString("script_name", loadedName)
                    .apply();

            fileView.setText(loadedName + "  •  " + plan.summary);
            setStatus("ARQUIVO LIDO E VALIDADO — IA EM ESPERA");
            setLog("Arquivo analisado: " + loadedName + "\n" + plan.summary
                    + "\nA IA não executará nada até o botão do HUD ser acionado.");
        } catch (Exception error) {
            loadedPlan = null;
            loadedSource = null;
            setStatus("ARQUIVO REJEITADO");
            setLog("Falha na análise: " + error.getMessage());
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return name == null ? "script.java" : name;
    }

    private void restoreScript() {
        String source = getPreferences(MODE_PRIVATE).getString("script_source", null);
        loadedName = getPreferences(MODE_PRIVATE).getString("script_name", "Nenhum arquivo");
        if (source == null) {
            fileView.setText(loadedName);
            return;
        }
        try {
            loadedPlan = ScriptEngine.analyze(source);
            loadedSource = source;
            fileView.setText(loadedName + "  •  " + loadedPlan.summary);
            setStatus("ARQUIVO RESTAURADO — IA EM ESPERA");
        } catch (Exception error) {
            loadedPlan = null;
            setStatus("ARQUIVO ANTERIOR INVÁLIDO");
        }
    }

    private void enableHud() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(permission, OVERLAY_PERMISSION);
            return;
        }
        startService(new Intent(this, FloatingHudService.class));
        setStatus("HUD ATIVO — aguardando comando");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this)) {
            startService(new Intent(this, FloatingHudService.class));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_HUD_START, false)) {
            intent.removeExtra(EXTRA_HUD_START);
            startProgram();
        }
    }

    private synchronized void startProgram() {
        if (scriptRunning) {
            setStatus("ARQUIVO JÁ ESTÁ EM EXECUÇÃO");
            return;
        }
        if (loadedPlan == null) {
            setStatus("CARREGUE E ANALISE UM ARQUIVO PRIMEIRO");
            Toast.makeText(this, "Nenhum arquivo validado.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!modelReady) {
            pendingStart = true;
            setStatus("IA TREINANDO — execução aguardará o modelo");
            return;
        }

        pendingStart = false;
        scriptRunning = true;
        setStatus("AGUARDANDO EVENTO DE INÍCIO DO ARQUIVO...");
        ScriptEngine.Plan plan = loadedPlan;

        scriptTask = scriptExecutor.submit(() -> {
            try {
                ScriptEngine.execute(plan, new BotApi() {
                    @Override
                    public void move(String direction) {
                        synchronized (MainActivity.this) {
                            if ("up".equals(direction)) botY = Math.max(0, botY - 1);
                            if ("down".equals(direction)) botY = Math.min(9, botY + 1);
                            if ("left".equals(direction)) botX = Math.max(0, botX - 1);
                            if ("right".equals(direction)) botX = Math.min(9, botX + 1);
                        }
                        appendLog("MOVE " + direction + "  → posição (" + botX + ", " + botY + ")");
                    }

                    @Override
                    public void attack() {
                        appendLog("ATTACK  → ação enviada ao ambiente virtual");
                    }

                    @Override
                    public void waitMs(int milliseconds) throws InterruptedException {
                        Thread.sleep(milliseconds);
                    }

                    @Override
                    public void log(String message) {
                        appendLog(message);
                    }
                }, () -> runOnUiThread(() -> {
                    setStatus("INÍCIO DETECTADO — IA ATIVA, ARQUIVO ACIONADO");
                    appendLog("Evento SCRIPT_STARTED detectado pelo executor do V-APK.");
                }));
                runOnUiThread(() -> setStatus("ARQUIVO CONCLUÍDO — IA EM ESPERA"));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                runOnUiThread(() -> setStatus("EXECUÇÃO INTERROMPIDA"));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setStatus("ERRO NA EXECUÇÃO");
                    appendLog("Erro: " + error.getMessage());
                });
            } finally {
                synchronized (MainActivity.this) {
                    scriptRunning = false;
                    scriptTask = null;
                }
            }
        });
    }

    private synchronized void stopProgram() {
        if (scriptTask != null) scriptTask.cancel(true);
        scriptRunning = false;
        pendingStart = false;
        setStatus("EXECUÇÃO PARADA");
    }

    private synchronized void trainModel(int episodes, boolean firstTraining) {
        if (training) {
            setStatus("TREINAMENTO JÁ ESTÁ EM ANDAMENTO");
            return;
        }
        training = true;
        if (firstTraining) modelReady = false;
        setTrainingText("Treinando contra inimigos: 0 / " + episodes);
        trainingExecutor.execute(() -> {
            trainer.train(neuralAgent, episodes, (episode, total, average, epsilon) -> {
                if (episode % 2 == 0 || episode == total) {
                    runOnUiThread(() -> setTrainingText(
                            "Episódio " + episode + "/" + total
                                    + "  •  recompensa média " + String.format(java.util.Locale.US, "%.3f", average)
                                    + "  •  exploração " + String.format(java.util.Locale.US, "%.2f", epsilon)));
                }
            });
            boolean saved;
            try {
                neuralAgent.save(modelFile);
                saved = true;
            } catch (Exception error) {
                saved = false;
            }
            boolean modelSaved = saved;
            runOnUiThread(() -> {
                training = false;
                modelReady = true;
                setStatus(modelSaved ? "MODELO TREINADO E SALVO — IA EM ESPERA"
                        : "TREINAMENTO CONCLUÍDO — falha ao salvar modelo");
                if (pendingStart) startProgram();
            });
        });
    }

    private void setStatus(String value) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(value);
        });
    }

    private void setTrainingText(String value) {
        runOnUiThread(() -> {
            if (trainingView != null) trainingView.setText(value);
        });
    }

    private void setLog(String value) {
        if (logView != null) logView.setText(value);
    }

    private void appendLog(String value) {
        runOnUiThread(() -> {
            if (logView == null) return;
            String previous = logView.getText().toString();
            String combined = previous + "\n" + value;
            if (combined.length() > 12000) combined = combined.substring(combined.length() - 12000);
            logView.setText(combined);
        });
    }

    @Override
    protected void onDestroy() {
        if (scriptTask != null) scriptTask.cancel(true);
        scriptExecutor.shutdownNow();
        trainingExecutor.shutdownNow();
        super.onDestroy();
    }
}

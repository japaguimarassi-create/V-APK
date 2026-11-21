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
    private static final int REQUEST_OPEN = 4101;
    private static final int REQUEST_SAVE = 4102;
    private static final int DEFAULT_TRAINING_EPISODES = 120;

    private EditText editor;
    private TextView status;
    private NeuralAgent agent;
    private boolean training;
    private File modelFile;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelFile = new File(getFilesDir(), "model_4x100.bin");
        agent = new NeuralAgent(modelFile);
        buildInterface();
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
        subheading.setText("Java • HUD flutuante • Aprendizado por reforço 4×100");
        subheading.setTextColor(Color.rgb(146, 170, 198));
        subheading.setTextSize(12);
        subheading.setPadding(0, dp(4), 0, dp(14));
        page.addView(subheading);

        LinearLayout rowOne = row(page);
        addButton(rowOne, "Abrir arquivo", v -> openFile());
        addButton(rowOne, "Salvar como", v -> saveFile());
        LinearLayout rowTwo = row(page);
        addButton(rowTwo, "Mostrar HUD", v -> showHud());
        addButton(rowTwo, "Executar", v -> runScript());
        LinearLayout rowThree = row(page);
        addButton(rowThree, "Treinar IA", v -> startTraining());
        addButton(rowThree, "Parar treino", v -> stopTraining());

        TextView editorLabel = new TextView(this);
        editorLabel.setText("EDITOR DE SCRIPTS  (.java / .txt / .vapk)");
        editorLabel.setTextColor(Color.rgb(125, 190, 255));
        editorLabel.setTextSize(12);
        editorLabel.setTypeface(null, Typeface.BOLD);
        editorLabel.setPadding(0, dp(14), 0, dp(7));
        page.addView(editorLabel);

        editor = new EditText(this);
        editor.setTextColor(Color.rgb(227, 237, 249));
        editor.setHintTextColor(Color.rgb(110, 130, 153));
        editor.setHint("Cole aqui os comandos Java da HUD...");
        editor.setTextSize(13);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setSingleLine(false);
        editor.setMinLines(14);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackgroundColor(Color.rgb(16, 27, 44));
        page.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(310)));

        TextView api = new TextView(this);
        api.setText("API permitida: HUD.setTitle("texto");  HUD.setText("texto");\n"
            + "HUD.log("texto");  HUD.setColor("#123456");\n"
            + "HUD.setVisible(true);  HUD.waitMs(250);\n"
            + "Arquivos Java podem conter uma classe e um método run() envolvendo esses comandos.");
        api.setTextColor(Color.rgb(145, 165, 188));
        api.setTextSize(11);
        api.setPadding(0, dp(8), 0, dp(8));
        page.addView(api);

        status = new TextView(this);
        status.setText("Pronto. O modelo neural será salvo no armazenamento privado do app.");
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
            Toast.makeText(this, "Autorize a sobreposição e toque em Mostrar HUD novamente.", Toast.LENGTH_LONG).show();
            return;
        }
        startService(new Intent(this, HudOverlayService.class));
        setStatus("HUD iniciada. Arraste a barra superior para mover.");
    }

    private void runScript() {
        if (!hasOverlayPermission()) {
            showHud();
            return;
        }
        startService(new Intent(this, HudOverlayService.class));
        String source = editor.getText().toString();
        setStatus("Validando e executando comandos permitidos...");
        new Thread(() -> {
            try {
                ScriptEngine engine = new ScriptEngine(HudOverlayService::command);
                String result = engine.execute(source);
                runOnUiThread(() -> setStatus(result));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("Script interrompido: " + error.getMessage()));
            }
        }, "VAPK-Script").start();
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
        setStatus("Treinando rede 4×100 contra inimigos simulados...");
        new Thread(() -> agent.train(modelFile, DEFAULT_TRAINING_EPISODES, new NeuralAgent.Listener() {
            @Override
            public void onProgress(int episode, int total, int recentWins, double epsilon) {
                runOnUiThread(() -> setStatus("Treino " + episode + "/" + total
                    + " • vitórias na janela: " + recentWins
                    + " • exploração: " + String.format(java.util.Locale.US, "%.2f", epsilon)));
            }

            @Override
            public void onComplete(int wins, int games, File file) {
                runOnUiThread(() -> {
                    training = false;
                    if (games == 0) {
                        setStatus("Treino interrompido. Pesos parciais salvos.");
                    } else {
                        setStatus("Avaliação simulada: " + wins + "/" + games
                            + " vitórias. Modelo salvo localmente.");
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
        super.onDestroy();
    }
}

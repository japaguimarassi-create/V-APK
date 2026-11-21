package com.vapk.hud;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    public interface Host {
        void hud(String command, String value);
        String app(String command, String value) throws Exception;
        boolean isCancelled();
    }

    private static final Pattern STRING_CALL = Pattern.compile(
        "^(HUD|APP)\\.(log|setText|setTitle|setColor|tapText|typeText|scroll)\\(\"((?:\\\\.|[^\"\\\\])*)\"\\);$"
    );
    private static final Pattern VISIBLE_CALL = Pattern.compile("^HUD\\.setVisible\\((true|false)\\);$");
    private static final Pattern WAIT_CALL = Pattern.compile("^(?:HUD|APP)\\.waitMs\\((\\d{1,5})\\);$");
    private static final Pattern SIMPLE_APP_CALL = Pattern.compile("^APP\\.(back)\\(\\);$");
    private static final int MAX_SOURCE = 100_000;
    private static final int MAX_COMMANDS = 250;
    private static final int MAX_WAIT_EACH_MS = 5_000;
    private static final int MAX_WAIT_TOTAL_MS = 30_000;

    private final Host host;

    public ScriptEngine(Host host) {
        this.host = host;
    }

    public String execute(String source) throws Exception {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("O script está vazio.");
        }
        if (source.length() > MAX_SOURCE) {
            throw new IllegalArgumentException("Limite do arquivo: 100 KB.");
        }

        int executed = 0;
        int totalWait = 0;
        String[] lines = source.replace("\r", "").split("\n");

        for (int i = 0; i < lines.length; i++) {
            if (host.isCancelled()) throw new InterruptedException("Execução cancelada pelo usuário.");
            String line = lines[i].trim();

            if (line.isEmpty() || line.startsWith("//") || line.equals("{") || line.equals("}")) continue;
            if (isJavaWrapper(line)) continue;

            if (++executed > MAX_COMMANDS) {
                throw new IllegalArgumentException("Limite de 250 comandos por execução.");
            }

            Matcher wait = WAIT_CALL.matcher(line);
            if (wait.matches()) {
                int ms = Integer.parseInt(wait.group(1));
                if (ms > MAX_WAIT_EACH_MS || totalWait + ms > MAX_WAIT_TOTAL_MS) {
                    throw new IllegalArgumentException("O limite total de espera é 30 segundos.");
                }
                totalWait += ms;
                Thread.sleep(ms);
                continue;
            }

            Matcher visible = VISIBLE_CALL.matcher(line);
            if (visible.matches()) {
                host.hud("setVisible", visible.group(1));
                continue;
            }

            Matcher simple = SIMPLE_APP_CALL.matcher(line);
            if (simple.matches()) {
                host.app(simple.group(1), "");
                continue;
            }

            Matcher call = STRING_CALL.matcher(line);
            if (!call.matches()) {
                throw new IllegalArgumentException(
                    "Linha " + (i + 1) + ": comando não reconhecido ou sintaxe inválida."
                );
            }

            String namespace = call.group(1);
            String command = call.group(2);
            String value = unescape(call.group(3));

            if ("HUD".equals(namespace)) {
                if (!"log".equals(command) && !"setText".equals(command)
                        && !"setTitle".equals(command) && !"setColor".equals(command)) {
                    throw new IllegalArgumentException("Comando HUD não permitido na linha " + (i + 1) + ".");
                }
                host.hud(command, value);
            } else {
                if (!"tapText".equals(command) && !"typeText".equals(command)
                        && !"scroll".equals(command)) {
                    throw new IllegalArgumentException("Comando APP não permitido na linha " + (i + 1) + ".");
                }
                host.app(command, value);
            }
        }

        if (executed == 0) throw new IllegalArgumentException("Nenhum comando válido foi encontrado.");
        return executed + " comando(s) executado(s).";
    }

    private boolean isJavaWrapper(String line) {
        return line.startsWith("package ")
            || line.startsWith("import ")
            || line.matches("(public\\s+)?class\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\{")
            || line.matches("(public\\s+)?static\\s+void\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^)]*\\)\\s*\\{");
    }

    private String unescape(String value) {
        return value.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }
}

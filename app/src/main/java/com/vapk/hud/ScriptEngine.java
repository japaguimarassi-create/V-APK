package com.vapk.hud;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    public interface Host {
        void call(String command, String value);
    }

    private static final Pattern CALL = Pattern.compile(
        "^HUD\\.(log|setText|setTitle|setColor|setVisible|waitMs)\\((.*)\\);$"
    );
    private static final int MAX_COMMANDS = 100;
    private static final long MAX_WAIT_TOTAL_MS = 5000L;

    private final Host host;

    public ScriptEngine(Host host) {
        this.host = host;
    }

    public String execute(String source) throws Exception {
        if (source == null || source.length() > 100_000) {
            throw new IllegalArgumentException("Arquivo vazio ou maior que 100 KB.");
        }

        String[] lines = source.replace("\r", "").split("\n");
        int executed = 0;
        long totalWait = 0L;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("//") || line.equals("{") || line.equals("}")) {
                continue;
            }
            if (line.startsWith("package ") || line.startsWith("import ")
                    || line.matches("(public\\s+)?class\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\{")
                    || line.matches("(public\\s+)?static\\s+void\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^)]*\\)\\s*\\{")) {
                continue;
            }

            Matcher matcher = CALL.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Linha " + (i + 1)
                    + ": comando não permitido ou sintaxe inválida.");
            }
            if (++executed > MAX_COMMANDS) {
                throw new IllegalArgumentException("Limite de 100 comandos por execução.");
            }

            String command = matcher.group(1);
            String argument = matcher.group(2).trim();

            if ("waitMs".equals(command)) {
                int ms;
                try {
                    ms = Integer.parseInt(argument);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("waitMs exige um número inteiro.");
                }
                if (ms < 0 || ms > 1000 || totalWait + ms > MAX_WAIT_TOTAL_MS) {
                    throw new IllegalArgumentException("Espera acima do limite permitido.");
                }
                totalWait += ms;
                Thread.sleep(ms);
                continue;
            }

            if ("setVisible".equals(command)) {
                if (!"true".equals(argument) && !"false".equals(argument)) {
                    throw new IllegalArgumentException("setVisible aceita apenas true ou false.");
                }
                host.call(command, argument);
            } else {
                if (argument.length() < 2 || argument.charAt(0) != '"'
                        || argument.charAt(argument.length() - 1) != '"') {
                    throw new IllegalArgumentException(command + " exige texto entre aspas.");
                }
                host.call(command, unescape(argument.substring(1, argument.length() - 1)));
            }
        }

        if (executed == 0) {
            throw new IllegalArgumentException("Nenhum comando HUD válido foi encontrado.");
        }
        return executed + " comando(s) executado(s).";
    }

    private String unescape(String value) {
        return value.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }
}

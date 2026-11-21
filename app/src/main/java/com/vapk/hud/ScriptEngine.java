package com.vapk.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    public interface Host {
        void call(String command, String value);
    }

    public static final class Command {
        final String name;
        final String value;
        final int number;

        Command(String name, String value, int number) {
            this.name = name;
            this.value = value;
            this.number = number;
        }
    }

    public static final class Plan {
        public final List<Command> commands;
        public final String summary;

        Plan(List<Command> commands) {
            this.commands = Collections.unmodifiableList(commands);
            this.summary = commands.size() + " comandos reconhecidos";
        }
    }

    private static final Pattern CALL = Pattern.compile(
        "^HUD\\.(log|setText|setTitle|setColor|setVisible|waitMs)\\((.*)\\);$"
    );
    private static final Pattern CLASS = Pattern.compile(
        "(public\\s+)?(final\\s+)?class\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\{"
    );
    private static final Pattern METHOD = Pattern.compile(
        "(public\\s+)?static\\s+void\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^)]*\\)\\s*\\{"
    );
    private static final int MAX_COMMANDS = 100;
    private static final int MAX_FILE_CHARS = 100_000;
    private static final long MAX_WAIT_TOTAL_MS = 5000L;

    private ScriptEngine() {}

    public static Plan analyze(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        if (source.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("Arquivo maior que 100 KB.");
        }

        String clean = source.replaceAll("(?s)/\\*.*?\\*/", "");
        String[] lines = clean.replace("\r", "").split("\n");
        List<Command> commands = new ArrayList<>();
        long totalWait = 0L;

        for (int i = 0; i < lines.length; i++) {
            String line = stripLineComment(lines[i]).trim();
            if (line.isEmpty() || line.equals("{") || line.equals("}")) continue;
            if (line.startsWith("package ") || line.startsWith("import ")
                    || CLASS.matcher(line).matches() || METHOD.matcher(line).matches()) {
                continue;
            }

            Matcher matcher = CALL.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Linha " + (i + 1)
                    + ": sintaxe inválida ou comando fora da API HUD.");
            }
            if (commands.size() >= MAX_COMMANDS) {
                throw new IllegalArgumentException("Limite de 100 comandos por arquivo.");
            }

            String command = matcher.group(1);
            String argument = matcher.group(2).trim();

            if ("waitMs".equals(command)) {
                int ms;
                try {
                    ms = Integer.parseInt(argument);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("waitMs exige um inteiro.");
                }
                if (ms < 0 || ms > 1000 || totalWait + ms > MAX_WAIT_TOTAL_MS) {
                    throw new IllegalArgumentException("Espera acima do limite permitido.");
                }
                totalWait += ms;
                commands.add(new Command(command, "", ms));
            } else if ("setVisible".equals(command)) {
                if (!"true".equals(argument) && !"false".equals(argument)) {
                    throw new IllegalArgumentException("setVisible aceita true ou false.");
                }
                commands.add(new Command(command, argument, 0));
            } else {
                if (argument.length() < 2 || argument.charAt(0) != '"'
                        || argument.charAt(argument.length() - 1) != '"') {
                    throw new IllegalArgumentException(command + " exige texto entre aspas.");
                }
                commands.add(new Command(command,
                        unescape(argument.substring(1, argument.length() - 1)), 0));
            }
        }

        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Nenhum comando HUD válido foi encontrado.");
        }
        return new Plan(commands);
    }

    public static String execute(Plan plan, Host host, Runnable onStarted) throws InterruptedException {
        if (plan == null || host == null) throw new IllegalArgumentException("Plano ou HUD ausente.");
        if (onStarted != null) onStarted.run();

        for (Command command : plan.commands) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Execução cancelada.");
            }
            if ("waitMs".equals(command.name)) {
                Thread.sleep(command.number);
            } else {
                host.call(command.name, command.value);
            }
        }
        return plan.summary + " executados.";
    }

    private static String stripLineComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && quoted) {
                escaped = true;
                continue;
            }
            if (c == '"') quoted = !quoted;
            if (!quoted && c == '/' && line.charAt(i + 1) == '/') return line.substring(0, i);
        }
        return line;
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}

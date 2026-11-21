package com.vapk.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    private static final Pattern MOVE = Pattern.compile("bot\\.move\\(\\s*\"(up|down|left|right)\"\\s*\\)");
    private static final Pattern WAIT = Pattern.compile("bot\\.waitMs\\(\\s*(\\d{1,4})\\s*\\)");
    private static final Pattern LOG = Pattern.compile("bot\\.log\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)");

    private ScriptEngine() {}

    public static final class Command {
        public final String name;
        public final String value;
        public final int number;

        private Command(String name, String value, int number) {
            this.name = name;
            this.value = value;
            this.number = number;
        }
    }

    public static final class Plan {
        public final List<Command> commands;
        public final String summary;

        private Plan(List<Command> commands) {
            this.commands = Collections.unmodifiableList(commands);
            this.summary = commands.size() + " comandos validados";
        }
    }

    public static Plan analyze(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("O arquivo está vazio.");
        }
        if (source.length() > 131072) {
            throw new IllegalArgumentException("Limite de 128 KB excedido.");
        }

        String clean = source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        String body = extractRunBody(clean);
        String[] statements = body.split(";");
        List<Command> commands = new ArrayList<>();

        for (String raw : statements) {
            String statement = raw.trim();
            if (statement.isEmpty()) continue;

            Matcher move = MOVE.matcher(statement);
            Matcher wait = WAIT.matcher(statement);
            Matcher log = LOG.matcher(statement);

            if (move.matches()) {
                commands.add(new Command("move", move.group(1), 0));
            } else if (statement.equals("bot.attack()")) {
                commands.add(new Command("attack", "", 0));
            } else if (wait.matches()) {
                int ms = Integer.parseInt(wait.group(1));
                if (ms < 0 || ms > 2000) {
                    throw new IllegalArgumentException("waitMs aceita valores entre 0 e 2000.");
                }
                commands.add(new Command("wait", "", ms));
            } else if (log.matches()) {
                commands.add(new Command("log", unescape(log.group(1)), 0));
            } else {
                throw new IllegalArgumentException("Comando não permitido: " + statement);
            }

            if (commands.size() > 300) {
                throw new IllegalArgumentException("Limite de 300 comandos por arquivo.");
            }
        }

        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Nenhum comando BotApi foi encontrado.");
        }
        return new Plan(commands);
    }

    private static String extractRunBody(String source) {
        int method = source.indexOf("run(BotApi bot)");
        if (method < 0) return source;

        int open = source.indexOf('{', method);
        if (open < 0) throw new IllegalArgumentException("Método run sem abertura de bloco.");

        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (depth == 0) return source.substring(open + 1, i);
        }
        throw new IllegalArgumentException("Bloco run não foi fechado.");
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\"", "\"")
                .replace("\\\\", "\\");
    }

    public static void execute(Plan plan, BotApi bot, Runnable onStarted) throws InterruptedException {
        if (plan == null || bot == null) throw new IllegalArgumentException("Plano ou API ausente.");
        onStarted.run();
        for (Command command : plan.commands) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Execução interrompida.");
            switch (command.name) {
                case "move":
                    bot.move(command.value);
                    break;
                case "attack":
                    bot.attack();
                    break;
                case "wait":
                    bot.waitMs(command.number);
                    break;
                case "log":
                    bot.log(command.value);
                    break;
                default:
                    throw new IllegalStateException("Comando interno desconhecido.");
            }
        }
    }
}

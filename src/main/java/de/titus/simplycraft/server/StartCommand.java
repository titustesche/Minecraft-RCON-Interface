package de.titus.simplycraft.server;

import de.titus.simplycraft.common.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the process arguments. No shell is involved: the custom command is split into
 * arguments (quotes group words) and placeholders are replaced per argument.
 */
public final class StartCommand {

    public static final String DEFAULT_TEMPLATE = "{java} -Xms{xms} -Xmx{xmx} -jar {jar} nogui";

    private StartCommand() {
    }

    public static List<String> build(ServerConfig config, String defaultJava, String defaultXms, String defaultXmx, boolean customAllowed) {
        String template = customAllowed && config.getCustomCommand() != null && !config.getCustomCommand().isBlank()
                ? config.getCustomCommand()
                : DEFAULT_TEMPLATE;
        String java = blankTo(config.getJavaPath(), defaultJava);
        String xms = blankTo(config.getXms(), defaultXms);
        String xmx = blankTo(config.getXmx(), defaultXmx);

        List<String> args = new ArrayList<>();
        for (String token : tokenize(template)) {
            args.add(token
                    .replace("{java}", java)
                    .replace("{xms}", xms)
                    .replace("{xmx}", xmx)
                    .replace("{jar}", ServerService.JAR_FILE));
        }
        if (args.isEmpty()) throw ApiException.badRequest("Der Startbefehl ist leer");
        return args;
    }

    public static boolean usesServerJar(ServerConfig config, boolean customAllowed) {
        if (!customAllowed || config.getCustomCommand() == null || config.getCustomCommand().isBlank()) return true;
        return config.getCustomCommand().contains("{jar}") || config.getCustomCommand().contains(ServerService.JAR_FILE);
    }

    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : command.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (quote != 0) throw ApiException.badRequest("Nicht geschlossenes Anführungszeichen im Startbefehl");
        if (inToken) tokens.add(current.toString());
        return tokens;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Behaves like a tiny Minecraft server on stdin/stdout. Started with "java FakeServer.java". */
public class FakeServer {
    static void log(String level, String text) {
        System.out.println("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] [Server thread/" + level + "]: " + text);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        log("INFO", "Starting minecraft server version fake");
        log("WARN", "Can't keep up! Is the server overloaded?");
        log("INFO", "Done (0.123s)! For help, type \"help\"");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String cmd = line.trim();
            if (cmd.equals("stop")) {
                log("INFO", "Stopping the server");
                System.exit(0);
            } else if (cmd.equals("crash")) {
                log("ERROR", "Something exploded");
                System.exit(3);
            } else if (cmd.equals("list")) {
                log("INFO", "There are 1 of a max of 20 players online: Steve");
            } else if (cmd.startsWith("join ")) {
                log("INFO", cmd.substring(5) + " joined the game");
            } else if (cmd.startsWith("leave ")) {
                log("INFO", cmd.substring(6) + " left the game");
            } else if (cmd.equals("save-all flush")) {
                log("INFO", "Saving the game (this may take a moment!)");
                log("INFO", "Saved the game");
            } else if (cmd.equals("save-off") || cmd.equals("save-on")) {
                log("INFO", "Automatic saving is now " + (cmd.equals("save-off") ? "disabled" : "enabled"));
            } else if (cmd.equals("ignore-stop")) {
                // simulates a hanging server: ignores everything from now on
                while (true) Thread.sleep(1000);
            } else {
                log("INFO", "Unknown or incomplete command");
            }
        }
    }
}

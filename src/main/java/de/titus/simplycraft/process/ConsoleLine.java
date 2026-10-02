package de.titus.simplycraft.process;

/**
 * One line of server output, already split into the parts the console shows.
 *
 * @param level INFO, WARN, ERROR, ... from the log line, "&gt;" for commands, "SYS" for Simplycraft messages
 * @param kind  chat, join, leave, cmd, sys or null
 */
public record ConsoleLine(long seq, String time, String level, String text, String kind) {
}

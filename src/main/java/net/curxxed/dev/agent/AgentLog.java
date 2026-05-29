package net.curxxed.dev.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes to console as well as its own log file, which will be created in the current directory this jar exists in.
 *
 * In certain cases no messages will show up in logs, for example the vanilla Minecraft log window
 * which won't show any messages until you close the game. In this case it is recommended to
 * rely on the log file that is created instead.
 */
public final class AgentLog {

    private static final Path LOG_FILE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static {
        try {
            final Path jarDir = Paths.get(AgentLog.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();

            LOG_FILE = jarDir.resolve("mod-agent.log");

            Files.deleteIfExists(LOG_FILE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find current directory", e);
        }
    }

    public static synchronized void log(String message) {
        final String line = String.format(
                "[%s] [%s] [Mod-Agent] %s",
                LocalTime.now().format(TIME_FORMAT),
                Thread.currentThread().getName(),
                message
        );

        try {
            System.out.println(line);
            System.out.flush();
        } catch (Throwable ignored) {}

        // File
        try {
            Files.createDirectories(LOG_FILE.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(
                    LOG_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            )) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            try {
                System.err.println("[Mod-Agent] Failed to write log file: " + e);
                System.err.flush();
            } catch (Throwable ignored) {}
        }
    }

    public static synchronized void log(String message, Throwable t) {
        log(message);

        try {
            t.printStackTrace(System.out);
            System.out.flush();
        } catch (Throwable ignored) {}

        try {
            Files.write(
                    LOG_FILE,
                    (stackTraceToString(t) + System.lineSeparator()).getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {}
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        t.printStackTrace(printWriter);
        printWriter.flush();
        return printWriter.toString();
    }
}

package proxyforge.utils;

import burp.api.montoya.logging.Logging;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ProxyForgeLogger
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final Logging logging;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public ProxyForgeLogger(Logging logging)
    {
        this.logging = logging;
    }

    public void addListener(Consumer<String> listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener)
    {
        listeners.remove(listener);
    }

    public void info(String message)
    {
        publish("INFO", message, false, null);
    }

    public void warn(String message)
    {
        publish("WARN", message, false, null);
    }

    public void error(String message, Throwable throwable)
    {
        publish("ERROR", message, true, throwable);
    }

    private void publish(String level, String message, boolean error, Throwable throwable)
    {
        String line = "[" + TIMESTAMP.format(Instant.now()) + "] [" + level + "] " + message;
        if (error)
        {
            if (logging != null && throwable != null)
            {
                logging.logToError(message, throwable);
            }
            else if (logging != null)
            {
                logging.logToError(message);
            }
            if (logging != null)
            {
                logging.raiseErrorEvent(message);
            }
        }
        else
        {
            if (logging != null)
            {
                logging.logToOutput(line);
                if ("WARN".equals(level))
                {
                    logging.raiseInfoEvent(message);
                }
            }
        }

        listeners.forEach(listener -> listener.accept(line));
    }
}

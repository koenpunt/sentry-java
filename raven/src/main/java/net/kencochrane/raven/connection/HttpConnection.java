package net.kencochrane.raven.connection;

import net.kencochrane.raven.connection.encoder.SimpleJsonEncoder;
import net.kencochrane.raven.event.LoggedEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basic connection to a Sentry server, using HTTP.
 */
public class HttpConnection extends AbstractConnection {
    private static final Logger logger = Logger.getLogger(HttpConnection.class.getCanonicalName());
    private static final String SENTRY_AUTH = "X-Sentry-Auth";
    private static final int DEFAULT_TIMEOUT = 10000;
    private SimpleJsonEncoder simpleJsonEncoder = new SimpleJsonEncoder();
    private int timeout = DEFAULT_TIMEOUT;

    public HttpConnection(Dsn dsn) {
        super(dsn);

        // Check if a timeout is set
        if (dsn.getOptions().containsKey(Dsn.TIMEOUT_OPTION))
            setTimeout(Integer.parseInt(dsn.getOptions().get(Dsn.TIMEOUT_OPTION)));
    }

    private URL getSentryUrl() {
        try {
            String url = getDsn().getUri().toString() + "/api/" + getDsn().getProjectId() + "/store/";
            //TODO: Cache the URL?
            return new URL(url);
        } catch (Exception e) {
            // TODO: Runtime exception... Really???
            throw new RuntimeException("Couldn't get a valid URL from the DSN", e);
        }
    }

    @Override
    protected final OutputStream getOutputStream() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) getSentryUrl().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(false);
        connection.setConnectTimeout(timeout);
        connection.setRequestProperty(SENTRY_AUTH, getAuthHeader());
        connection.connect();

        return connection.getOutputStream();
    }

    @Override
    public void send(LoggedEvent event) {
        OutputStream out = null;
        try {
            out = getOutput();
            simpleJsonEncoder.encodeEvent(event, out);
        } catch (IOException e) {
            logger.log(Level.SEVERE,
                    "An exception occurred while trying to establish a connection to the sentry server", e);
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE,
                        "An exception occurred while closing the connection to the sentry server", e);
            }
        }
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
}

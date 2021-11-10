package io.sentry;

import static io.sentry.SentryLevel.ERROR;

import io.sentry.exception.ExceptionMechanismException;
import io.sentry.hints.DiskFlushNotification;
import io.sentry.hints.Flushable;
import io.sentry.hints.SessionEnd;
import io.sentry.protocol.Mechanism;
import io.sentry.util.Objects;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Sends any uncaught exception to Sentry, then passes the exception on to the pre-existing uncaught
 * exception handler.
 */
public final class UncaughtExceptionHandlerIntegration
    implements Integration, Thread.UncaughtExceptionHandler, Closeable {
  /** Reference to the pre-existing uncaught exception handler. */
  private @Nullable Thread.UncaughtExceptionHandler defaultExceptionHandler;

  private @Nullable IHub hub;
  private @Nullable SentryOptions options;

  private boolean registered = false;
  private final @NotNull UncaughtExceptionHandler threadAdapter;

  public UncaughtExceptionHandlerIntegration() {
    this(UncaughtExceptionHandler.Adapter.getInstance());
  }

  UncaughtExceptionHandlerIntegration(final @NotNull UncaughtExceptionHandler threadAdapter) {
    this.threadAdapter = Objects.requireNonNull(threadAdapter, "threadAdapter is required.");
  }

  @Override
  public final void register(final @NotNull IHub hub, final @NotNull SentryOptions options) {
    if (registered) {
      options
          .getLogger()
          .log(
              SentryLevel.ERROR,
              "Attempt to register a UncaughtExceptionHandlerIntegration twice.");
      return;
    }
    registered = true;

    this.hub = Objects.requireNonNull(hub, "Hub is required");
    this.options = Objects.requireNonNull(options, "SentryOptions is required");

    this.options
        .getLogger()
        .log(
            SentryLevel.DEBUG,
            "UncaughtExceptionHandlerIntegration enabled: %s",
            this.options.isEnableUncaughtExceptionHandler());

    if (this.options.isEnableUncaughtExceptionHandler()) {
      final Thread.UncaughtExceptionHandler currentHandler =
          threadAdapter.getDefaultUncaughtExceptionHandler();
      if (currentHandler != null) {
        this.options
            .getLogger()
            .log(
                SentryLevel.DEBUG,
                "default UncaughtExceptionHandler class='"
                    + currentHandler.getClass().getName()
                    + "'");
        defaultExceptionHandler = currentHandler;
      }

      threadAdapter.setDefaultUncaughtExceptionHandler(this);

      this.options
          .getLogger()
          .log(SentryLevel.DEBUG, "UncaughtExceptionHandlerIntegration installed.");
    }
  }

  @Override
  public void uncaughtException(Thread thread, Throwable thrown) {
    if (options != null && hub != null) {
      options.getLogger().log(SentryLevel.INFO, "Uncaught exception received.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      thrown.printStackTrace(new PrintStream(out));
      options.getLogger().log(SentryLevel.DEBUG, "%s\nStacktrace. %s",
        thrown.getMessage(),
        new String(out.toByteArray()));

      try {
        final UncaughtExceptionHint hint =
            new UncaughtExceptionHint(options.getFlushTimeoutMillis(), options.getLogger());
        final Throwable throwable = getUnhandledThrowable(thread, thrown);
        final SentryEvent event = new SentryEvent(throwable);
        event.setLevel(SentryLevel.FATAL);
        hub.captureEvent(event, hint);
        // Block until the event is flushed to disk
        if (!hint.waitFlush()) {
          options
              .getLogger()
              .log(
                  SentryLevel.WARNING,
                  "Timed out waiting to flush event to disk before crashing. Event: %s",
                  event.getEventId());
        }
      } catch (Exception e) {
        options
            .getLogger()
            .log(SentryLevel.ERROR, "Error sending uncaught exception to Sentry.", e);
      }

      if (defaultExceptionHandler != null) {
        options.getLogger().log(SentryLevel.INFO, "Invoking inner uncaught exception handler.");
        defaultExceptionHandler.uncaughtException(thread, thrown);
      }
    }
  }

  @TestOnly
  @NotNull
  static Throwable getUnhandledThrowable(
      final @NotNull Thread thread, final @NotNull Throwable thrown) {
    final Mechanism mechanism = new Mechanism();
    mechanism.setHandled(false);
    mechanism.setType("UncaughtExceptionHandler");
    return new ExceptionMechanismException(mechanism, thrown, thread);
  }

  @Override
  public void close() {
    if (defaultExceptionHandler != null
        && this == threadAdapter.getDefaultUncaughtExceptionHandler()) {
      threadAdapter.setDefaultUncaughtExceptionHandler(defaultExceptionHandler);

      if (options != null) {
        options.getLogger().log(SentryLevel.DEBUG, "UncaughtExceptionHandlerIntegration removed.");
      }
    }
  }

  private static final class UncaughtExceptionHint
      implements DiskFlushNotification, Flushable, SessionEnd {

    private final CountDownLatch latch;
    private final long flushTimeoutMillis;
    private final @NotNull ILogger logger;

    UncaughtExceptionHint(final long flushTimeoutMillis, final @NotNull ILogger logger) {
      this.flushTimeoutMillis = flushTimeoutMillis;
      latch = new CountDownLatch(1);
      this.logger = logger;
    }

    @Override
    public boolean waitFlush() {
      try {
        return latch.await(flushTimeoutMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.log(ERROR, "Exception while awaiting for flush in UncaughtExceptionHint", e);
      }
      return false;
    }

    @Override
    public void markFlushed() {
      latch.countDown();
    }
  }
}

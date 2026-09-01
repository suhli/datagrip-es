package io.github.suhli.datagrip.elasticsearch;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public interface Transport extends Closeable {
    Response execute(Request request) throws IOException;

    default Response execute(Request request, ExecuteOptions options) throws IOException {
        return execute(request);
    }

    default void setNetworkTimeoutMillis(int milliseconds) {}

    record Request(String method, URI uri, Map<String, String> headers, String body) {
        public Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    record Response(int status, Map<String, List<String>> headers, String body) {
        public boolean successful() { return status >= 200 && status < 300; }
    }

    /**
     * Per-request options. {@code timeoutMillis <= 0} means "no override" —
     * use connection network timeout or transport default.
     */
    record ExecuteOptions(int timeoutMillis, Cancellation cancellation) {
        public static ExecuteOptions of(int timeoutMillis) {
            return new ExecuteOptions(timeoutMillis, null);
        }

        public static ExecuteOptions none() {
            return new ExecuteOptions(0, null);
        }
    }

    /** Handle for aborting an in-flight HTTP request without closing the client. */
    interface Cancellation {
        void cancel();
        boolean isCancelled();
    }

    /**
     * One-shot cancellation token for a single execution.
     * A new instance must be created for every request.
     */
    final class RequestCancellation implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object lock = new Object();
        private volatile Runnable abortAction;
        private volatile Thread executingThread;

        /** Bind the real HTTP request abort action (e.g. {@code HttpUriRequestBase::cancel}). */
        public void bind(Runnable abort) {
            synchronized (lock) {
                abortAction = abort;
                if (cancelled.get() && abort != null) {
                    abort.run();
                }
            }
        }

        void bindExecution(Thread thread) {
            executingThread = thread;
            if (cancelled.get() && thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Runnable abort;
            synchronized (lock) {
                abort = abortAction;
            }
            if (abort != null) {
                try {
                    abort.run();
                } catch (RuntimeException ignored) {
                    // Abort must not throw out of cancel().
                }
            }
            Thread thread = executingThread;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}

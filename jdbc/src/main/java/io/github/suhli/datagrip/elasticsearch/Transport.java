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

    record ExecuteOptions(int timeoutMillis, Cancellation cancellation) {
        public static ExecuteOptions of(int timeoutMillis) {
            return new ExecuteOptions(timeoutMillis, null);
        }
    }

    /** Handle for aborting an in-flight HTTP request without closing the client. */
    interface Cancellation {
        void cancel();
        boolean isCancelled();
    }

    final class RequestCancellation implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Runnable abortAction;
        private volatile Thread executingThread;

        void bind(Runnable abort) {
            abortAction = abort;
            if (cancelled.get()) abort.run();
        }

        void bindExecution(Thread thread) {
            executingThread = thread;
            if (cancelled.get()) thread.interrupt();
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Runnable abort = abortAction;
                if (abort != null) abort.run();
                Thread thread = executingThread;
                if (thread != null) thread.interrupt();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}

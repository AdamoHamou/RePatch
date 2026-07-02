package edu.unlv.cs.evol.repatch.platform;

import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.application.ApplicationManager;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Runs blocking work on a pooled thread while the EDT pumps its event queue.
 *
 * <p>git4idea's command layer asserts it is NOT on the EDT along its Windows
 * auth-preparation path ({@code GitHandlerAuthenticationManager.readSshCommand}
 * → {@code AbstractRepositoryManager.getRepositoryForRoot} →
 * {@code ThreadingAssertions.assertBackgroundThread}), so calls like
 * {@code GitHistoryUtils.history} and {@code Git.reset} that the Linux dev
 * container tolerated on the EDT throw on a Windows host. The headless
 * pipeline's {@code main} occupies the EDT for the whole run, so the only
 * correct shape is the one {@link IntelliJ2024PlatformFacade}'s
 * {@code pumpEventsUntilSmart} already uses: park the work on a pooled thread
 * and pump the queue until it completes, so any EDT hops the work schedules
 * can still run instead of deadlocking behind us.
 */
public final class EdtSafe {

    private EdtSafe() {
    }

    /**
     * Executes {@code task} directly when already on a background thread;
     * otherwise runs it on a pooled thread and pumps the EDT event queue
     * until it finishes. Rethrows the task's own exception unwrapped.
     */
    public static <T> T compute(Callable<T> task) throws Exception {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            return task.call();
        }
        Future<T> future = ApplicationManager.getApplication().executeOnPooledThread(task);
        while (!future.isDone()) {
            IdeEventQueue.getInstance().flushQueue();
            try {
                // Work is proceeding on the pooled thread; yield briefly
                // instead of spinning the EDT hot (same as pumpEventsUntilSmart).
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

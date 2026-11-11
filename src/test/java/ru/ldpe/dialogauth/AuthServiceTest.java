package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class AuthServiceTest {
    @TempDir Path directory;
    final AtomicLong clock = new AtomicLong();
    final List<Exception> errors = new ArrayList<>();

    AuthService service(AuthBackend backend, int threads, int queue) {
        return new AuthService(backend, new RateLimiter(clock::get, 100), threads, queue, errors::add);
    }

    @Test void realRegistrationAndLoginRoundTripOnEventLoop() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        UUID uuid = UUID.randomUUID();
        try (var service = service(store, 2, 2)) {
            Loop first = new Loop();
            var registration = service.session(uuid, "Alice", "one", first);
            registration.start();
            assertTrue(first.registering);
            registration.submit("password", "password");
            assertEquals(AuthService.State.BUSY, registration.state());
            first.deliver();
            assertEquals(AuthService.State.COMPLETE, registration.state());
            assertEquals(1, first.successes);
            assertTrue(first.timerCancelled);
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            Loop second = new Loop();
            var login = service.session(uuid, "Renamed", "one", second);
            login.start();
            assertFalse(second.registering);
            login.submit("password", "");
            second.deliver();
            assertEquals(1, second.successes);
            assertTrue(errors.isEmpty());
        }
    }

    @Test void duplicatePacketsAreIgnoredAndLeaseSurvivesDisconnect() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (var service = service(backend, 2, 2)) {
            UUID uuid = UUID.randomUUID();
            Loop first = new Loop();
            var session = service.session(uuid, "Alice", "one", first);
            session.start();
            session.submit("password", "");
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            session.submit("password", "");
            first.open = false;
            session.close();
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            Loop other = new Loop();
            var reconnect = service.session(uuid, "Alice", "two", other);
            reconnect.start();
            reconnect.submit("password", "");
            assertTrue(other.reason.contains("UUID"));
            assertEquals(1, backend.calls.get());
            backend.release.countDown();
            first.deliver();
            assertEquals(0, first.successes);
            assertEquals(AuthService.State.CLOSED, session.state());
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            Loop third = new Loop();
            var next = service.session(uuid, "Alice", "three", third);
            next.start();
            next.submit("password", "");
            third.deliver();
            assertEquals(1, third.successes);
        } finally { backend.release.countDown(); }
    }

    @Test void queueOverflowRejectsImmediatelyAndReleasesUuid() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (var service = service(backend, 1, 1)) {
            Loop running = new Loop();
            var one = service.session(UUID.randomUUID(), "One", "one", running);
            one.start(); one.submit("password", "");
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            Loop queued = new Loop();
            var two = service.session(UUID.randomUUID(), "Two", "two", queued);
            two.start(); two.submit("password", "");
            UUID uuid = UUID.randomUUID();
            Loop rejected = new Loop();
            var three = service.session(uuid, "Three", "three", rejected);
            three.start(); three.submit("password", "");
            assertTrue(rejected.reason.contains("занят"));
            assertEquals(AuthService.State.CLOSED, three.state());
            two.close();
            backend.release.countDown();
            running.deliver(); queued.deliver();
            assertEquals(1, backend.calls.get());
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            Loop retried = new Loop();
            var retry = service.session(uuid, "Three", "four", retried);
            retry.start(); retry.submit("password", "");
            retried.deliver();
            assertEquals(1, retried.successes);
            assertTrue(backend.daemon);
        } finally { backend.release.countDown(); }
    }

    @Test void timeoutWinsAgainstLateSuccessAndCannotBeResetByDuplicates() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (var service = service(backend, 1, 1)) {
            Loop loop = new Loop();
            var session = service.session(UUID.randomUUID(), "Alice", "one", loop);
            session.start(); session.start();
            session.submit("password", "");
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            session.submit("password", "");
            loop.timeout.run();
            assertEquals(AuthService.State.CLOSED, session.state());
            assertTrue(loop.reason.contains("истекло"));
            backend.release.countDown();
            loop.deliver();
            assertEquals(0, loop.successes);
            assertEquals(1, loop.scheduled);
        } finally { backend.release.countDown(); }
    }

    @Test void validationAndWrongPasswordAreRateLimitedAcrossSessions() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        UUID uuid = UUID.randomUUID();
        store.register(uuid, "Alice", "password".toCharArray(), () -> true);
        try (var service = service(store, 1, 1)) {
            Loop loop = new Loop();
            var session = service.session(uuid, "Alice", "one", loop);
            session.start(); session.submit("short", "");
            assertEquals(AuthService.State.READY, session.state());
            session.submit("password", "");
            assertEquals(AuthService.State.CLOSED, session.state());
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            Loop second = new Loop();
            var retry = service.session(uuid, "Alice", "two", second);
            retry.start(); retry.submit("wrongpass", ""); second.deliver();
            assertEquals(AuthService.State.READY, retry.state());
            Loop third = new Loop();
            var reconnect = service.session(uuid, "Alice", "three", third);
            reconnect.start(); reconnect.submit("password", "");
            assertEquals(AuthService.State.CLOSED, reconnect.state());
        }
    }

    @Test void backendErrorFailsClosedAndClearsPasswordBuffer() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        backend.fail = true;
        backend.release.countDown();
        try (var service = service(backend, 1, 1)) {
            Loop loop = new Loop();
            var session = service.session(UUID.randomUUID(), "Alice", "one", loop);
            session.start(); session.submit("password", ""); loop.deliver();
            assertEquals(AuthService.State.CLOSED, session.state());
            assertTrue(loop.reason.contains("хранилища"));
            assertEquals(1, errors.size());
            assertArrayEquals(new char[8], backend.secret);
        }
    }

    static final class Loop implements AuthService.Transport {
        final Thread owner = Thread.currentThread();
        final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        boolean open = true;
        boolean registering;
        boolean timerCancelled;
        int scheduled;
        int successes;
        String reason = "";
        Runnable timeout;
        void check() { assertSame(owner, Thread.currentThread()); }
        public void execute(Runnable action) { callbacks.add(action); }
        public Runnable schedule(Duration delay, Runnable action) {
            check(); assertEquals(Duration.ofSeconds(120), delay); scheduled++;
            timeout = action;
            return () -> { check(); timerCancelled = true; };
        }
        public boolean isOpen() { check(); return open; }
        public void dialog(boolean registering, String error) { check(); this.registering = registering; }
        public void success() { check(); successes++; }
        public void disconnect(String reason) { check(); this.reason = reason; open = false; }
        void deliver() throws Exception {
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback, "Worker completion was not returned to event loop");
            callback.run();
        }
    }

    static final class BlockingBackend implements AuthBackend {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean daemon;
        volatile boolean fail;
        volatile char[] secret;
        public boolean exists(UUID uuid) { return true; }
        public void register(UUID uuid, String username, char[] password, BooleanSupplier active) {
            throw new AssertionError("Unexpected registration");
        }
        public boolean verify(UUID uuid, String username, char[] password, BooleanSupplier active) {
            calls.incrementAndGet(); daemon = Thread.currentThread().isDaemon(); secret = password;
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            if (fail) throw new IllegalStateException("test failure");
            return true;
        }
    }
}

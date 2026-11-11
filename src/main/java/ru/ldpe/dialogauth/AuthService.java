package ru.ldpe.dialogauth;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class AuthService implements AutoCloseable {
    public static final Duration TIMEOUT = Duration.ofSeconds(120);
    private final AuthBackend accounts;
    private final RateLimiter limiter;
    private final ThreadPoolExecutor workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Consumer<Exception> errors;
    private final Consumer<String> warnings;
    private final boolean offlineMode;
    private final boolean allowOfflineMode;

    public AuthService(AuthBackend accounts, RateLimiter limiter, int threads, int queueSize,
                       Consumer<Exception> errors) {
        this(accounts, limiter, threads, queueSize, errors, true, false, message -> {});
    }

    public AuthService(AuthBackend accounts, RateLimiter limiter, int threads, int queueSize,
                       Consumer<Exception> errors, boolean onlineMode, boolean allowOfflineMode,
                       Consumer<String> warnings) {
        this.accounts = accounts;
        this.limiter = limiter;
        this.errors = errors;
        this.warnings = warnings;
        this.offlineMode = !onlineMode;
        this.allowOfflineMode = allowOfflineMode;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    Thread thread = new Thread(runnable, "dialogauth-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Session session(UUID uuid, String username, String ip, Transport transport) {
        return new Session(uuid, username, ip, transport);
    }

    public void initialize(Runnable initialization) {
        workers.execute(initialization);
    }

    @Override
    public void close() {
        workers.shutdown();
    }

    public interface Transport {
        void execute(Runnable action);
        Runnable schedule(Duration delay, Runnable action);
        boolean isOpen();
        void dialog(boolean registering, String error);
        void success();
        void disconnect(String reason);
    }

    public enum State { NEW, READY, BUSY, COMPLETE, CLOSED }

    public final class Session {
        private final UUID uuid;
        private final String username;
        private final String ip;
        private final Transport transport;
        private final boolean registering;
        private final String principal;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private State state = State.NEW;
        private Runnable cancelTimeout = () -> {};

        private Session(UUID uuid, String username, String ip, Transport transport) {
            this.uuid = uuid;
            this.username = username;
            this.ip = ip;
            this.transport = transport;
            principal = offlineMode ? "name:" + username.toLowerCase(Locale.ROOT) : "uuid:" + uuid;
            registering = !accounts.exists(uuid, username, offlineMode);
        }

        public State state() { return state; }

        public void start() {
            start(true);
        }

        public void start(boolean showDialog) {
            if (state != State.NEW) return;
            if (!transport.isOpen()) { close(); return; }
            if (offlineMode && !allowOfflineMode) {
                warnings.accept("Blocked offline-mode authentication for " + username + " (" + uuid
                        + "): allow-offline-mode=false");
                disconnect("Сервер работает в offline-mode, но поддержка offline-клиентов отключена. "
                        + "Администратору: установите allow-offline-mode=true в config/dialogauth/config.properties.");
                return;
            }
            state = State.READY;
            cancelTimeout = transport.schedule(TIMEOUT, () -> disconnect("Время авторизации истекло. Переподключитесь."));
            if (showDialog) showDialog();
        }

        public void showDialog() {
            if (state == State.READY && transport.isOpen()) transport.dialog(registering, "");
        }

        public void submit(String password, String confirmation) {
            if (state != State.READY) return;
            if (!transport.isOpen()) { close(); return; }
            long delay = limiter.acquire(principal, ip);
            if (delay > 0) {
                disconnect("Слишком частые попытки. Повторите через " + ((delay + 999) / 1000) + " сек.");
                return;
            }
            if (password.length() < 8 || password.length() > 128) {
                transport.dialog(registering, "Пароль должен содержать от 8 до 128 символов.");
                return;
            }
            if (registering && !password.equals(confirmation)) {
                transport.dialog(true, "Пароли не совпадают.");
                return;
            }
            if (!inFlight.add(principal)) {
                disconnect(offlineMode ? "Для этого имени уже выполняется авторизация. Повторите позже."
                        : "Для этого UUID уже выполняется авторизация. Повторите позже.");
                return;
            }
            state = State.BUSY;
            char[] secret = password.toCharArray();
            try {
                workers.execute(() -> compute(secret));
            } catch (RejectedExecutionException e) {
                Arrays.fill(secret, '\0');
                inFlight.remove(principal);
                disconnect("Сервис авторизации занят. Повторите позже.");
            }
        }

        private void compute(char[] secret) {
            Outcome outcome = Outcome.CANCELLED;
            try {
                if (!cancelled.get()) {
                    if (registering) {
                        accounts.register(uuid, username, secret, offlineMode, () -> !cancelled.get());
                        outcome = Outcome.SUCCESS;
                    } else if (accounts.verify(uuid, username, secret, offlineMode, () -> !cancelled.get())) {
                        outcome = Outcome.SUCCESS;
                    } else {
                        limiter.failed(principal, ip);
                        outcome = Outcome.WRONG_PASSWORD;
                    }
                }
            } catch (Exception e) {
                errors.accept(e);
                outcome = Outcome.ERROR;
            } finally {
                Arrays.fill(secret, '\0');
                inFlight.remove(principal);
            }
            Outcome result = outcome;
            try {
                transport.execute(() -> complete(result));
            } catch (RejectedExecutionException e) {
                cancelled.set(true);
            }
        }

        private void complete(Outcome outcome) {
            if (state != State.BUSY || cancelled.get()) return;
            if (!transport.isOpen()) { close(); return; }
            switch (outcome) {
                case SUCCESS -> {
                    state = State.COMPLETE;
                    cancelTimeout.run();
                    transport.success();
                }
                case WRONG_PASSWORD -> {
                    state = State.READY;
                    transport.dialog(registering, "Неверный пароль. Подождите перед следующей попыткой.");
                }
                case ERROR -> disconnect("Ошибка хранилища авторизации. Обратитесь к администратору.");
                case CANCELLED -> close();
            }
        }

        private void disconnect(String reason) {
            if (state == State.CLOSED || state == State.COMPLETE) return;
            close();
            transport.disconnect(reason);
        }

        public void close() {
            cancelled.set(true);
            if (state != State.COMPLETE) state = State.CLOSED;
            cancelTimeout.run();
        }
    }

    private enum Outcome { SUCCESS, WRONG_PASSWORD, ERROR, CANCELLED }
}

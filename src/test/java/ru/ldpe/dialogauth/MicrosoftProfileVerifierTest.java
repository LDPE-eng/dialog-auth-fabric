package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrosoftProfileVerifierTest {
    private HttpServer server;
    private final UUID onlineUuid = UUID.randomUUID();
    private final AtomicInteger aliceRequests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profiles/Alice", exchange -> {
            aliceRequests.incrementAndGet();
            byte[] body = ("{\"id\":\"" + onlineUuid.toString().replace("-", "")
                    + "\",\"name\":\"Alice\"}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/profiles/Unknown", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/profiles/Broken", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        MicrosoftProfileVerifier.configure("http://127.0.0.1:" + server.getAddress().getPort() + "/profiles/");
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    @Test void onlineUuidMustMatchAndLookupsAreCached() throws Exception {
        assertEquals(MicrosoftProfileVerifier.Decision.ONLINE,
                MicrosoftProfileVerifier.lookupNow("Alice", onlineUuid));
        assertEquals(MicrosoftProfileVerifier.Decision.ONLINE,
                MicrosoftProfileVerifier.lookupNow("Alice", onlineUuid));
        assertEquals(MicrosoftProfileVerifier.Decision.OFFLINE,
                MicrosoftProfileVerifier.lookupNow("Alice", UUID.randomUUID()));
        assertEquals(1, aliceRequests.get());
        assertEquals(MicrosoftProfileVerifier.Decision.OFFLINE,
                MicrosoftProfileVerifier.lookupNow("Unknown", UUID.randomUUID()));
        assertThrows(IOException.class, () -> MicrosoftProfileVerifier.lookupNow("Broken", UUID.randomUUID()));
    }

    @Test void invalidProfileUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MicrosoftProfileVerifier.configure("file:///tmp/profile"));
        assertThrows(IllegalArgumentException.class, () -> MicrosoftProfileVerifier.configure("https://example.com/no-slash"));
    }
}

package com.pedanticprogrammer.undertow;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.server.session.SessionManager;
import io.undertow.util.Sessions;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;

public class RedisSessionManagerIT {
    private static Jedis JEDIS;
    private static URI REDIS_URI;

    @BeforeClass
    public static void init() throws URISyntaxException {
        REDIS_URI = new URI("redis://localhost:6379/0");
        JEDIS = new Jedis();
    }

    @Before
    public void setUp() {
        JEDIS.flushDB();
    }

    @Test
    public void sessionShouldWorkCorrectly() throws URISyntaxException, IOException {
        SessionCookieConfig sessionConfig = new SessionCookieConfig();
        sessionConfig.setCookieName("session");

        SessionManager sessionManager = new RedisSessionManager(REDIS_URI, sessionConfig);

        Undertow server = Undertow.builder()
                .addHttpListener(9876, "localhost")
                .setHandler(new SessionAttachmentHandler(new SessionHandler(), sessionManager, sessionConfig))
                .build();

        server.start();

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("http://localhost:9876/")
                .build();

        Response response = client.newCall(request).execute();

        assertEquals("square", response.body().string());

        // Should be the :created key
        assertEquals(1, sessionManager.getAllSessions().size());

        server.stop();
    }

    private static class SessionHandler implements HttpHandler {
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            Session session = Sessions.getOrCreateSession(exchange);

            session.setAttribute("shape", "triangle");
            System.out.println("shape: " + session.getAttribute("shape"));

            session.setAttribute("shape", "square");
            System.out.println("shape: " + session.getAttribute("shape"));

            exchange.getResponseSender().send(String.valueOf(session.removeAttribute("shape")));
        }
    }
}

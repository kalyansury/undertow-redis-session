package com.pedanticprogrammer.undertow;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.server.session.SessionManager;
import io.undertow.util.Sessions;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;

public class RedisSessionManagerIT {
    private static Jedis JEDIS;
    private static SessionManager sessionManager;
    private static Undertow server;
    private static OkHttpClient client = new OkHttpClient();

    @BeforeClass
    public static void init() throws URISyntaxException {
        URI redisUri = new URI("redis://localhost:6379/0");
        JEDIS = new Jedis(redisUri);

        SessionCookieConfig sessionConfig = new SessionCookieConfig();
        sessionConfig.setCookieName("session");

        sessionManager = new RedisSessionManager(redisUri, sessionConfig);

        server = Undertow.builder()
                .addHttpListener(9876, "localhost")
                .setHandler(new SessionAttachmentHandler(new PathTemplateHandler()
                        .add("/attributes", new AttributesHandler())
                        .add("/objects", new ObjectsHandler())
                        , sessionManager, sessionConfig))
                .build();

        server.start();
    }

    @AfterClass
    public static void after() {
        server.stop();
    }

    @Before
    public void setUp() {
        JEDIS.flushDB();
    }

    @Test
    public void sessionAttributesShouldWorkCorrectly() throws URISyntaxException, IOException {
        Request request = new Request.Builder()
                .url("http://localhost:9876/attributes")
                .build();

        Response response = client.newCall(request).execute();

        assertEquals("square", response.body().string());

        // The only attribute being removed will delete the map key, but the meta data map should remain
        assertEquals(1, sessionManager.getAllSessions().size());

        server.stop();
    }

    @Test
    public void sessionAttributesShouldHandleObjects() throws IOException {
        Request request = new Request.Builder()
                .url("http://localhost:9876/objects")
                .build();

        Response response = client.newCall(request).execute();

        assertEquals("name", response.body().string());
    }

    private static class AttributesHandler implements HttpHandler {
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            Session session = Sessions.getOrCreateSession(exchange);

            session.setAttribute("shape", "triangle");
            System.out.println("shape: " + session.getAttribute("shape"));

            session.setAttribute("shape", "square");
            System.out.println("shape: " + session.getAttribute("shape"));

            exchange.getResponseSender().send(String.valueOf(session.removeAttribute("shape")));
        }
    }

    private static class ObjectsHandler implements HttpHandler {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            Session session = Sessions.getOrCreateSession(exchange);

            session.setAttribute("object", new Bean());
            Bean object = (Bean) session.getAttribute("object");
            System.out.println(object);

            exchange.getResponseSender().send(object.getName());
        }

        private static class Bean implements Serializable {
            private Long id = 1L;
            private String name = "name";

            String getName() {
                return name;
            }

            @Override
            public String toString() {
                return "Bean{" +
                        "id=" + id +
                        ", name='" + name + '\'' +
                        '}';
            }
        }
    }
}

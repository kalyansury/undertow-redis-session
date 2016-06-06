package com.pedanticprogrammer.undertow;

import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.*;
import io.undertow.util.AttachmentKey;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * A SessionManager that uses Redis to store session data.  Only supports Strings as session values right now...
 *
 * @author Kent Smith
 */
public class RedisSessionManager implements SessionManager {
    private final static String DEFAULT_DEPLOYMENT_NAME = "SESSION_MANAGER";
    private final static String CREATED_FIELD = ":created";

    private final AttachmentKey<SessionImpl> NEW_SESSION = AttachmentKey.create(SessionImpl.class);
    private final SessionIdGenerator sessionIdGenerator;
    private final String deploymentName;
    private final SessionListeners sessionListeners = new SessionListeners();
    private volatile int defaultSessionTimeout = 30 * 60;
    private final SessionConfig sessionConfig;
    private final Jedis jedis;

    private volatile long startTime;

    public RedisSessionManager(final URI redisUri, final SessionConfig sessionConfig) {
        this(DEFAULT_DEPLOYMENT_NAME, new SecureRandomSessionIdGenerator(), redisUri, sessionConfig);
    }

    public RedisSessionManager(final String deploymentName, final SessionIdGenerator sessionIdGenerator,
                               final URI redisUri, final SessionConfig sessionConfig) {
        this.deploymentName = deploymentName;
        this.sessionIdGenerator = sessionIdGenerator;
        this.sessionConfig = sessionConfig;

        jedis = new Jedis(redisUri);
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void start() {
        startTime = System.currentTimeMillis();
    }

    public void stop() {
        // Do nothing for now...
    }

    public Session createSession(final HttpServerExchange serverExchange, final SessionConfig sessionConfig) {
        if (sessionConfig == null) {
            throw UndertowMessages.MESSAGES.couldNotFindSessionCookieConfig();
        }
        String sessionId = sessionConfig.findSessionId(serverExchange);
        int count = 0;
        while (sessionId == null) {
            sessionId = sessionIdGenerator.createSessionId();
            if (jedis.exists(sessionId)) {
                sessionId = null;
            }
            if (count++ == 100) {
                //this should never happen
                //but we guard against pathalogical session id generators to prevent an infinite loop
                throw UndertowMessages.MESSAGES.couldNotGenerateUniqueSessionId();
            }
        }
        final long created = System.currentTimeMillis();
        jedis.set(sessionId + CREATED_FIELD, String.valueOf(created));
        final SessionImpl session = new SessionImpl(sessionId, created, defaultSessionTimeout,
                sessionConfig, this);

        sessionConfig.setSessionId(serverExchange, session.getId());
        sessionListeners.sessionCreated(session, serverExchange);
        serverExchange.putAttachment(NEW_SESSION, session);

        return session;
    }

    public Session getSession(final HttpServerExchange serverExchange, final SessionConfig sessionConfig) {
        if (serverExchange != null) {
            SessionImpl newSession = serverExchange.getAttachment(NEW_SESSION);
            if (newSession != null) {
                return newSession;
            }
        }
        String sessionId = sessionConfig.findSessionId(serverExchange);
        return getSession(sessionId);
    }

    public Session getSession(final String sessionId) {
        if (sessionId == null) {
            return null;
        }
        if (jedis.exists(sessionId)) {
            long created = Long.valueOf(jedis.get(sessionId + CREATED_FIELD));
            int ttl = jedis.ttl(sessionId).intValue();
            return new SessionImpl(sessionId, created, ttl, sessionConfig, this);
        } else {
            return null;
        }
    }

    public synchronized void registerSessionListener(final SessionListener listener) {
        sessionListeners.addSessionListener(listener);
    }

    public synchronized void removeSessionListener(final SessionListener listener) {
        sessionListeners.removeSessionListener(listener);
    }

    public void setDefaultSessionTimeout(final int timeout) {
        defaultSessionTimeout = timeout;
    }

    public Set<String> getTransientSessions() {
        // No sessions should be lost when shutting down a node
        return Collections.emptySet();
    }

    public Set<String> getActiveSessions() {
        return getAllSessions();
    }

    public Set<String> getAllSessions() {
        return jedis.keys("*");
    }

    // TODO: support statistics
    public SessionManagerStatistics getStatistics() {
        return null;
    }

    private static class SessionImpl implements Session {
        private String sessionId;
        private final long creationTime;
        private volatile int maxInactiveInterval;
        private final SessionConfig sessionConfig;
        private final RedisSessionManager sessionManager;

        private SessionImpl(final String sessionId, final long creationTime,
                            final int maxInactiveInterval, final SessionConfig sessionConfig,
                            final RedisSessionManager sessionManager) {
            this.sessionId = sessionId;
            this.creationTime = creationTime;
            this.maxInactiveInterval = maxInactiveInterval;
            this.sessionConfig = sessionConfig;
            this.sessionManager = sessionManager;
        }

        public String getId() {
            return sessionId;
        }

        public void requestDone(HttpServerExchange serverExchange) {

        }

        public long getCreationTime() {
            return creationTime;
        }

        public long getLastAccessedTime() {
            return System.currentTimeMillis() - ((maxInactiveInterval * 100) - sessionManager.jedis.pttl(sessionId));
        }

        public void setMaxInactiveInterval(final int interval) {
            maxInactiveInterval = interval;
            bumpTimeout();
        }

        public int getMaxInactiveInterval() {
            return maxInactiveInterval;
        }

        public Object getAttribute(String name) {
            bumpTimeout();
            return sessionManager.jedis.hget(sessionId, name);
        }

        public Set<String> getAttributeNames() {
            bumpTimeout();
            return sessionManager.jedis.hkeys(sessionId);
        }

        public Object setAttribute(String name, Object value) {
            String existing = sessionManager.jedis.hget(sessionId, name);
            sessionManager.jedis.hset(sessionId, name, value.toString());
            if (existing == null) {
                sessionManager.sessionListeners.attributeAdded(this, name, value);
            } else {
                sessionManager.sessionListeners.attributeUpdated(this, name, value, existing);
            }
            bumpTimeout();
            return value;
        }

        public Object removeAttribute(String name) {
            final Object existing = sessionManager.jedis.hget(sessionId, name);
            sessionManager.jedis.hdel(sessionId, name);
            sessionManager.sessionListeners.attributeRemoved(this, name, existing);
            bumpTimeout();

            return existing;
        }

        public void invalidate(HttpServerExchange exchange) {
            Transaction transaction = sessionManager.jedis.multi();
            transaction.del(sessionId);
            transaction.del(sessionId + CREATED_FIELD);
            transaction.exec();

            if (exchange != null) {
                sessionConfig.clearSession(exchange, this.getId());
            }
        }

        public SessionManager getSessionManager() {
            return sessionManager;
        }

        public String changeSessionId(HttpServerExchange exchange, SessionConfig config) {
            final String oldId = sessionId;
            String newId = sessionManager.sessionIdGenerator.createSessionId();
            this.sessionId = newId;
            sessionManager.jedis.rename(oldId, newId);
            config.setSessionId(exchange, this.getId());
            sessionManager.sessionListeners.sessionIdChanged(this, oldId);
            return newId;
        }

        private void bumpTimeout() {
            Transaction transaction = sessionManager.jedis.multi();
            transaction.expire(sessionId, maxInactiveInterval);
            transaction.expire(sessionId + CREATED_FIELD, maxInactiveInterval);

            transaction.exec();
        }
    }
}

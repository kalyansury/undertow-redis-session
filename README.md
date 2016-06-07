A SessionManager for [Undertow](http://undertow.io) that uses Redis to store session data.  Make sure the session attribute you want to store is Serializable.

# Getting started

You will need to pass in the `SessionConfig` when instantiating RedisSessionManager.
```java
SessionCookieConfig sessionConfig = new SessionCookieConfig();
SessionManager sessionManager = new RedisSessionManager(new URI("redis://localhost:6379/0"), sessionConfig);
```

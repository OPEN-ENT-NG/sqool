package fr.openent.sqool.services.impl;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.entcore.common.validation.ValidationException;

import fr.wseduc.webutils.Utils;
import io.reactiverse.pgclient.PgClient;
import io.reactiverse.pgclient.PgPool;
import io.reactiverse.pgclient.PgPoolOptions;
import io.reactiverse.pgclient.PgRowSet;
import io.reactiverse.pgclient.Row;
import io.reactiverse.pgclient.Tuple;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class SyncAD implements Handler<Long> {

    private static final Logger log = LoggerFactory.getLogger(SyncAD.class);

    private final Vertx vertx;
    private final PgPool masterPgPool;
    private final PgPool slavePgPool;
    private final String platformId;
    private final HttpClient httpClient;
    private final String baseUriPath;
    private final String authorizationHeader;
    private final long timeout;
    private final String passwordEncryptKey;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final String filterEventType;
    private final String filterProfiles;
    private final int batchSize;
    private final int idxWebhook;

    public SyncAD(Vertx vertx, JsonObject webhookConfig) {
        this.vertx = vertx;
        this.idxWebhook = webhookConfig.getInteger("idx");
        final String eventStoreConf = (String) vertx.sharedData().getLocalMap("server").get("event-store");
        if (eventStoreConf != null) {
            final JsonObject eventStoreConfig = new JsonObject(eventStoreConf);
            this.platformId = eventStoreConfig.getString("platform");

            final JsonObject eventStorePGConfig = eventStoreConfig.getJsonObject("postgresql");
            if (eventStorePGConfig != null) {
                final PgPoolOptions options = new PgPoolOptions().setPort(eventStorePGConfig.getInteger("port", 5432))
                        .setHost(eventStorePGConfig.getString("host"))
                        .setDatabase(eventStorePGConfig.getString("database"))
                        .setUser(eventStorePGConfig.getString("user"))
                        .setPassword(eventStorePGConfig.getString("password"))
                        .setMaxSize(eventStorePGConfig.getInteger("pool-size", 5));
                this.masterPgPool = PgClient.pool(vertx, options);
            } else {
                throw new ValidationException("invalid.configuration.postgresql");
            }
            final JsonObject eventStorePGSlaveConfig = eventStoreConfig.getJsonObject("postgresql-slave");
            if (eventStorePGSlaveConfig != null) {
                final PgPoolOptions options = new PgPoolOptions()
                        .setPort(eventStorePGSlaveConfig.getInteger("port", 5432))
                        .setHost(eventStorePGSlaveConfig.getString("host"))
                        .setDatabase(eventStorePGSlaveConfig.getString("database"))
                        .setUser(eventStorePGSlaveConfig.getString("user"))
                        .setPassword(eventStorePGSlaveConfig.getString("password"))
                        .setMaxSize(eventStorePGSlaveConfig.getInteger("pool-size", 5));
                this.slavePgPool = PgClient.pool(vertx, options);
            } else {
                this.slavePgPool = masterPgPool;
            }
        } else {
            throw new ValidationException("invalid.configuration.eventstore");
        }

        final JsonObject httpWebhookConfig = webhookConfig.getJsonObject("http-webhook");
        this.passwordEncryptKey = httpWebhookConfig.getString("password-encryption-secret");
        this.timeout = httpWebhookConfig.getLong("timeout", 30000l);
        this.baseUriPath = httpWebhookConfig.getString("base-uri-path");
        this.authorizationHeader = "Basic " + httpWebhookConfig.getString("basic-header");
        final HttpClientOptions options = new HttpClientOptions().setDefaultHost(httpWebhookConfig.getString("host"))
                .setDefaultPort(httpWebhookConfig.getInteger("port")).setSsl(httpWebhookConfig.getBoolean("ssl", true))
                .setMaxPoolSize(httpWebhookConfig.getInteger("pool-size", 5)).setConnectTimeout((int) timeout)
                .setKeepAlive(httpWebhookConfig.getBoolean("keep-alive", true));
        httpClient = vertx.createHttpClient(options);

        final JsonArray filterEventTypes = webhookConfig.getJsonArray("only-types", new JsonArray().add("PASSWORD").add("DELETED"));
        if (filterEventTypes != null && !filterEventTypes.isEmpty()) {
            filterEventType = "AND event_type IN " + filterEventTypes.stream()
                    .map(Object::toString).collect(Collectors.joining("','", "('", "')"));
        } else {
            filterEventType = "";
        }

        final JsonArray profiles = webhookConfig.getJsonArray("profiles", new JsonArray().add("Teacher").add("Personnel").add("Student").add("Guest"));
        if (profiles != null && !profiles.isEmpty()) {
            filterProfiles = "AND profile IN " + profiles.stream()
                    .map(Object::toString).collect(Collectors.joining("','", "('", "') "));
        } else {
            filterProfiles = "";
        }

        batchSize = webhookConfig.getInteger("batch-size", 1000);
    }

    @Override
    public void handle(Long delay) {
        if (!inProgress.compareAndSet(false, true)) {
            log.info("Disable sync AD launch. Because is already in progress.");
            return;
        }
        log.info("Launch sync AD task");
        final long startTime = System.currentTimeMillis();
        extract(ar -> {
            if (ar.succeeded()) {
                final PgRowSet rows = ar.result();
                if (rows.size() == 0) {
                    inProgress.set(false);
                    log.info("List sync AD is empty.");
                    return;
                }
                transform(rows, ar2 -> {
                    if (ar2.succeeded()) {
                        final List<Tuple> tuples = ar2.result();
                        load(tuples, ar3 -> {
                            inProgress.set(false);
                            if (ar3.succeeded()) {
                                if (rows.size() < batchSize) {
                                    log.info("Sync AD succeeded in " + (System.currentTimeMillis() - startTime) + "ms.");
                                } else {
                                    log.info("Sync AD iteration in " + (System.currentTimeMillis() - startTime) + "ms.");
                                    handle(delay);
                                }
                            } else {
                                log.error("Error updating events to sqool", ar3.cause());
                            }
                        });
                    } else {
                        inProgress.set(false);
                        log.error("Error sending events to sqool", ar2.cause());
                    }
                });
            } else {
                inProgress.set(false);
                log.error("Error extracting events to sqool", ar.cause());
            }
        });
    }

    private void extract(Handler<AsyncResult<PgRowSet>> handler) {

        final String query =
            "WITH w as ( " +
            "SELECT id, rank() OVER (PARTITION BY login, event_type ORDER BY date DESC) as r " +
            "FROM events.auth_events " +
            "WHERE platform_id = $1 " + filterProfiles + filterEventType +
            ") " +
            "SELECT e.id as id, date, login, login_alias, password, event_type, e.profile as profile, u.external_id as external_id " +
            "FROM events.auth_events e " +
            "JOIN w ON e.id = w.id " +
            "JOIN repository.users u on e.user_id = u.id " +
            "WHERE w.r = 1 AND ((e.sync >> " + idxWebhook + ") & 1) = 0 " +
            "ORDER BY date ASC " +
            "LIMIT " + batchSize;
        slavePgPool.preparedQuery(query, Tuple.of(platformId), handler);
    }

    private void transform(PgRowSet rows, Handler<AsyncResult<List<Tuple>>> handler) {
        vertx.<List<Object>>executeBlocking(future -> {
            final List<Tuple> tuples = new ArrayList<>();
            final JsonArray events = new JsonArray();
            for (Row row : rows) {
                final JsonObject event = new JsonObject().put("id", row.getValue("id").toString())
                        .put("date", row.getValue("date").toString()).put("userId", row.getString("external_id"))
                        .put("event-type", row.getString("event_type")).put("profile", row.getString("profile"));
                if (Utils.isNotEmpty(row.getString("password"))) {
                    if (Utils.isNotEmpty(passwordEncryptKey)) {
                        try {
                            event.put("password", encryptPassword(row.getString("password")));
                        } catch (Exception e) {
                            log.error("Error encrypting password", e);
                            handler.handle(Future.failedFuture(e));
                            return;
                        }
                    } else {
                        event.put("password", row.getString("password"));
                    }
                }
                if (Utils.isNotEmpty(row.getString("login"))) {
                    event.put("login", row.getString("login"));
                }
                if (Utils.isNotEmpty(row.getString("login_alias"))) {
                    event.put("login-alias", row.getString("login_alias"));
                }
                events.add(event);
                tuples.add(Tuple.of(row.getValue("id")));
            }
            future.complete(Arrays.asList(events, tuples));
        }, ar -> {
            if (ar.succeeded()) {
                final JsonArray events = (JsonArray) ar.result().get(0);
                final List<Tuple> tuples = (List<Tuple>) ar.result().get(1);
                if (!events.isEmpty()) {
                    final HttpClientRequest req = httpClient.put(baseUriPath, resp -> {
                        resp.exceptionHandler(ex -> handler.handle(Future.failedFuture(ex)));
                        if (resp.statusCode() == 200) {
                            handler.handle(Future.succeededFuture(tuples));
                        } else if (resp.statusCode() == 202) {
                            resp.bodyHandler(body -> {
                                final JsonArray ids = new JsonArray(body.toString());
                                final List<Tuple> t = new ArrayList<>();
                                ids.stream().forEach(id -> t.add(Tuple.of(UUID.fromString(id.toString()))));
                                handler.handle(Future.succeededFuture(t));
                            });
                        } else {
                            resp.bodyHandler(body -> log.error("body resp error" + body.toString()));
                            handler.handle(Future.failedFuture(new ValidationException("invalid.status.code : " + resp.statusCode())));
                        }
                    });
                    req.headers()
                            .add("Content-Type", "application/json")
                            .add("Accept", "application/json; charset=UTF-8")
                            .add("Authorization", authorizationHeader);
                    req.exceptionHandler(ex -> handler.handle(Future.failedFuture(ex)));
                    req.setTimeout(timeout);
                    // log.info("send payload : " + events.encode());
                    req.end(events.encode());
                    log.info("Sync AD send ");
                } else {
                    log.warn("Sync AD events is empty.");
                }
            } else {
                log.error("Error preparing payload in blocking context.");
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    private void load(List<Tuple> tuples, Handler<AsyncResult<Void>> handler) {
        final String query =
                "UPDATE events.auth_events " +
                "SET sync = sync + 2^" + idxWebhook +
                " WHERE id = $1 AND ((sync >> " + idxWebhook + ") & 1) = 0 ";
        masterPgPool.preparedBatch(query, tuples, ar -> {
            if (ar.succeeded()) {
                handler.handle(Future.succeededFuture());
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    private String encryptPassword(String plainText) throws Exception {
        return encryptPassword(plainText, random, passwordEncryptKey);
    }

    public static String encryptPassword(String plainText, Random random, String passwordEncryptKey) throws Exception {
        // Generating salt.
        final byte[] salt = new byte[16];
        random.nextBytes(salt);

        // Generating IV.
        final byte[] iv = new byte[16];
        random.nextBytes(iv);

        return encryptPassword(plainText, passwordEncryptKey, salt, iv);
    }

    public static String encryptPassword(String plainText, String passwordEncryptKey, byte[] salt, byte[] iv) throws Exception {
        // Generating IV.
        final IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);

        // Generating key.
        final PBEKeySpec keySpec = new PBEKeySpec(passwordEncryptKey.toCharArray(), salt, 1000, 256);
        final SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final SecretKey secretKey = keyFactory.generateSecret(keySpec);
        final SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getEncoded(), "AES");

        // Encrypt.
        final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
        final byte[] encrypted = cipher.doFinal(plainText.getBytes());

        return
                Base64.getEncoder().encodeToString(salt) + "$" +
                Base64.getEncoder().encodeToString(iv) + "$" +
                Base64.getEncoder().encodeToString(encrypted);
    }

}

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.repositories.gcs;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.http.HttpStatus;
import org.apache.lucene.util.ArrayUtil;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.blobstore.ESMockAPIBasedRepositoryIntegTestCase;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.RestUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.threeten.bp.Duration;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.CREDENTIALS_FILE_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.ENDPOINT_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.TOKEN_URI_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageRepository.BUCKET;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageRepository.CLIENT_NAME;

@SuppressForbidden(reason = "this test uses a HttpServer to emulate a Google Cloud Storage endpoint")
public class GoogleCloudStorageBlobStoreRepositoryTests extends ESMockAPIBasedRepositoryIntegTestCase {

    private static byte[] serviceAccount;

    @Override
    protected String repositoryType() {
        return GoogleCloudStorageRepository.TYPE;
    }

    @Override
    protected Settings repositorySettings() {
        return Settings.builder()
            .put(super.repositorySettings())
            .put(BUCKET.getKey(), "bucket")
            .put(CLIENT_NAME.getKey(), "test")
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singletonList(TestGoogleCloudStoragePlugin.class);
    }

    @Override
    protected Map<String, HttpHandler> createHttpHandlers() {
        final Map<String, HttpHandler> handlers = new HashMap<>(2);
        handlers.put("/", new InternalHttpHandler());
        handlers.put("/token", new FakeOAuth2HttpHandler());
        return Collections.unmodifiableMap(handlers);
    }

    @Override
    protected HttpHandler createErroneousHttpHandler(final HttpHandler delegate) {
        return new GoogleErroneousHttpHandler(delegate, randomIntBetween(2, 3));
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        if (serviceAccount == null) {
            serviceAccount = createServiceAccount();
        }

        final Settings.Builder settings = Settings.builder();
        settings.put(super.nodeSettings(nodeOrdinal));
        settings.put(ENDPOINT_SETTING.getConcreteSettingForNamespace("test").getKey(), httpServerUrl());
        settings.put(TOKEN_URI_SETTING.getConcreteSettingForNamespace("test").getKey(), httpServerUrl() + "/token");

        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setFile(CREDENTIALS_FILE_SETTING.getConcreteSettingForNamespace("test").getKey(), serviceAccount);
        settings.setSecureSettings(secureSettings);
        return settings.build();
    }

    public void testChunkSize() {
        // default chunk size
        RepositoryMetaData repositoryMetaData = new RepositoryMetaData("repo", GoogleCloudStorageRepository.TYPE, Settings.EMPTY);
        ByteSizeValue chunkSize = GoogleCloudStorageRepository.getSetting(GoogleCloudStorageRepository.CHUNK_SIZE, repositoryMetaData);
        assertEquals(GoogleCloudStorageRepository.MAX_CHUNK_SIZE, chunkSize);

        // chunk size in settings
        final int size = randomIntBetween(1, 100);
        repositoryMetaData = new RepositoryMetaData("repo", GoogleCloudStorageRepository.TYPE,
                                                       Settings.builder().put("chunk_size", size + "mb").build());
        chunkSize = GoogleCloudStorageRepository.getSetting(GoogleCloudStorageRepository.CHUNK_SIZE, repositoryMetaData);
        assertEquals(new ByteSizeValue(size, ByteSizeUnit.MB), chunkSize);

        // zero bytes is not allowed
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> {
            final RepositoryMetaData repoMetaData = new RepositoryMetaData("repo", GoogleCloudStorageRepository.TYPE,
                                                                        Settings.builder().put("chunk_size", "0").build());
            GoogleCloudStorageRepository.getSetting(GoogleCloudStorageRepository.CHUNK_SIZE, repoMetaData);
        });
        assertEquals("failed to parse value [0] for setting [chunk_size], must be >= [1b]", e.getMessage());

        // negative bytes not allowed
        e = expectThrows(IllegalArgumentException.class, () -> {
            final RepositoryMetaData repoMetaData = new RepositoryMetaData("repo", GoogleCloudStorageRepository.TYPE,
                                                                        Settings.builder().put("chunk_size", "-1").build());
            GoogleCloudStorageRepository.getSetting(GoogleCloudStorageRepository.CHUNK_SIZE, repoMetaData);
        });
        assertEquals("failed to parse value [-1] for setting [chunk_size], must be >= [1b]", e.getMessage());

        // greater than max chunk size not allowed
        e = expectThrows(IllegalArgumentException.class, () -> {
            final RepositoryMetaData repoMetaData = new RepositoryMetaData("repo", GoogleCloudStorageRepository.TYPE,
                                                                        Settings.builder().put("chunk_size", "101mb").build());
            GoogleCloudStorageRepository.getSetting(GoogleCloudStorageRepository.CHUNK_SIZE, repoMetaData);
        });
        assertEquals("failed to parse value [101mb] for setting [chunk_size], must be <= [100mb]", e.getMessage());
    }

    public static class TestGoogleCloudStoragePlugin extends GoogleCloudStoragePlugin {

        public TestGoogleCloudStoragePlugin(Settings settings) {
            super(settings);
        }

        @Override
        protected GoogleCloudStorageService createStorageService() {
            return new GoogleCloudStorageService() {
                @Override
                StorageOptions createStorageOptions(final GoogleCloudStorageClientSettings clientSettings,
                                                    final HttpTransportOptions httpTransportOptions) {
                    StorageOptions options = super.createStorageOptions(clientSettings, httpTransportOptions);
                    return options.toBuilder()
                        .setRetrySettings(RetrySettings.newBuilder()
                            .setTotalTimeout(options.getRetrySettings().getTotalTimeout())
                            .setInitialRetryDelay(Duration.ofMillis(10L))
                            .setRetryDelayMultiplier(options.getRetrySettings().getRetryDelayMultiplier())
                            .setMaxRetryDelay(Duration.ofSeconds(1L))
                            .setMaxAttempts(0)
                            .setJittered(false)
                            .setInitialRpcTimeout(options.getRetrySettings().getInitialRpcTimeout())
                            .setRpcTimeoutMultiplier(options.getRetrySettings().getRpcTimeoutMultiplier())
                            .setMaxRpcTimeout(options.getRetrySettings().getMaxRpcTimeout())
                            .build())
                        .build();
                }
            };
        }

        @Override
        public Map<String, Repository.Factory> getRepositories(Environment env, NamedXContentRegistry registry, ThreadPool threadPool) {
            return Collections.singletonMap(GoogleCloudStorageRepository.TYPE,
                metadata -> new GoogleCloudStorageRepository(metadata, registry, this.storageService, threadPool) {
                    @Override
                    protected GoogleCloudStorageBlobStore createBlobStore() {
                        return new GoogleCloudStorageBlobStore("bucket", "test", storageService) {
                            @Override
                            long getLargeBlobThresholdInBytes() {
                                return ByteSizeUnit.MB.toBytes(1);
                            }
                        };
                    }
                });
        }
    }

    private static byte[] createServiceAccount() {
        try {
            final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(1024);
            final String privateKey = Base64.getEncoder().encodeToString(keyPairGenerator.generateKeyPair().getPrivate().getEncoded());

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), out)) {
                builder.startObject();
                {
                    builder.field("type", "service_account");
                    builder.field("project_id", getTestClass().getName().toLowerCase(Locale.ROOT));
                    builder.field("private_key_id", UUID.randomUUID().toString());
                    builder.field("private_key", "-----BEGIN PRIVATE KEY-----\n" + privateKey + "\n-----END PRIVATE KEY-----\n");
                    builder.field("client_email", "elastic@appspot.gserviceaccount.com");
                    builder.field("client_id", String.valueOf(randomNonNegativeLong()));
                }
                builder.endObject();
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError("Unable to create service account file", e);
        }
    }

    /**
     * Minimal HTTP handler that acts as a Google Cloud Storage compliant server
     */
    @SuppressForbidden(reason = "this test uses a HttpServer to emulate a Google Cloud Storage endpoint")
    private static class InternalHttpHandler implements HttpHandler {

        private final ConcurrentMap<String, BytesArray> blobs = new ConcurrentHashMap<>();

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            final String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().toString();
            try {
                if (Regex.simpleMatch("GET /storage/v1/b/bucket/o*", request)) {
                    final Map<String, String> params = new HashMap<>();
                    RestUtils.decodeQueryString(exchange.getRequestURI().getQuery(), 0, params);
                    final String prefix = params.get("prefix");

                    final List<Map.Entry<String, BytesArray>> listOfBlobs = blobs.entrySet().stream()
                        .filter(blob -> prefix == null || blob.getKey().startsWith(prefix)).collect(Collectors.toList());

                    final StringBuilder list = new StringBuilder();
                    list.append("{\"kind\":\"storage#objects\",\"items\":[");
                    for (Iterator<Map.Entry<String, BytesArray>> it = listOfBlobs.iterator(); it.hasNext(); ) {
                        Map.Entry<String, BytesArray> blob = it.next();
                        list.append("{\"kind\":\"storage#object\",");
                        list.append("\"bucket\":\"bucket\",");
                        list.append("\"name\":\"").append(blob.getKey()).append("\",");
                        list.append("\"id\":\"").append(blob.getKey()).append("\",");
                        list.append("\"size\":\"").append(blob.getValue().length()).append("\"");
                        list.append('}');

                        if (it.hasNext()) {
                            list.append(',');
                        }
                    }
                    list.append("]}");

                    byte[] response = list.toString().getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(RestStatus.OK.getStatus(), response.length);
                    exchange.getResponseBody().write(response);

                } else if (Regex.simpleMatch("GET /storage/v1/b/bucket*", request)) {
                    byte[] response = ("{\"kind\":\"storage#bucket\",\"name\":\"bucket\",\"id\":\"0\"}").getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
                    exchange.getResponseBody().write(response);

                } else if (Regex.simpleMatch("GET /download/storage/v1/b/bucket/o/*", request)) {
                    BytesArray blob = blobs.get(exchange.getRequestURI().getPath().replace("/download/storage/v1/b/bucket/o/", ""));
                    if (blob != null) {
                        final String range = exchange.getRequestHeaders().getFirst("Range");
                        Matcher matcher = Pattern.compile("bytes=([0-9]*)-([0-9]*)").matcher(range);
                        assert matcher.find();

                        byte[] response = Integer.parseInt(matcher.group(1)) == 0 ? blob.array() : new byte[0];
                        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                        exchange.sendResponseHeaders(RestStatus.OK.getStatus(), response.length);
                        exchange.getResponseBody().write(response);
                    } else {
                        exchange.sendResponseHeaders(RestStatus.NOT_FOUND.getStatus(), -1);
                    }

                } else if (Regex.simpleMatch("DELETE /storage/v1/b/bucket/o/*", request)) {
                    int deletions = 0;
                    for (Iterator<Map.Entry<String, BytesArray>> iterator = blobs.entrySet().iterator(); iterator.hasNext(); ) {
                        Map.Entry<String, BytesArray> blob = iterator.next();
                        if (blob.getKey().equals(exchange.getRequestURI().toString())) {
                            iterator.remove();
                            deletions++;
                        }
                    }
                    exchange.sendResponseHeaders((deletions > 0 ? RestStatus.OK : RestStatus.NO_CONTENT).getStatus(), -1);

                } else if (Regex.simpleMatch("POST /batch/storage/v1", request)) {
                    final String uri = "/storage/v1/b/bucket/o/";
                    final StringBuilder batch = new StringBuilder();
                    for (String line : Streams.readAllLines(new BufferedInputStream(exchange.getRequestBody()))) {
                        if (line.length() == 0 || line.startsWith("--") || line.toLowerCase(Locale.ROOT).startsWith("content")) {
                            batch.append(line).append('\n');
                        } else if (line.startsWith("DELETE")) {
                            final String name = line.substring(line.indexOf(uri) + uri.length(), line.lastIndexOf(" HTTP"));
                            if (Strings.hasText(name)) {
                                try {
                                    final String blobName = URLDecoder.decode(name, UTF_8.name());
                                    if (blobs.entrySet().removeIf(blob -> blob.getKey().equals(blobName))) {
                                        batch.append("HTTP/1.1 204 NO_CONTENT").append('\n');
                                        batch.append('\n');
                                    }
                                } catch (UnsupportedEncodingException e) {
                                    batch.append("HTTP/1.1 404 NOT_FOUND").append('\n');
                                    batch.append('\n');
                                }
                            }
                        }
                    }
                    byte[] response = batch.toString().getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type"));
                    exchange.sendResponseHeaders(RestStatus.OK.getStatus(), response.length);
                    exchange.getResponseBody().write(response);

                } else if (Regex.simpleMatch("POST /upload/storage/v1/b/bucket/*uploadType=multipart*", request)) {
                    try (BufferedInputStream in = new BufferedInputStream(new GZIPInputStream(exchange.getRequestBody()))) {
                        byte[] response = new byte[0];
                        String blob = null;
                        int read;
                        while ((read = in.read()) != -1) {
                            boolean markAndContinue = false;
                            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                                do { // search next consecutive {carriage return, new line} chars and stop
                                    if ((char) read == '\r') {
                                        int next = in.read();
                                        if (next != -1) {
                                            if (next == '\n') {
                                                break;
                                            }
                                            out.write(read);
                                            out.write(next);
                                            continue;
                                        }
                                    }
                                    out.write(read);
                                } while ((read = in.read()) != -1);

                                final String line = new String(out.toByteArray(), UTF_8);
                                if (line.length() == 0 || line.equals("\r\n") || line.startsWith("--")
                                    || line.toLowerCase(Locale.ROOT).startsWith("content")) {
                                    markAndContinue = true;
                                } else if (line.startsWith("{\"bucket\":\"bucket\"")) {
                                    markAndContinue = true;
                                    Matcher matcher = Pattern.compile("\"name\":\"([^\"]*)\"").matcher(line);
                                    if (matcher.find()) {
                                        blob = matcher.group(1);
                                        response = line.getBytes(UTF_8);
                                    }
                                }
                                if (markAndContinue) {
                                    in.mark(Integer.MAX_VALUE);
                                    continue;
                                }
                            }
                            if (blob != null) {
                                in.reset();
                                try (ByteArrayOutputStream binary = new ByteArrayOutputStream()) {
                                    while ((read = in.read()) != -1) {
                                        binary.write(read);
                                    }
                                    binary.flush();
                                    byte[] tmp = binary.toByteArray();
                                    // removes the trailing end "\r\n--__END_OF_PART__--\r\n" which is 23 bytes long
                                    blobs.put(blob, new BytesArray(Arrays.copyOf(tmp, tmp.length - 23)));

                                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(RestStatus.OK.getStatus(), response.length);
                                    exchange.getResponseBody().write(response);

                                } finally {
                                    blob = null;
                                }
                            }
                        }
                    }

                } else if (Regex.simpleMatch("POST /upload/storage/v1/b/bucket/*uploadType=resumable*", request)) {
                    final Map<String, String> params = new HashMap<>();
                    RestUtils.decodeQueryString(exchange.getRequestURI().getQuery(), 0, params);
                    final String blobName = params.get("name");
                    blobs.put(blobName, BytesArray.EMPTY);

                    byte[] response = Streams.readFully(exchange.getRequestBody()).utf8ToString().getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Location", httpServerUrl() + "/upload/storage/v1/b/bucket/o?"
                        + "uploadType=resumable"
                        + "&upload_id=" + UUIDs.randomBase64UUID()
                        + "&test_blob_name=" + blobName); // not a Google Storage parameter, but it allows to pass the blob name
                    exchange.sendResponseHeaders(RestStatus.OK.getStatus(), response.length);
                    exchange.getResponseBody().write(response);

                } else if (Regex.simpleMatch("PUT /upload/storage/v1/b/bucket/o?*uploadType=resumable*", request)) {
                    final Map<String, String> params = new HashMap<>();
                    RestUtils.decodeQueryString(exchange.getRequestURI().getQuery(), 0, params);

                    final String blobName = params.get("test_blob_name");
                    final String range = exchange.getRequestHeaders().getFirst("Content-Range");
                    assert Strings.hasLength(range);

                    Matcher matcher = Pattern.compile("bytes ([^/]*)/([0-9\\*]*)").matcher(range);
                    if (matcher.find()) {
                        String bytes = matcher.group(1);
                        String limit = matcher.group(2);
                        byte[] blob = blobs.get(blobName).array();
                        assert blob != null;
                        // client is uploading a chunk
                        matcher = Pattern.compile("([0-9]*)-([0-9]*)").matcher(bytes);
                        assert matcher.find();

                        int end = Integer.parseInt(matcher.group(2));
                        int start = Integer.parseInt(matcher.group(1));

                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        long count = Streams.copy(exchange.getRequestBody(), out);
                        int length = Math.max(end + 1, "*".equals(limit) ? 0 : Integer.parseInt(limit));
                        assert count <= length;
                        if (length > blob.length) {
                            blob = ArrayUtil.growExact(blob, length);
                        }
                        assert blob.length >= end;
                        System.arraycopy(out.toByteArray(), 0, blob, start, Math.toIntExact(count));
                        blobs.put(blobName, new BytesArray(blob));

                        if ("*".equals(limit)) {
                            exchange.getResponseHeaders().add("Range", String.format(Locale.ROOT, "bytes=%d/%d", start, end));
                            exchange.getResponseHeaders().add("Content-Length", "0");
                            exchange.sendResponseHeaders(308 /* Resume Incomplete */, -1);
                        } else {
                            assert blob.length == Integer.parseInt(limit);
                            exchange.sendResponseHeaders(RestStatus.OK.getStatus(), -1);
                        }
                    }
                } else {
                    exchange.sendResponseHeaders(RestStatus.INTERNAL_SERVER_ERROR.getStatus(), -1);
                }
            } finally {
                exchange.close();
            }
        }
    }

    @SuppressForbidden(reason = "this test uses a HttpServer to emulate a fake OAuth2 authentication service")
    private static class FakeOAuth2HttpHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            byte[] response = ("{\"access_token\":\"foo\",\"token_type\":\"Bearer\",\"expires_in\":3600}").getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    /**
     * HTTP handler that injects random  Google Cloud Storage service errors
     *
     * Note: it is not a good idea to allow this handler to simulate too many errors as it would
     * slow down the test suite.
     */
    @SuppressForbidden(reason = "this test uses a HttpServer to emulate a Google Cloud Storage endpoint")
    private static class GoogleErroneousHttpHandler extends ErroneousHttpHandler {

        GoogleErroneousHttpHandler(final HttpHandler delegate, final int maxErrorsPerRequest) {
            super(delegate, maxErrorsPerRequest);
        }

        @Override
        protected String requestUniqueId(HttpExchange exchange) {
            final String range = exchange.getRequestHeaders().getFirst("Content-Range");
            return exchange.getRemoteAddress().toString()
                + " " + exchange.getRequestMethod()
                + " " + exchange.getRequestURI()
                + (range != null ?  " " + range :  "");
        }

        @Override
        protected boolean canFailRequest(final HttpExchange exchange) {
            // Batch requests are not retried so we don't want to fail them
            // The batched request are supposed to be retried (not tested here)
            return exchange.getRequestURI().toString().startsWith("/batch/") == false;
        }
    }
}

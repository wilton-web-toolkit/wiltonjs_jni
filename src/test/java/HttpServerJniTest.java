/*
 * Copyright 2016, alex at staticlibs.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.wiltonwebtoolkit.HttpGateway;

import static net.wiltonwebtoolkit.HttpServerJni.*;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.junit.Assert.*;
import static utils.TestUtils.httpGet;
import static utils.TestUtils.httpGetCode;
import static utils.TestUtils.httpPost;

import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import org.junit.Test;

public class HttpServerJniTest {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final int TCP_PORT = 8080;
    private static final String ROOT_URL = "http://127.0.0.1:" + TCP_PORT + "/";
    private static final String ROOT_RESP = "Hello Java!\n";
    private static final String NOT_FOUND_RESP = "Not found\n";
    private static final String LOG_DATA = "Please append me to log";
    private static final String STATIC_FILE_DATA = "I am data from static file";
    private static final String STATIC_ZIP_DATA = "I am data from ZIP file";

    private CloseableHttpClient http = HttpClients.createDefault();

    private static class TestGateway implements HttpGateway {

        @Override
        public void gatewayCallback(long requestHandle) {
            try {
                String meta = getRequestMetadata(requestHandle);
                Map<String, Object> metaMap = GSON.fromJson(meta, MAP_TYPE);
                String path = String.valueOf(metaMap.get("pathname"));
                final String resp;
                if ("/".equalsIgnoreCase(path)) {
                    resp = ROOT_RESP;
                } else if ("/headers".equalsIgnoreCase(path)) {
                    String json = GSON.toJson(ImmutableMap.builder()
                            .put("headers", ImmutableMap.builder()
                                    .put("X-Server-H1", "foo")
                                    .put("X-Server-H2", "bar")
                                    .build())
                            .build());
                    setResponseMetadata(requestHandle, json);
                    resp = GSON.toJson(metaMap.get("headers"));
                } else if ("/postmirror".equalsIgnoreCase(path)) {
                    resp = getRequestData(requestHandle);
                } else if ("/logger".equalsIgnoreCase(path)) {
                    String data = getRequestData(requestHandle);
                    appendLog("INFO", HttpServerJniTest.class.getName(), data);
                    resp = "";
                } else if ("/sendfile".equalsIgnoreCase(path)) {
                    String filename = getRequestData(requestHandle);
                    sendFile(requestHandle, filename);
                    resp = null;
                } else {
                    String json = GSON.toJson(ImmutableMap.builder()
                            .put("statusCode", 404)
                            .put("statusMessage", "Not Found")
                            .build());
                    setResponseMetadata(requestHandle, json);
                    resp = NOT_FOUND_RESP;
                }
                if (null != resp) { // sendfile case
                    sendResponse(requestHandle, resp);
                }
            } catch (Throwable e) {
                e.printStackTrace();
                sendResponse(requestHandle, e.getMessage());
            }
        }
    }
    
    @Test
    public void testSimple() throws Exception {
        long handle = 0;
        try {
            handle = createServer(new TestGateway(), GSON.toJson(ImmutableMap.builder()
                    .put("tcpPort", TCP_PORT)
                    .build()));
            assertEquals(ROOT_RESP, httpGet(ROOT_URL));
            assertEquals("foo", httpPost(ROOT_URL + "postmirror", "foo"));
            assertEquals(NOT_FOUND_RESP, httpGet(ROOT_URL + "foo"));
            assertEquals(404, httpGetCode(ROOT_URL + "foo"));
        } finally {
            stopServer(handle);
        }
    }

    @Test
    public void testLogging() throws Exception {
        File dir = null;
        long handle = 0;
        try {
            dir = Files.createTempDir();
            File logfile = new File(dir, "test.log");
            handle = createServer(new TestGateway(), GSON.toJson(ImmutableMap.builder()
                    .put("tcpPort", TCP_PORT)
                    .put("logging", ImmutableMap.builder()
                            .put("appenders", ImmutableList.builder()
                                    .add(ImmutableMap.builder()
                                            .put("appenderType", "FILE")
                                            .put("thresholdLevel", "DEBUG")
                                            .put("filePath", logfile.getAbsolutePath())
                                            .put("layout", "%m")
                                            .build())
                                    .build())
                            .put("loggers", ImmutableList.builder()
                                    .add(ImmutableMap.builder()
                                            .put("name", "staticlib.httpserver")
                                            .put("level", "WARN")
                                            .build())
                                    .build())
                            .build())
                    .build()));
            assertEquals(ROOT_RESP, httpGet(ROOT_URL));
            httpPost(ROOT_URL + "logger", LOG_DATA);
            assertEquals(LOG_DATA, FileUtils.readFileToString(logfile, "UTF-8"));
        } finally {
            stopServer(handle);
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void testDocumentRoot() throws Exception {
        File dir = null;
        long handle = 0;
        try {
            dir = Files.createTempDir();
            // prepare data
            FileUtils.writeStringToFile(new File(dir, "test.txt"), STATIC_FILE_DATA);
            File zipFile = new File(dir, "test.zip");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zipper = new ZipOutputStream(baos);
            zipper.putNextEntry(new ZipEntry("test/zipped.txt"));
            zipper.write(STATIC_ZIP_DATA.getBytes("UTF-8"));
            zipper.close();
            FileUtils.writeByteArrayToFile(zipFile, baos.toByteArray());
            handle = createServer(new TestGateway(), GSON.toJson(ImmutableMap.builder()
                    .put("tcpPort", TCP_PORT)
                    .put("documentRoots", ImmutableList.builder()
                            .add(ImmutableMap.builder()
                                    .put("resource", "/static/files/")
                                    .put("dirPath", dir.getAbsolutePath())
                                    .build())
                            .add(ImmutableMap.builder()
                                    .put("resource", "/static/")
                                    .put("zipPath", zipFile.getAbsolutePath())
                                    .build())
                            .build())
                    .build()));
            assertEquals(ROOT_RESP, httpGet(ROOT_URL));
            // deliberated repeated requests
            assertEquals(STATIC_FILE_DATA, httpGet(ROOT_URL + "static/files/test.txt"));
            assertEquals(STATIC_FILE_DATA, httpGet(ROOT_URL + "static/files/test.txt"));
            assertEquals(STATIC_FILE_DATA, httpGet(ROOT_URL + "static/files/test.txt"));
            assertEquals(STATIC_ZIP_DATA, httpGet(ROOT_URL + "static/test/zipped.txt"));
            assertEquals(STATIC_ZIP_DATA, httpGet(ROOT_URL + "static/test/zipped.txt"));
            assertEquals(STATIC_ZIP_DATA, httpGet(ROOT_URL + "static/test/zipped.txt"));
        } finally {
            stopServer(handle);
            FileUtils.deleteDirectory(dir);
        }
    }

    // Duplicates in raw headers are handled in the following ways, depending on the header name:
    // Duplicates of age, authorization, content-length, content-type, etag, expires,
    // from, host, if-modified-since, if-unmodified-since, last-modified, location,
    // max-forwards, proxy-authorization, referer, retry-after, or user-agent are discarded.
    // For all other headers, the values are joined together with ', '.
    @Test
    public void testHeaders() throws Exception {
        long handle = 0;
        try {
            handle = createServer(new TestGateway(), GSON.toJson(ImmutableMap.builder()
                    .put("tcpPort", TCP_PORT)
                    .build()));
            assertEquals(ROOT_RESP, httpGet(ROOT_URL));
            CloseableHttpResponse resp = null;
            String output;
            Map<String, String> serverHeaders = new LinkedHashMap<String, String>();
            try {
                HttpGet get = new HttpGet(ROOT_URL + "headers");
                get.addHeader("X-Dupl-H", "foo");
                get.addHeader("X-Dupl-H", "bar");
                get.addHeader("Referer", "foo");
                get.addHeader("referer", "bar");
                resp = http.execute(get);
                for (Header he : resp.getAllHeaders()) {
                    serverHeaders.put(he.getName(), he.getValue());
                }
                output = EntityUtils.toString(resp.getEntity(), "UTF-8");
            } finally {
                closeQuietly(resp);
            }
            Map<String, String> clientHeaders = GSON.fromJson(output, STRING_MAP_TYPE);
            assertTrue(clientHeaders.containsKey("X-Dupl-H"));
            assertTrue(clientHeaders.get("X-Dupl-H").contains("foo"));
            assertTrue(clientHeaders.get("X-Dupl-H").contains("bar"));
            assertTrue(clientHeaders.get("X-Dupl-H").contains(","));
            assertTrue(clientHeaders.containsKey("referer") || clientHeaders.containsKey("Referer"));
            if (clientHeaders.containsKey("referer")) {
                assertEquals("bar", clientHeaders.get("referer"));
            } else {
                assertEquals("foo", clientHeaders.get("Referer"));
            }
            assertEquals("foo", serverHeaders.get("X-Server-H1"));
            assertEquals("bar", serverHeaders.get("X-Server-H2"));
        } finally {
            stopServer(handle);
        }
    }

    @Test
    public void testSendFile() throws Exception {
        long handle = 0;
        File dir = null;
        try {
            dir = Files.createTempDir();
            File file = new File(dir, "test.txt");
            FileUtils.writeStringToFile(file, STATIC_FILE_DATA);
            handle = createServer(new TestGateway(), GSON.toJson(ImmutableMap.builder()
                    .put("tcpPort", TCP_PORT)
                    .build()));
            assertEquals(ROOT_RESP, httpGet(ROOT_URL));
            assertTrue(file.exists());
            String contents = httpPost(ROOT_URL + "sendfile", file.getAbsolutePath());
            assertEquals(STATIC_FILE_DATA, contents);
            Thread.sleep(200);
            assertFalse(file.exists());
        } finally {
            stopServer(handle);
            FileUtils.deleteDirectory(dir);
        }
    }

}
/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.wrapper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;

public class Download implements IDownload {
    public static final String UNKNOWN_VERSION = "0";

    private static final int BUFFER_SIZE = 10 * 1024;
    private static final int PROGRESS_CHUNK = 1024 * 1024;
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;
    private static final int READ_TIMEOUT_MILLISECONDS = 10 * 1000;
    private final Logger logger;
    private final String appName;
    private final String appVersion;
    private final DownloadProgressListener progressListener;

    public Download(Logger logger, String appName, String appVersion) {
        this(logger, null, appName, appVersion);
    }

    public Download(Logger logger, DownloadProgressListener progressListener, String appName, String appVersion) {
        this.logger = logger;
        this.appName = appName;
        this.appVersion = appVersion;
        this.progressListener = new DefaultDownloadProgressListener(logger, progressListener);
        configureProxyAuthentication();
    }

    private void populateProxyPropertiesFromEnvironmentVariable(String protocol) {
        // Do not fallback to environment variables if a proxy has been set via properties.
        if (System.getProperty(protocol + ".proxyHost") != null) {
            return;
        }

        String variableName = protocol + "_proxy";
        String proxyString = System.getenv(variableName);
        if (proxyString == null) {
            proxyString = System.getenv(variableName.toUpperCase());
        }

        try {
            URL proxyUrl = new URL(proxyString);
            System.setProperty(protocol + ".proxyHost", proxyUrl.getHost());

            int port = proxyUrl.getPort();
            if (port > 0) {
                System.setProperty(protocol + ".proxyPort", String.valueOf(port));
            }

            String userInfo = proxyUrl.getUserInfo();
            if (userInfo != null) {
                String[] userAndPassword = userInfo.split(":", 2);
                System.setProperty(protocol + ".proxyUser", userAndPassword[0]);
                if (userAndPassword.length > 1) {
                    System.setProperty(protocol + ".proxyPassword", userAndPassword[1]);
                }
            }
        } catch (MalformedURLException e) {
            // Simply ignore a malformed proxy URL.
            if (proxyString != null) {
                logger.log("Unable to parse value '" + proxyString + "' of environment variable '" + variableName + "' as a URL.");
                if (!proxyString.startsWith("http")) {
                    logger.log("Please ensure it contains a valid protocol prefix.");
                }
            }
        }
    }

    private void configureProxyAuthentication() {
        populateProxyPropertiesFromEnvironmentVariable("http");
        populateProxyPropertiesFromEnvironmentVariable("https");
        Authenticator.setDefault(getProxyPasswordAuthenticator());
    }

    private Authenticator getProxyPasswordAuthenticator() {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                // While Java itself only defines the use of the "http(s).proxyHost" and "http(s).proxyPort" properties, see [1] for Java 8, the Apache HttpClient established the use of the
                // "http(s).proxyUser" and "http(s).proxyPassword" properties, see [2], so support them here.
                // [1] https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Proxies
                // [2] https://hc.apache.org/httpcomponents-client-5.0.x/httpclient5/xref/org/apache/hc/client5/http/impl/auth/SystemDefaultCredentialsProvider.html#L117

                String protocol = getRequestingScheme();

                if ("http".equals(protocol)) {
                    String proxyUser = System.getProperty("http.proxyUser");
                    if (proxyUser != null) {
                        String proxyPassword = System.getProperty("http.proxyPassword", "");
                        return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                    }
                }

                if ("https".equals(protocol)) {
                    String proxyUser = System.getProperty("https.proxyUser");
                    if (proxyUser != null) {
                        String proxyPassword = System.getProperty("https.proxyPassword", "");
                        return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                    }
                }

                return super.getPasswordAuthentication();
            }
        };
    }

    public void download(URI address, File destination) throws Exception {
        destination.getParentFile().mkdirs();
        downloadInternal(address, destination);
    }

    private void downloadInternal(URI address, File destination)
        throws Exception {
        OutputStream out = null;
        URLConnection conn;
        InputStream in = null;
        URL safeUrl = safeUri(address).toURL();
        try {
            out = new BufferedOutputStream(new FileOutputStream(destination));

            // Note that no proxy is passed here as we configure any proxy globally using the HTTP(S) proxy system properties. The implementation will choose to look at the properties belonging to
            // the protocol of the URL.
            conn = safeUrl.openConnection();

            addBasicAuthentication(address, conn);
            final String userAgentValue = calculateUserAgent();
            conn.setRequestProperty("User-Agent", userAgentValue);
            conn.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            conn.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            in = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int numRead;
            int totalLength = conn.getContentLength();
            long downloadedLength = 0;
            long progressCounter = 0;
            while ((numRead = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.print("interrupted");
                    throw new IOException("Download was interrupted.");
                }

                downloadedLength += numRead;
                progressCounter += numRead;

                if (progressCounter / PROGRESS_CHUNK > 0 || downloadedLength == totalLength) {
                    progressCounter = progressCounter - PROGRESS_CHUNK;
                    progressListener.downloadStatusChanged(address, totalLength, downloadedLength);
                }

                out.write(buffer, 0, numRead);
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Downloading from " + safeUrl + " failed: timeout", e);
        } finally {
            logger.log("");
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Create a safe URI from the given one by stripping out user info.
     *
     * @param uri Original URI
     * @return a new URI with no user info
     */
    static URI safeUri(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to parse URI", e);
        }
    }

    private void addBasicAuthentication(URI address, URLConnection connection) throws IOException {
        String userInfo = calculateUserInfo(address);
        if (userInfo == null) {
            return;
        }
        if (!"https".equals(address.getScheme())) {
            logger.log("WARNING Using HTTP Basic Authentication over an insecure connection to download the Gradle distribution. Please consider using HTTPS.");
        }
        connection.setRequestProperty("Authorization", "Basic " + base64Encode(userInfo));
    }

    /**
     * Base64 encode user info for HTTP Basic Authentication.
     *
     * Try to use {@literal java.util.Base64} encoder which is available starting with Java 8.
     * Fallback to {@literal javax.xml.bind.DatatypeConverter} from JAXB which is available starting with Java 6 but is not anymore in Java 9.
     * Fortunately, both of these two Base64 encoders implement the right Base64 flavor, the one that does not split the output in multiple lines.
     *
     * @param userInfo user info
     * @return Base64 encoded user info
     * @throws RuntimeException if no public Base64 encoder is available on this JVM
     */
    private String base64Encode(String userInfo) {
        ClassLoader loader = getClass().getClassLoader();
        try {
            Method getEncoderMethod = loader.loadClass("java.util.Base64").getMethod("getEncoder");
            Method encodeMethod = loader.loadClass("java.util.Base64$Encoder").getMethod("encodeToString", byte[].class);
            Object encoder = getEncoderMethod.invoke(null);
            return (String) encodeMethod.invoke(encoder, new Object[]{userInfo.getBytes("UTF-8")});
        } catch (Exception java7OrEarlier) {
            try {
                Method encodeMethod = loader.loadClass("javax.xml.bind.DatatypeConverter").getMethod("printBase64Binary", byte[].class);
                return (String) encodeMethod.invoke(null, new Object[]{userInfo.getBytes("UTF-8")});
            } catch (Exception java5OrEarlier) {
                throw new RuntimeException("Downloading Gradle distributions with HTTP Basic Authentication is not supported on your JVM.", java5OrEarlier);
            }
        }
    }

    private String calculateUserInfo(URI uri) {
        String username = System.getProperty("gradle.wrapperUser");
        String password = System.getProperty("gradle.wrapperPassword");
        if (username != null && password != null) {
            return username + ':' + password;
        }
        return uri.getUserInfo();
    }

    private String calculateUserAgent() {
        String javaVendor = System.getProperty("java.vendor");
        String javaVersion = System.getProperty("java.version");
        String javaVendorVersion = System.getProperty("java.vm.version");
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        return String.format("%s/%s (%s;%s;%s) (%s;%s;%s)", appName, appVersion, osName, osVersion, osArch, javaVendor, javaVersion, javaVendorVersion);
    }

    private static class DefaultDownloadProgressListener implements DownloadProgressListener {
        private final Logger logger;
        private final DownloadProgressListener delegate;
        private int previousDownloadPercent;

        public DefaultDownloadProgressListener(Logger logger, DownloadProgressListener delegate) {
            this.logger = logger;
            this.delegate = delegate;
            this.previousDownloadPercent = 0;
        }

        @Override
        public void downloadStatusChanged(URI address, long contentLength, long downloaded) {
            // If the total size of distribution is known, but there's no advanced progress listener, provide extra progress information
            if (contentLength > 0 && delegate == null) {
                appendPercentageSoFar(contentLength, downloaded);
            }

            if (contentLength != downloaded) {
                logger.append(".");
            }

            if (delegate != null) {
                delegate.downloadStatusChanged(address, contentLength, downloaded);
            }
        }

        private void appendPercentageSoFar(long contentLength, long downloaded) {
            try {
                int currentDownloadPercent = 10 * (calculateDownloadPercent(contentLength, downloaded) / 10);
                if (currentDownloadPercent != 0 && previousDownloadPercent != currentDownloadPercent) {
                    logger.append(String.valueOf(currentDownloadPercent)).append('%');
                    previousDownloadPercent = currentDownloadPercent;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private int calculateDownloadPercent(long totalLength, long downloadedLength) {
            return Math.min(100, Math.max(0, (int) ((downloadedLength / (double) totalLength) * 100)));
        }
    }
}

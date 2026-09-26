package ru.lighthouse.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/** TLS transport probe only. Never use this factory for updates, credentials, or private data. */
final class DiagnosticTls {
    private DiagnosticTls() {}

    static SSLSocketFactory unverifiedFactory() throws Exception {
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[]{trust}, new SecureRandom());
        return context.getSocketFactory();
    }
}

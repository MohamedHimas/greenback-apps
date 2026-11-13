package com.greenbackterminal.app;

import android.net.http.SslCertificate;
import android.net.http.X509TrustManagerExtensions;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Completes and verifies a server certificate chain the way Chrome does (fetching missing intermediates). */
final class Tls {
    private Tls() { }

    /** @return null when the chain is valid for host, otherwise the reason it isn't */
    static String verify(SslCertificate sc, String host) throws Exception {
        X509Certificate leaf = toX509(sc);
        if (leaf == null) return "no certificate";
        leaf.checkValidity();
        if (!matchesHost(leaf, host)) return "certificate is not for " + host;

        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);
        for (int i = 0; i < 4; i++) {
            X509Certificate cur = chain.get(chain.size() - 1);
            if (cur.getSubjectX500Principal().equals(cur.getIssuerX500Principal())) break; // reached a root
            String url = caIssuersUrl(cur);
            if (url == null) break;
            List<X509Certificate> got = fetchCerts(url);
            if (got.isEmpty()) break;
            X509Certificate issuer = null;
            for (X509Certificate c : got) if (c.getSubjectX500Principal().equals(cur.getIssuerX500Principal())) { issuer = c; break; }
            if (issuer == null) break;
            cur.verify(issuer.getPublicKey()); // the fetched certificate really signed this one
            chain.add(issuer);
        }

        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore) null); // the phone's authorities + the bundled up-to-date list (network_security_config)
        X509TrustManager tm = null;
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) { tm = (X509TrustManager) t; break; }
        if (tm == null) return "no trust manager";
        String auth = "EC".equals(leaf.getPublicKey().getAlgorithm()) ? "ECDHE_ECDSA" : "ECDHE_RSA";
        try {
            new X509TrustManagerExtensions(tm).checkServerTrusted(chain.toArray(new X509Certificate[0]), auth, host);
        } catch (Exception e) {
            return "untrusted (" + chain.size() + " certs, top: " + chain.get(chain.size() - 1).getIssuerX500Principal().getName() + ") " + e.getMessage();
        }
        return null;
    }

    private static X509Certificate toX509(SslCertificate sc) throws Exception {
        if (sc == null) return null;
        if (Build.VERSION.SDK_INT >= 29) return sc.getX509Certificate();
        Bundle b = SslCertificate.saveState(sc);
        byte[] der = b == null ? null : b.getByteArray("x509-certificate");
        if (der == null) return null;
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean matchesHost(X509Certificate c, String host) throws Exception {
        Collection<List<?>> sans = c.getSubjectAlternativeNames();
        if (sans == null) return false;
        String h = host.toLowerCase();
        for (List<?> e : sans) {
            if (e.size() < 2 || !Integer.valueOf(2).equals(e.get(0))) continue; // dNSName
            String n = String.valueOf(e.get(1)).toLowerCase();
            if (n.equals(h)) return true;
            if (n.startsWith("*.") && h.endsWith(n.substring(1))) {
                String label = h.substring(0, h.length() - n.length() + 1);
                if (!label.isEmpty() && label.indexOf('.') < 0) return true;
            }
        }
        return false;
    }

    /** "CA Issuers" address from the Authority Information Access extension */
    private static String caIssuersUrl(X509Certificate c) {
        byte[] ext = c.getExtensionValue("1.3.6.1.5.5.7.1.1");
        if (ext == null) return null;
        byte[] oid = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02}; // id-ad-caIssuers
        for (int i = 0; i + oid.length + 2 < ext.length; i++) {
            boolean m = true;
            for (int j = 0; j < oid.length; j++) if (ext[i + j] != oid[j]) { m = false; break; }
            if (!m) continue;
            int p = i + oid.length;
            if ((ext[p] & 0xFF) != 0x86) continue; // uniformResourceIdentifier
            int len = ext[p + 1] & 0xFF;
            int start = p + 2;
            if (len > 0x80) { int nb = len & 0x7F; len = 0; for (int k = 0; k < nb; k++) len = (len << 8) | (ext[start + k] & 0xFF); start += nb; }
            if (start + len > ext.length) return null;
            return new String(ext, start, len, StandardCharsets.US_ASCII);
        }
        return null;
    }

    /** Certificate files are published over plain http (they are signed, so that is safe); a tiny GET without the cleartext block. */
    private static List<X509Certificate> fetchCerts(String url) throws Exception {
        for (int hop = 0; hop < 3 && url != null; hop++) {
            URI u = URI.create(url);
            boolean tls = "https".equalsIgnoreCase(u.getScheme());
            byte[] body;
            String location = null;
            if (tls) {
                javax.net.ssl.HttpsURLConnection con = (javax.net.ssl.HttpsURLConnection) new java.net.URL(url).openConnection();
                con.setConnectTimeout(8000); con.setReadTimeout(8000);
                try (InputStream in = con.getInputStream()) { body = readAll(in); }
            } else {
                int port = u.getPort() > 0 ? u.getPort() : 80;
                String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
                if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(u.getHost(), port), 8000);
                    s.setSoTimeout(8000);
                    OutputStream o = s.getOutputStream();
                    o.write(("GET " + path + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\nUser-Agent: GreenbackApp\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    o.flush();
                    byte[] all = readAll(s.getInputStream());
                    int sep = indexOf(all, new byte[]{13, 10, 13, 10});
                    if (sep < 0) return new ArrayList<>();
                    String head = new String(all, 0, sep, StandardCharsets.ISO_8859_1);
                    String status = head.split("\r\n", 2)[0];
                    if (status.matches("HTTP/\\S+ 30[1278].*")) {
                        for (String line : head.split("\r\n")) if (line.toLowerCase().startsWith("location:")) location = line.substring(9).trim();
                        url = location == null ? null : u.resolve(location).toString();
                        continue;
                    }
                    if (!status.matches("HTTP/\\S+ 200.*")) return new ArrayList<>();
                    body = new byte[all.length - sep - 4];
                    System.arraycopy(all, sep + 4, body, 0, body.length);
                }
            }
            List<X509Certificate> out = new ArrayList<>();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try {
                for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(body))) out.add((X509Certificate) c);
            } catch (Exception e) {
                out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(body)));
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); total += n; if (total > 1 << 20) break; }
        return b.toByteArray();
    }

    private static int indexOf(byte[] a, byte[] p) {
        outer:
        for (int i = 0; i + p.length <= a.length; i++) {
            for (int j = 0; j < p.length; j++) if (a[i + j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }
}

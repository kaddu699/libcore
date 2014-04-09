/*
 * Copyright (C) 2010 The Android Open Source Project
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

package libcore.javax.net.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;
import junit.framework.TestCase;
import libcore.java.security.StandardNames;
import libcore.java.security.TestKeyStore;

public class SSLEngineTest extends TestCase {

    public void assertConnected(TestSSLEnginePair e) {
        assertConnected(e.client, e.server);
    }

    public void assertNotConnected(TestSSLEnginePair e) {
        assertNotConnected(e.client, e.server);
    }

    public void assertConnected(SSLEngine a, SSLEngine b) {
        assertTrue(connected(a, b));
    }

    public void assertNotConnected(SSLEngine a, SSLEngine b) {
        assertFalse(connected(a, b));
    }

    public boolean connected(SSLEngine a, SSLEngine b) {
        return (a.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
                && b.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
                && a.getSession() != null
                && b.getSession() != null
                && !a.isInboundDone()
                && !b.isInboundDone()
                && !a.isOutboundDone()
                && !b.isOutboundDone());
    }

    public void test_SSLEngine_defaultConfiguration() throws Exception {
        SSLDefaultConfigurationAsserts.assertSSLEngine(
                TestSSLContext.create().clientContext.createSSLEngine());
    }

    public void test_SSLEngine_getSupportedCipherSuites_returnsCopies() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        assertNotSame(e.getSupportedCipherSuites(), e.getSupportedCipherSuites());
        c.close();
    }

    public void test_SSLEngine_getSupportedCipherSuites_connect() throws Exception {
        // note the rare usage of non-RSA keys
        TestKeyStore testKeyStore = new TestKeyStore.Builder()
                .keyAlgorithms("RSA", "DSA", "EC", "EC_RSA")
                .aliasPrefix("rsa-dsa-ec")
                .ca(true)
                .build();
        test_SSLEngine_getSupportedCipherSuites_connect(testKeyStore, false);
        test_SSLEngine_getSupportedCipherSuites_connect(testKeyStore, true);
    }
    private void test_SSLEngine_getSupportedCipherSuites_connect(TestKeyStore testKeyStore,
                                                                 boolean secureRenegotiation)
            throws Exception {
        TestSSLContext c = TestSSLContext.create(testKeyStore, testKeyStore);

        // Create a TestSSLContext where the KeyManager returns wrong (randomly generated) private
        // keys, matching the algorithm and parameters of the correct keys.
        // I couldn't find a more elegant way to achieve this other than temporarily replacing the
        // first X509ExtendedKeyManager element of TestKeyStore.keyManagers while invoking
        // TestSSLContext.create.
        TestSSLContext cWithWrongPrivateKeys;
        {
            // Create a RandomPrivateKeyX509ExtendedKeyManager based on the first
            // X509ExtendedKeyManager in c.serverKeyManagers.
            KeyManager randomPrivateKeyX509ExtendedKeyManager = null;
            for (KeyManager keyManager : c.serverKeyManagers) {
              if (keyManager instanceof X509ExtendedKeyManager) {
                randomPrivateKeyX509ExtendedKeyManager =
                    new RandomPrivateKeyX509ExtendedKeyManager((X509ExtendedKeyManager) keyManager);
                break;
              }
            }
            if (randomPrivateKeyX509ExtendedKeyManager == null) {
              fail("No X509ExtendedKeyManager in c.serverKeyManagers");
            }

            // Find the first X509ExtendedKeyManager in testKeyStore.keyManagers
            int replaceIndex = -1;
            for (int i = 0; i < testKeyStore.keyManagers.length; i++) {
              KeyManager keyManager = testKeyStore.keyManagers[i];
              if (keyManager instanceof X509ExtendedKeyManager) {
                replaceIndex = i;
                break;
              }
            }
            if (replaceIndex == -1) {
              fail("No X509ExtendedKeyManager in testKeyStore.keyManagers");
            }

            // Temporarily substitute the RandomPrivateKeyX509ExtendedKeyManager in place of the
            // original X509ExtendedKeyManager.
            KeyManager originalKeyManager = testKeyStore.keyManagers[replaceIndex];
            testKeyStore.keyManagers[replaceIndex] = randomPrivateKeyX509ExtendedKeyManager;
            cWithWrongPrivateKeys = TestSSLContext.create(testKeyStore, testKeyStore);
            testKeyStore.keyManagers[replaceIndex] = originalKeyManager;
        }

        // To catch all the errors.
        StringBuilder error = new StringBuilder();

        String[] cipherSuites = c.clientContext.createSSLEngine().getSupportedCipherSuites();
        for (String cipherSuite : cipherSuites) {
            try {
            // Skip cipher suites that are obsoleted.
            if (StandardNames.IS_RI && "TLSv1.2".equals(c.clientContext.getProtocol())
                    && StandardNames.CIPHER_SUITES_OBSOLETE_TLS12.contains(cipherSuite)) {
                continue;
            }
            /*
             * TLS_EMPTY_RENEGOTIATION_INFO_SCSV cannot be used on
             * its own, but instead in conjunction with other
             * cipher suites.
             */
            if (cipherSuite.equals(StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION)) {
                continue;
            }
            /*
             * Kerberos cipher suites require external setup. See "Kerberos Requirements" in
             * https://java.sun.com/j2se/1.5.0/docs/guide/security/jsse/JSSERefGuide.html
             * #KRBRequire
             */
            if (cipherSuite.startsWith("TLS_KRB5_")) {
                continue;
            }

            final String[] cipherSuiteArray
                    = (secureRenegotiation
                       ? new String[] { cipherSuite,
                                        StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION }
                       : new String[] { cipherSuite });

            // Check that handshake succeeds.
            TestSSLEnginePair pair = TestSSLEnginePair.create(c, new TestSSLEnginePair.Hooks() {
                @Override
                void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                    client.setEnabledCipherSuites(cipherSuiteArray);
                    server.setEnabledCipherSuites(cipherSuiteArray);
                }
            });
            assertConnected(pair);

            boolean needsRecordSplit =
                    ("TLS".equalsIgnoreCase(c.clientContext.getProtocol())
                            || "SSLv3".equalsIgnoreCase(c.clientContext.getProtocol()))
                    && cipherSuite.contains("_CBC_");

            assertSendsCorrectly("This is the client. Hello!".getBytes(),
                    pair.client, pair.server, needsRecordSplit);
            assertSendsCorrectly("This is the server. Hi!".getBytes(),
                    pair.server, pair.client, needsRecordSplit);

            // Check that handshake fails when the server does not possess the private key
            // corresponding to the server's certificate. This is achieved by using SSLContext
            // cWithWrongPrivateKeys whose KeyManager returns wrong private keys that match
            // the algorithm (and parameters) of the correct keys.
            if (!cipherSuite.contains("_anon_")) {
                // The identity of the server is verified only in non-anonymous key exchanges.
                try {
                    TestSSLEnginePair p = TestSSLEnginePair.create(
                            cWithWrongPrivateKeys, new TestSSLEnginePair.Hooks() {
                        @Override
                                void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                            client.setEnabledCipherSuites(cipherSuiteArray);
                            server.setEnabledCipherSuites(cipherSuiteArray);
                        }
                    });
                    assertNotConnected(p);
                } catch (IOException expected) {}
            }
            } catch (Exception e) {
                String message = ("Problem trying to connect cipher suite " + cipherSuite);
                System.out.println(message);
                e.printStackTrace();
                error.append(message);
                error.append('\n');
            }
        }
        c.close();
        
        if (error.length() > 0) {
            throw new Exception("One or more problems in "
                    + "test_SSLEngine_getSupportedCipherSuites_connect:\n" + error);
        }
    }

    private static void assertSendsCorrectly(final byte[] sourceBytes, SSLEngine source,
            SSLEngine dest, boolean needsRecordSplit) throws SSLException {
        ByteBuffer sourceOut = ByteBuffer.wrap(sourceBytes);
        SSLSession sourceSession = source.getSession();
        ByteBuffer sourceToDest = ByteBuffer.allocate(sourceSession.getPacketBufferSize());
        SSLEngineResult sourceOutRes = source.wrap(sourceOut, sourceToDest);
        sourceToDest.flip();

        String sourceCipherSuite = source.getSession().getCipherSuite();
        assertEquals(sourceCipherSuite, sourceBytes.length, sourceOutRes.bytesConsumed());
        assertEquals(sourceCipherSuite, HandshakeStatus.NOT_HANDSHAKING,
                sourceOutRes.getHandshakeStatus());

        SSLSession destSession = dest.getSession();
        ByteBuffer destIn = ByteBuffer.allocate(destSession.getApplicationBufferSize());

        int numUnwrapCalls = 0;
        while (destIn.position() != sourceOut.limit()) {
            SSLEngineResult destRes = dest.unwrap(sourceToDest, destIn);
            assertEquals(sourceCipherSuite, HandshakeStatus.NOT_HANDSHAKING,
                    destRes.getHandshakeStatus());
            if (needsRecordSplit && numUnwrapCalls == 0) {
                assertEquals(sourceCipherSuite, 1, destRes.bytesProduced());
            }
            numUnwrapCalls++;
        }

        destIn.flip();
        byte[] actual = new byte[destIn.remaining()];
        destIn.get(actual);
        assertEquals(sourceCipherSuite, Arrays.toString(sourceBytes), Arrays.toString(actual));

        if (needsRecordSplit) {
            assertEquals(sourceCipherSuite, 2, numUnwrapCalls);
        } else {
            assertEquals(sourceCipherSuite, 1, numUnwrapCalls);
        }
    }

    public void test_SSLEngine_getEnabledCipherSuites_returnsCopies() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        assertNotSame(e.getEnabledCipherSuites(), e.getEnabledCipherSuites());
        c.close();
    }

    public void test_SSLEngine_setEnabledCipherSuites() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();

        try {
            e.setEnabledCipherSuites(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            e.setEnabledCipherSuites(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            e.setEnabledCipherSuites(new String[] { "Bogus" } );
            fail();
        } catch (IllegalArgumentException expected) {
        }

        e.setEnabledCipherSuites(new String[0]);
        e.setEnabledCipherSuites(e.getEnabledCipherSuites());
        e.setEnabledCipherSuites(e.getSupportedCipherSuites());

        // Check that setEnabledCipherSuites affects getEnabledCipherSuites
        String[] cipherSuites = new String[] { e.getSupportedCipherSuites()[0] };
        e.setEnabledCipherSuites(cipherSuites);
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(e.getEnabledCipherSuites()));

        c.close();
    }

    public void test_SSLEngine_getSupportedProtocols_returnsCopies() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        assertNotSame(e.getSupportedProtocols(), e.getSupportedProtocols());
        c.close();
    }

    public void test_SSLEngine_getEnabledProtocols_returnsCopies() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        assertNotSame(e.getEnabledProtocols(), e.getEnabledProtocols());
        c.close();
    }

    public void test_SSLEngine_setEnabledProtocols() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();

        try {
            e.setEnabledProtocols(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            e.setEnabledProtocols(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            e.setEnabledProtocols(new String[] { "Bogus" } );
            fail();
        } catch (IllegalArgumentException expected) {
        }
        e.setEnabledProtocols(new String[0]);
        e.setEnabledProtocols(e.getEnabledProtocols());
        e.setEnabledProtocols(e.getSupportedProtocols());

        // Check that setEnabledProtocols affects getEnabledProtocols
        for (String protocol : e.getSupportedProtocols()) {
            if ("SSLv2Hello".equals(protocol)) {
                try {
                    e.setEnabledProtocols(new String[] { protocol });
                    fail("Should fail when SSLv2Hello is set by itself");
                } catch (IllegalArgumentException expected) {}
            } else {
                String[] protocols = new String[] { protocol };
                e.setEnabledProtocols(protocols);
                assertEquals(Arrays.deepToString(protocols),
                        Arrays.deepToString(e.getEnabledProtocols()));
            }
        }

        c.close();
    }

    public void test_SSLEngine_getSession() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        SSLSession session = e.getSession();
        assertNotNull(session);
        assertFalse(session.isValid());
        c.close();
    }

    public void test_SSLEngine_beginHandshake() throws Exception {
        TestSSLContext c = TestSSLContext.create();

        try {
            c.clientContext.createSSLEngine().beginHandshake();
            fail();
        } catch (IllegalStateException expected) {
        }

        assertConnected(TestSSLEnginePair.create(null));

        c.close();
    }

    public void test_SSLEngine_beginHandshake_noKeyStore() throws Exception {
        TestSSLContext c = TestSSLContext.create(null, null, null, null, null, null, null, null,
                                                 SSLContext.getDefault(), SSLContext.getDefault());
        try {
            // TODO Fix KnownFailure AlertException "NO SERVER CERTIFICATE FOUND"
            // ServerHandshakeImpl.selectSuite should not select a suite without a required cert
            TestSSLEnginePair.connect(c, null);
            fail();
        } catch (SSLHandshakeException expected) {
        }
        c.close();
    }

    public void test_SSLEngine_beginHandshake_noClientCertificate() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine[] engines = TestSSLEnginePair.connect(c, null);
        assertConnected(engines[0], engines[1]);
        c.close();
    }

    public void test_SSLEngine_getUseClientMode() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        assertFalse(c.clientContext.createSSLEngine().getUseClientMode());
        assertFalse(c.clientContext.createSSLEngine(null, -1).getUseClientMode());
        c.close();
    }

    public void test_SSLEngine_setUseClientMode() throws Exception {
        boolean[] finished;

        // client is client, server is server
        finished = new boolean[2];
        assertConnected(test_SSLEngine_setUseClientMode(true, false, finished));
        assertTrue(finished[0]);
        assertTrue(finished[1]);

        // client is server, server is client
        finished = new boolean[2];
        assertConnected(test_SSLEngine_setUseClientMode(false, true, finished));
        assertTrue(finished[0]);
        assertTrue(finished[1]);

        // both are client
        /*
         * Our implementation throws an SSLHandshakeException, but RI just
         * stalls forever
         */
        try {
            assertNotConnected(test_SSLEngine_setUseClientMode(true, true, null));
            assertTrue(StandardNames.IS_RI);
        } catch (SSLHandshakeException maybeExpected) {
            assertFalse(StandardNames.IS_RI);
        }

        // both are server
        assertNotConnected(test_SSLEngine_setUseClientMode(false, false, null));
    }

    public void test_SSLEngine_setUseClientMode_afterHandshake() throws Exception {

        // can't set after handshake
        TestSSLEnginePair pair = TestSSLEnginePair.create(null);
        try {
            pair.server.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            pair.client.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    private TestSSLEnginePair test_SSLEngine_setUseClientMode(final boolean clientClientMode,
                                                              final boolean serverClientMode,
                                                              final boolean[] finished)
            throws Exception {
        TestSSLContext c;
        if (!clientClientMode && serverClientMode) {
            c = TestSSLContext.create(TestKeyStore.getServer(), TestKeyStore.getClient());
        } else {
            c = TestSSLContext.create();
        }

        return TestSSLEnginePair.create(c, new TestSSLEnginePair.Hooks() {
            @Override
            void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                client.setUseClientMode(clientClientMode);
                server.setUseClientMode(serverClientMode);
            }
        }, finished);
    }

    public void test_SSLEngine_clientAuth() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();

        assertFalse(e.getWantClientAuth());
        assertFalse(e.getNeedClientAuth());

        // confirm turning one on by itself
        e.setWantClientAuth(true);
        assertTrue(e.getWantClientAuth());
        assertFalse(e.getNeedClientAuth());

        // confirm turning setting on toggles the other
        e.setNeedClientAuth(true);
        assertFalse(e.getWantClientAuth());
        assertTrue(e.getNeedClientAuth());

        // confirm toggling back
        e.setWantClientAuth(true);
        assertTrue(e.getWantClientAuth());
        assertFalse(e.getNeedClientAuth());

        // TODO Fix KnownFailure "init - invalid private key"
        TestSSLContext clientAuthContext
                = TestSSLContext.create(TestKeyStore.getClientCertificate(),
                                        TestKeyStore.getServer());
        TestSSLEnginePair p = TestSSLEnginePair.create(clientAuthContext,
                                                       new TestSSLEnginePair.Hooks() {
            @Override
                    void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                server.setWantClientAuth(true);
            }
        });
        assertConnected(p);
        assertNotNull(p.client.getSession().getLocalCertificates());
        TestKeyStore.assertChainLength(p.client.getSession().getLocalCertificates());
        TestSSLContext.assertClientCertificateChain(clientAuthContext.clientTrustManager,
                                                    p.client.getSession().getLocalCertificates());
        clientAuthContext.close();
        c.close();
    }

   /**
    * http://code.google.com/p/android/issues/detail?id=31903
    * This test case directly tests the fix for the issue.
    */
    public void test_SSLEngine_clientAuthWantedNoClientCert() throws Exception {
        TestSSLContext clientAuthContext
                = TestSSLContext.create(TestKeyStore.getClient(),
                                        TestKeyStore.getServer());
        TestSSLEnginePair p = TestSSLEnginePair.create(clientAuthContext,
                                                       new TestSSLEnginePair.Hooks() {
            @Override
            void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                server.setWantClientAuth(true);
            }
        });
        assertConnected(p);
        clientAuthContext.close();
    }

   /**
    * http://code.google.com/p/android/issues/detail?id=31903
    * This test case verifies that if the server requires a client cert
    * (setNeedClientAuth) but the client does not provide one SSL connection
    * establishment will fail
    */
    public void test_SSLEngine_clientAuthNeededNoClientCert() throws Exception {
        boolean handshakeExceptionCaught = false;
        TestSSLContext clientAuthContext
                = TestSSLContext.create(TestKeyStore.getClient(),
                                        TestKeyStore.getServer());
        try {
            TestSSLEnginePair.create(clientAuthContext,
                             new TestSSLEnginePair.Hooks() {
                @Override
                void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                    server.setNeedClientAuth(true);
                }
            });
            fail();
        } catch (SSLHandshakeException expected) {
        } finally {
            clientAuthContext.close();
        }
    }

    public void test_SSLEngine_endpointVerification_Success() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        TestSSLEnginePair p = TestSSLEnginePair.create(c, new TestSSLEnginePair.Hooks() {
            @Override
            void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                SSLParameters p = client.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                client.setSSLParameters(p);
            }
        });
        assertConnected(p);
        c.close();
    }

    public void test_SSLEngine_getEnableSessionCreation() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        assertTrue(e.getEnableSessionCreation());
        c.close();
    }

    public void test_SSLEngine_setEnableSessionCreation_server() throws Exception {
        TestSSLEnginePair p = TestSSLEnginePair.create(new TestSSLEnginePair.Hooks() {
            @Override
            void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                server.setEnableSessionCreation(false);
            }
        });
        assertNotConnected(p);
    }

    public void test_SSLEngine_setEnableSessionCreation_client() throws Exception {
        try {
            TestSSLEnginePair.create(new TestSSLEnginePair.Hooks() {
                @Override
                void beforeBeginHandshake(SSLEngine client, SSLEngine server) {
                    client.setEnableSessionCreation(false);
                }
            });
            fail();
        } catch (SSLException expected) {
        }
    }

    public void test_SSLEngine_getSSLParameters() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();

        SSLParameters p = e.getSSLParameters();
        assertNotNull(p);

        String[] cipherSuites = p.getCipherSuites();
        assertNotSame(cipherSuites, e.getEnabledCipherSuites());
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(e.getEnabledCipherSuites()));

        String[] protocols = p.getProtocols();
        assertNotSame(protocols, e.getEnabledProtocols());
        assertEquals(Arrays.asList(protocols), Arrays.asList(e.getEnabledProtocols()));

        assertEquals(p.getWantClientAuth(), e.getWantClientAuth());
        assertEquals(p.getNeedClientAuth(), e.getNeedClientAuth());

        c.close();
    }

    public void test_SSLEngine_setSSLParameters() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLEngine e = c.clientContext.createSSLEngine();
        String[] defaultCipherSuites = e.getEnabledCipherSuites();
        String[] defaultProtocols = e.getEnabledProtocols();
        String[] supportedCipherSuites = e.getSupportedCipherSuites();
        String[] supportedProtocols = e.getSupportedProtocols();

        {
            SSLParameters p = new SSLParameters();
            e.setSSLParameters(p);
            assertEquals(Arrays.asList(defaultCipherSuites),
                         Arrays.asList(e.getEnabledCipherSuites()));
            assertEquals(Arrays.asList(defaultProtocols),
                         Arrays.asList(e.getEnabledProtocols()));
        }

        {
            SSLParameters p = new SSLParameters(supportedCipherSuites,
                                                supportedProtocols);
            e.setSSLParameters(p);
            assertEquals(Arrays.asList(supportedCipherSuites),
                         Arrays.asList(e.getEnabledCipherSuites()));
            assertEquals(Arrays.asList(supportedProtocols),
                         Arrays.asList(e.getEnabledProtocols()));
        }
        {
            SSLParameters p = new SSLParameters();

            p.setNeedClientAuth(true);
            assertFalse(e.getNeedClientAuth());
            assertFalse(e.getWantClientAuth());
            e.setSSLParameters(p);
            assertTrue(e.getNeedClientAuth());
            assertFalse(e.getWantClientAuth());

            p.setWantClientAuth(true);
            assertTrue(e.getNeedClientAuth());
            assertFalse(e.getWantClientAuth());
            e.setSSLParameters(p);
            assertFalse(e.getNeedClientAuth());
            assertTrue(e.getWantClientAuth());

            p.setWantClientAuth(false);
            assertFalse(e.getNeedClientAuth());
            assertTrue(e.getWantClientAuth());
            e.setSSLParameters(p);
            assertFalse(e.getNeedClientAuth());
            assertFalse(e.getWantClientAuth());
        }
        c.close();
    }

    public void test_TestSSLEnginePair_create() throws Exception {
        TestSSLEnginePair test = TestSSLEnginePair.create(null);
        assertNotNull(test.c);
        assertNotNull(test.server);
        assertNotNull(test.client);
        assertConnected(test);
    }
}

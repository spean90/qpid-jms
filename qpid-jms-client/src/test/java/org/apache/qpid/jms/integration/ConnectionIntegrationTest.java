/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.jms.integration;

import static org.hamcrest.Matchers.arrayContaining;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Connection;
import javax.jms.ConnectionMetaData;
import javax.jms.ExceptionListener;
import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.provider.ProviderRedirectedException;
import org.apache.qpid.jms.test.QpidJmsTestCase;
import org.apache.qpid.jms.test.Wait;
import org.apache.qpid.jms.test.testpeer.TestAmqpPeer;
import org.apache.qpid.jms.test.testpeer.basictypes.AmqpError;
import org.apache.qpid.jms.test.testpeer.basictypes.ConnectionError;
import org.apache.qpid.jms.test.testpeer.matchers.CoordinatorMatcher;
import org.apache.qpid.proton.amqp.transaction.TxnCapability;
import org.junit.Test;

// TODO find a way to make the test abort immediately if the TestAmqpPeer throws an exception
public class ConnectionIntegrationTest extends QpidJmsTestCase {
    private final IntegrationTestFixture testFixture = new IntegrationTestFixture();

    @Test(timeout = 5000)
    public void testCreateAndCloseConnection() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 5000)
    public void testCreateConnectionWithClientId() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer, false, null, null, null, true);
            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 5000)
    public void testCreateAutoAckSession() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            testPeer.expectBegin(true);
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            assertNotNull("Session should not be null", session);
        }
    }

    @Test(timeout = 5000)
    public void testCreateTransactedSession() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);

            testPeer.expectBegin(true);
            // Expect the session, with an immediate link to the transaction coordinator
            // using a target with the expected capabilities only.
            CoordinatorMatcher txCoordinatorMatcher = new CoordinatorMatcher();
            txCoordinatorMatcher.withCapabilities(arrayContaining(TxnCapability.LOCAL_TXN));
            testPeer.expectSenderAttach(txCoordinatorMatcher, false, false);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            assertNotNull("Session should not be null", session);
        }
    }

    @Test(timeout = 5000)
    public void testConnectionMetaDataVersion() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            ConnectionMetaData meta = connection.getMetaData();
            int result = meta.getProviderMajorVersion() + meta.getProviderMinorVersion();
            assertTrue("Expected non-zero provider major / minor version", result != 0);

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 10000)
    public void testRemotelyEndConnectionListenerInvoked() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            final CountDownLatch done = new CountDownLatch(1);

            // Don't set a ClientId, so that the underlying AMQP connection isn't established yet
            Connection connection = testFixture.establishConnecton(testPeer, false, null, null, null, false);

            // Tell the test peer to close the connection when executing its last handler
            testPeer.remotelyCloseConnection(true);

            // Add the exception listener
            connection.setExceptionListener(new ExceptionListener() {

                @Override
                public void onException(JMSException exception) {
                    done.countDown();
                }
            });

            // Trigger the underlying AMQP connection
            connection.start();

            assertTrue("Connection should report failure", done.await(5, TimeUnit.SECONDS));

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 10000)
    public void testRemotelyEndConnectionWithRedirect() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<JMSException> asyncError = new AtomicReference<JMSException>();

            final String REDIRECTED_HOSTNAME = "vhost";
            final String REDIRECTED_NETWORK_HOST = "localhost";
            final int REDIRECTED_PORT = 5677;

            // Don't set a ClientId, so that the underlying AMQP connection isn't established yet
            Connection connection = testFixture.establishConnecton(testPeer, false, null, null, null, false);

            // Tell the test peer to close the connection when executing its last handler
            Map<String, Object> errorInfo = new HashMap<String, Object>();
            errorInfo.put("hostname", REDIRECTED_HOSTNAME);
            errorInfo.put("network-host", REDIRECTED_NETWORK_HOST);
            errorInfo.put("port", 5677);

            testPeer.remotelyCloseConnection(true, ConnectionError.REDIRECT, "Connection redirected", errorInfo);

            // Add the exception listener
            connection.setExceptionListener(new ExceptionListener() {

                @Override
                public void onException(JMSException exception) {
                    asyncError.set(exception);
                    done.countDown();
                }
            });

            // Trigger the underlying AMQP connection
            connection.start();

            assertTrue("Connection should report failure", done.await(5, TimeUnit.SECONDS));

            assertTrue(asyncError.get() instanceof JMSException);
            assertTrue(asyncError.get().getCause() instanceof ProviderRedirectedException);

            ProviderRedirectedException redirect = (ProviderRedirectedException) asyncError.get().getCause();
            assertEquals(REDIRECTED_HOSTNAME, redirect.getHostname());
            assertEquals(REDIRECTED_NETWORK_HOST, redirect.getNetworkHost());
            assertEquals(REDIRECTED_PORT, redirect.getPort());

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 5000)
    public void testRemotelyEndConnectionWithSessionWithConsumer() throws Exception {
        final String BREAD_CRUMB = "ErrorMessage";

        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            final Connection connection = testFixture.establishConnecton(testPeer);

            testPeer.expectBegin(true);
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Create a consumer, then remotely end the connection afterwards.
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlow();
            testPeer.remotelyCloseConnection(true, AmqpError.RESOURCE_LIMIT_EXCEEDED, BREAD_CRUMB);

            Queue queue = session.createQueue("myQueue");
            MessageConsumer consumer = session.createConsumer(queue);

            testPeer.waitForAllHandlersToComplete(1000);
            assertTrue("connection never closed.", Wait.waitFor(new Wait.Condition() {
                @Override
                public boolean isSatisified() throws Exception {
                    return !((JmsConnection) connection).isConnected();
                }
            }, 1000, 10));

            try {
                connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                fail("Expected ISE to be thrown due to being closed");
            } catch (IllegalStateException jmsise) {
                String message = jmsise.getCause().getMessage();
                assertTrue(message.contains(AmqpError.RESOURCE_LIMIT_EXCEEDED.toString()));
                assertTrue(message.contains(BREAD_CRUMB));
            }

            // Verify the session is now marked closed
            try {
                session.getAcknowledgeMode();
                fail("Expected ISE to be thrown due to being closed");
            } catch (IllegalStateException jmsise) {
                String message = jmsise.getCause().getMessage();
                assertTrue(message.contains(AmqpError.RESOURCE_LIMIT_EXCEEDED.toString()));
                assertTrue(message.contains(BREAD_CRUMB));
            }

            // Verify the consumer is now marked closed
            try {
                consumer.getMessageListener();
                fail("Expected ISE to be thrown due to being closed");
            } catch (IllegalStateException jmsise) {
                String message = jmsise.getCause().getMessage();
                assertTrue(message.contains(AmqpError.RESOURCE_LIMIT_EXCEEDED.toString()));
                assertTrue(message.contains(BREAD_CRUMB));
            }

            // Try closing them explicitly, should effectively no-op in client.
            // The test peer will throw during close if it sends anything.
            consumer.close();
            session.close();
        }
    }
}

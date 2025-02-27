/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.rest.action;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.admin.cluster.node.tasks.cancel.CancelTasksAction;
import org.opensearch.action.admin.cluster.node.tasks.cancel.CancelTasksRequest;
import org.opensearch.action.search.SearchAction;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.PlainListenableActionFuture;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.http.HttpChannel;
import org.opensearch.http.HttpResponse;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.node.NodeClient;
import org.junit.After;
import org.junit.Before;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class RestCancellableNodeClientTests extends OpenSearchTestCase {

    private ThreadPool threadPool;

    @Before
    public void createThreadPool() {
        threadPool = new TestThreadPool(RestCancellableNodeClientTests.class.getName());
    }

    @After
    public void stopThreadPool() {
        ThreadPool.terminate(threadPool, 5, TimeUnit.SECONDS);
    }

    /**
     * This test verifies that no tasks are left in the map where channels and their corresponding tasks are tracked.
     * Through the {@link TestClient} we simulate a scenario where the task may complete even before it has been
     * associated with its corresponding channel. Either way, we need to make sure that no tasks are left in the map.
     */
    public void testCompletedTasks() throws Exception {
        try (TestClient testClient = new TestClient(Settings.EMPTY, threadPool, false)) {
            int initialHttpChannels = RestCancellableNodeClient.getNumChannels();
            int totalSearches = 0;
            List<Future<?>> futures = new ArrayList<>();
            int numChannels = randomIntBetween(1, 30);
            for (int i = 0; i < numChannels; i++) {
                int numTasks = randomIntBetween(1, 30);
                TestHttpChannel channel = new TestHttpChannel();
                totalSearches += numTasks;
                for (int j = 0; j < numTasks; j++) {
                    PlainListenableActionFuture<SearchResponse> actionFuture = PlainListenableActionFuture.newListenableFuture();
                    RestCancellableNodeClient client = new RestCancellableNodeClient(testClient, channel);
                    threadPool.generic().submit(() -> client.execute(SearchAction.INSTANCE, new SearchRequest(), actionFuture));
                    futures.add(actionFuture);
                }
            }
            for (Future<?> future : futures) {
                future.get();
            }
            // no channels get closed in this test, hence we expect as many channels as we created in the map
            assertEquals(initialHttpChannels + numChannels, RestCancellableNodeClient.getNumChannels());
            assertEquals(0, RestCancellableNodeClient.getNumTasks());
            assertEquals(totalSearches, testClient.searchRequests.get());
        }
    }

    /**
     * This test verifies the behaviour when the channel gets closed. The channel is expected to be
     * removed and all of its corresponding tasks get cancelled.
     */
    public void testCancelledTasks() throws Exception {
        try (TestClient nodeClient = new TestClient(Settings.EMPTY, threadPool, true)) {
            int initialHttpChannels = RestCancellableNodeClient.getNumChannels();
            int numChannels = randomIntBetween(1, 30);
            int totalSearches = 0;
            List<TestHttpChannel> channels = new ArrayList<>(numChannels);
            for (int i = 0; i < numChannels; i++) {
                TestHttpChannel channel = new TestHttpChannel();
                channels.add(channel);
                int numTasks = randomIntBetween(1, 30);
                totalSearches += numTasks;
                RestCancellableNodeClient client = new RestCancellableNodeClient(nodeClient, channel);
                for (int j = 0; j < numTasks; j++) {
                    client.execute(SearchAction.INSTANCE, new SearchRequest(), null);
                }
                assertEquals(numTasks, RestCancellableNodeClient.getNumTasks(channel));
            }
            assertEquals(initialHttpChannels + numChannels, RestCancellableNodeClient.getNumChannels());
            for (TestHttpChannel channel : channels) {
                channel.awaitClose();
            }
            assertEquals(initialHttpChannels, RestCancellableNodeClient.getNumChannels());
            assertEquals(totalSearches, nodeClient.searchRequests.get());
            assertEquals(totalSearches, nodeClient.cancelledTasks.size());
        }
    }

    /**
     * This test verified what happens when a request comes through yet its corresponding http channel is already closed.
     * The close listener is straight-away executed, the task is cancelled. This can even happen multiple times, it's the only case
     * where we may end up registering a close listener multiple times to the channel, but the channel is already closed hence only
     * the newly added listener will be invoked at registration time.
     */
    public void testChannelAlreadyClosed() {
        try (TestClient testClient = new TestClient(Settings.EMPTY, threadPool, true)) {
            int initialHttpChannels = RestCancellableNodeClient.getNumChannels();
            int numChannels = randomIntBetween(1, 30);
            int totalSearches = 0;
            for (int i = 0; i < numChannels; i++) {
                TestHttpChannel channel = new TestHttpChannel();
                // no need to wait here, there will be no close listener registered, nothing to wait for.
                channel.close();
                int numTasks = randomIntBetween(1, 5);
                totalSearches += numTasks;
                RestCancellableNodeClient client = new RestCancellableNodeClient(testClient, channel);
                for (int j = 0; j < numTasks; j++) {
                    // here the channel will be first registered, then straight-away removed from the map as the close listener is invoked
                    client.execute(SearchAction.INSTANCE, new SearchRequest(), null);
                }
            }
            assertEquals(initialHttpChannels, RestCancellableNodeClient.getNumChannels());
            assertEquals(totalSearches, testClient.searchRequests.get());
            assertEquals(totalSearches, testClient.cancelledTasks.size());
        }
    }

    private static class TestClient extends NodeClient {
        private final AtomicLong counter = new AtomicLong(0);
        private final Set<TaskId> cancelledTasks = new CopyOnWriteArraySet<>();
        private final AtomicInteger searchRequests = new AtomicInteger(0);
        private final boolean timeout;

        TestClient(Settings settings, ThreadPool threadPool, boolean timeout) {
            super(settings, threadPool);
            this.timeout = timeout;
        }

        @Override
        public <Request extends ActionRequest, Response extends ActionResponse> Task executeLocally(
            ActionType<Response> action,
            Request request,
            ActionListener<Response> listener
        ) {
            switch (action.name()) {
                case CancelTasksAction.NAME:
                    CancelTasksRequest cancelTasksRequest = (CancelTasksRequest) request;
                    assertTrue("tried to cancel the same task more than once", cancelledTasks.add(cancelTasksRequest.getTaskId()));
                    Task task = request.createTask(counter.getAndIncrement(), "cancel_task", action.name(), null, Collections.emptyMap());
                    if (randomBoolean()) {
                        listener.onResponse(null);
                    } else {
                        // test that cancel tasks is best effort, failure received are not propagated
                        listener.onFailure(new IllegalStateException());
                    }

                    return task;
                case SearchAction.NAME:
                    searchRequests.incrementAndGet();
                    Task searchTask = request.createTask(counter.getAndIncrement(), "search", action.name(), null, Collections.emptyMap());
                    if (timeout == false) {
                        if (rarely()) {
                            // make sure that search is sometimes also called from the same thread before the task is returned
                            listener.onResponse(null);
                        } else {
                            threadPool().generic().submit(() -> listener.onResponse(null));
                        }
                    }
                    return searchTask;
                default:
                    throw new UnsupportedOperationException();
            }

        }

        @Override
        public String getLocalNodeId() {
            return "node";
        }
    }

    private class TestHttpChannel implements HttpChannel {
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicReference<ActionListener<Void>> closeListener = new AtomicReference<>();
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        @Override
        public void sendResponse(HttpResponse response, ActionListener<Void> listener) {}

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false) == false) {
                throw new IllegalStateException("channel already closed!");
            }
            ActionListener<Void> listener = closeListener.get();
            if (listener != null) {
                boolean failure = randomBoolean();
                threadPool.generic().submit(() -> {
                    if (failure) {
                        listener.onFailure(new IllegalStateException());
                    } else {
                        listener.onResponse(null);
                    }
                    closeLatch.countDown();
                });
            }
        }

        private void awaitClose() throws InterruptedException {
            close();
            closeLatch.await();
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void addCloseListener(ActionListener<Void> listener) {
            // if the channel is already closed, the listener gets notified immediately, from the same thread.
            if (open.get() == false) {
                listener.onResponse(null);
            } else {
                if (closeListener.compareAndSet(null, listener) == false) {
                    throw new IllegalStateException("close listener already set, only one is allowed!");
                }
            }
        }
    }
}

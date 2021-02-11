/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.transforms;

import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.breaker.CircuitBreaker.Durability;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.BulkByScrollTask;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.script.ScriptException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileShardResults;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.common.notifications.Level;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.elasticsearch.xpack.core.indexing.IterationResult;
import org.elasticsearch.xpack.core.transform.transforms.QueryConfigTests;
import org.elasticsearch.xpack.core.transform.transforms.SettingsConfig;
import org.elasticsearch.xpack.core.transform.transforms.SourceConfig;
import org.elasticsearch.xpack.core.transform.transforms.SyncConfig;
import org.elasticsearch.xpack.core.transform.transforms.TimeSyncConfig;
import org.elasticsearch.xpack.core.transform.transforms.TransformCheckpoint;
import org.elasticsearch.xpack.core.transform.transforms.TransformConfig;
import org.elasticsearch.xpack.core.transform.transforms.TransformIndexerPosition;
import org.elasticsearch.xpack.core.transform.transforms.TransformIndexerStats;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskState;
import org.elasticsearch.xpack.core.transform.transforms.latest.LatestConfig;
import org.elasticsearch.xpack.transform.Transform;
import org.elasticsearch.xpack.transform.checkpoint.CheckpointProvider;
import org.elasticsearch.xpack.transform.notifications.MockTransformAuditor;
import org.elasticsearch.xpack.transform.notifications.TransformAuditor;
import org.elasticsearch.xpack.transform.persistence.IndexBasedTransformConfigManager;
import org.junit.After;
import org.junit.Before;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.xpack.core.transform.transforms.DestConfigTests.randomDestConfig;
import static org.elasticsearch.xpack.core.transform.transforms.SourceConfigTests.randomSourceConfig;
import static org.elasticsearch.xpack.core.transform.transforms.pivot.PivotConfigTests.randomPivotConfig;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesRegex;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TransformIndexerFailureHandlingTests extends ESTestCase {

    private Client client;
    private ThreadPool threadPool;

    class MockedTransformIndexer extends TransformIndexer {

        private final Function<SearchRequest, SearchResponse> searchFunction;
        private final Function<BulkRequest, BulkResponse> bulkFunction;
        private final Consumer<String> failureConsumer;

        // used for synchronizing with the test
        private CountDownLatch latch;

        MockedTransformIndexer(
            ThreadPool threadPool,
            String executorName,
            IndexBasedTransformConfigManager transformsConfigManager,
            CheckpointProvider checkpointProvider,
            TransformConfig transformConfig,
            Map<String, String> fieldMappings,
            TransformAuditor auditor,
            AtomicReference<IndexerState> initialState,
            TransformIndexerPosition initialPosition,
            TransformIndexerStats jobStats,
            TransformContext context,
            Function<SearchRequest, SearchResponse> searchFunction,
            Function<BulkRequest, BulkResponse> bulkFunction,
            Consumer<String> failureConsumer
        ) {
            super(
                threadPool,
                transformsConfigManager,
                checkpointProvider,
                auditor,
                transformConfig,
                fieldMappings,
                initialState,
                initialPosition,
                jobStats,
                /* TransformProgress */ null,
                TransformCheckpoint.EMPTY,
                TransformCheckpoint.EMPTY,
                context
            );
            this.searchFunction = searchFunction;
            this.bulkFunction = bulkFunction;
            this.failureConsumer = failureConsumer;
        }

        public void initialize() {
            this.initializeFunction();
        }

        public CountDownLatch newLatch(int count) {
            return latch = new CountDownLatch(count);
        }

        @Override
        protected void createCheckpoint(ActionListener<TransformCheckpoint> listener) {
            listener.onResponse(TransformCheckpoint.EMPTY);
        }

        @Override
        protected String getJobId() {
            return transformConfig.getId();
        }

        @Override
        protected void doNextSearch(long waitTimeInNanos, ActionListener<SearchResponse> nextPhase) {
            assert latch != null;
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            try {
                SearchResponse response = searchFunction.apply(buildSearchRequest());
                nextPhase.onResponse(response);
            } catch (Exception e) {
                nextPhase.onFailure(e);
            }
        }

        @Override
        protected void doNextBulk(BulkRequest request, ActionListener<BulkResponse> nextPhase) {
            assert latch != null;
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            try {
                BulkResponse response = bulkFunction.apply(request);
                nextPhase.onResponse(response);
            } catch (Exception e) {
                nextPhase.onFailure(e);
            }
        }

        @Override
        protected void doSaveState(IndexerState state, TransformIndexerPosition position, Runnable next) {
            assert state == IndexerState.STARTED || state == IndexerState.INDEXING || state == IndexerState.STOPPED;
            next.run();
        }

        @Override
        protected void onFailure(Exception exc) {
            try {
                super.onFailure(exc);
            } catch (Exception e) {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter(sw, true);
                e.printStackTrace(pw);
                fail("Unexpected failure: " + e.getMessage() + " Trace: " + sw.getBuffer().toString());
            }
        }

        @Override
        protected void onFinish(ActionListener<Void> listener) {
            super.onFinish(listener);
            listener.onResponse(null);
        }

        @Override
        protected void onAbort() {
            fail("onAbort should not be called");
        }

        @Override
        protected void failIndexer(String message) {
            if (failureConsumer != null) {
                failureConsumer.accept(message);
                super.failIndexer(message);
            } else {
                fail("failIndexer should not be called, received error: " + message);
            }
        }

        @Override
        void doGetInitialProgress(SearchRequest request, ActionListener<SearchResponse> responseListener) {
            responseListener.onResponse(
                new SearchResponse(
                    new InternalSearchResponse(
                        new SearchHits(new SearchHit[0], new TotalHits(0L, TotalHits.Relation.EQUAL_TO), 0.0f),
                        // Simulate completely null aggs
                        null,
                        new Suggest(Collections.emptyList()),
                        new SearchProfileShardResults(Collections.emptyMap()),
                        false,
                        false,
                        1
                    ),
                    "",
                    1,
                    1,
                    0,
                    0,
                    ShardSearchFailure.EMPTY_ARRAY,
                    SearchResponse.Clusters.EMPTY
                )
            );
        }

        @Override
        void doDeleteByQuery(DeleteByQueryRequest deleteByQueryRequest, ActionListener<BulkByScrollResponse> responseListener) {
            responseListener.onResponse(
                new BulkByScrollResponse(
                    TimeValue.ZERO,
                    new BulkByScrollTask.Status(Collections.emptyList(), null),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    false
                )
            );
        }

        @Override
        void refreshDestinationIndex(ActionListener<RefreshResponse> responseListener) {
            responseListener.onResponse(new RefreshResponse(1, 1, 0, Collections.emptyList()));
        }
    }

    @Before
    public void setUpMocks() {
        client = new NoOpClient(getTestName());
        threadPool = new TestThreadPool(getTestName());
    }

    @After
    public void tearDownClient() {
        client.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
    }

    public void testPageSizeAdapt() throws Exception {
        Integer pageSize = randomBoolean() ? null : randomIntBetween(500, 10_000);
        TransformConfig config = new TransformConfig(
            randomAlphaOfLength(10),
            randomSourceConfig(),
            randomDestConfig(),
            null,
            null,
            null,
            randomPivotConfig(),
            null,
            randomBoolean() ? null : randomAlphaOfLengthBetween(1, 1000),
            new SettingsConfig(pageSize, null, (Boolean) null),
            null,
            null,
            null
        );
        AtomicReference<IndexerState> state = new AtomicReference<>(IndexerState.STOPPED);
        final long initialPageSize = pageSize == null ? Transform.DEFAULT_INITIAL_MAX_PAGE_SEARCH_SIZE : pageSize;
        Function<SearchRequest, SearchResponse> searchFunction = searchRequest -> {
            throw new SearchPhaseExecutionException(
                "query",
                "Partial shards failure",
                new ShardSearchFailure[] {
                    new ShardSearchFailure(new CircuitBreakingException("to much memory", 110, 100, Durability.TRANSIENT)) }
            );
        };

        Function<BulkRequest, BulkResponse> bulkFunction = bulkRequest -> new BulkResponse(new BulkItemResponse[0], 100);

        TransformAuditor auditor = MockTransformAuditor.createMockAuditor();
        TransformContext context = new TransformContext(TransformTaskState.STARTED, "", 0, mock(TransformContext.Listener.class));

        MockedTransformIndexer indexer = createMockIndexer(
            config,
            state,
            searchFunction,
            bulkFunction,
            null,
            threadPool,
            ThreadPool.Names.GENERIC,
            auditor,
            context
        );
        final CountDownLatch latch = indexer.newLatch(1);
        indexer.start();
        assertThat(indexer.getState(), equalTo(IndexerState.STARTED));
        assertTrue(indexer.maybeTriggerAsyncJob(System.currentTimeMillis()));
        assertThat(indexer.getState(), equalTo(IndexerState.INDEXING));

        latch.countDown();
        assertBusy(() -> assertThat(indexer.getState(), equalTo(IndexerState.STARTED)), 10, TimeUnit.MINUTES);
        long pageSizeAfterFirstReduction = indexer.getPageSize();
        assertThat(initialPageSize, greaterThan(pageSizeAfterFirstReduction));
        assertThat(pageSizeAfterFirstReduction, greaterThan((long) TransformIndexer.MINIMUM_PAGE_SIZE));

        // run indexer a 2nd time
        final CountDownLatch secondRunLatch = indexer.newLatch(1);
        indexer.start();
        assertEquals(pageSizeAfterFirstReduction, indexer.getPageSize());
        assertThat(indexer.getState(), equalTo(IndexerState.STARTED));
        assertTrue(indexer.maybeTriggerAsyncJob(System.currentTimeMillis()));
        assertThat(indexer.getState(), equalTo(IndexerState.INDEXING));

        secondRunLatch.countDown();
        assertBusy(() -> assertThat(indexer.getState(), equalTo(IndexerState.STARTED)));

        // assert that page size has been reduced again
        assertThat(pageSizeAfterFirstReduction, greaterThan((long) indexer.getPageSize()));
        assertThat(pageSizeAfterFirstReduction, greaterThan((long) TransformIndexer.MINIMUM_PAGE_SIZE));
    }

    public void testDoProcessAggNullCheck() {
        Integer pageSize = randomBoolean() ? null : randomIntBetween(500, 10_000);
        TransformConfig config = new TransformConfig(
            randomAlphaOfLength(10),
            randomSourceConfig(),
            randomDestConfig(),
            null,
            null,
            null,
            randomPivotConfig(),
            null,
            randomBoolean() ? null : randomAlphaOfLengthBetween(1, 1000),
            new SettingsConfig(pageSize, null, (Boolean) null),
            null,
            null,
            null
        );
        SearchResponse searchResponse = new SearchResponse(
            new InternalSearchResponse(
                new SearchHits(new SearchHit[0], new TotalHits(0L, TotalHits.Relation.EQUAL_TO), 0.0f),
                // Simulate completely null aggs
                null,
                new Suggest(Collections.emptyList()),
                new SearchProfileShardResults(Collections.emptyMap()),
                false,
                false,
                1
            ),
            "",
            1,
            1,
            0,
            0,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );
        AtomicReference<IndexerState> state = new AtomicReference<>(IndexerState.STOPPED);
        Function<SearchRequest, SearchResponse> searchFunction = searchRequest -> searchResponse;
        Function<BulkRequest, BulkResponse> bulkFunction = bulkRequest -> new BulkResponse(new BulkItemResponse[0], 100);

        TransformAuditor auditor = mock(TransformAuditor.class);
        TransformContext context = new TransformContext(TransformTaskState.STARTED, "", 0, mock(TransformContext.Listener.class));

        MockedTransformIndexer indexer = createMockIndexer(
            config,
            state,
            searchFunction,
            bulkFunction,
            null,
            threadPool,
            ThreadPool.Names.GENERIC,
            auditor,
            context
        );

        IterationResult<TransformIndexerPosition> newPosition = indexer.doProcess(searchResponse);
        assertThat(newPosition.getToIndex(), is(empty()));
        assertThat(newPosition.getPosition(), is(nullValue()));
        assertThat(newPosition.isDone(), is(true));
    }

    public void testScriptError() throws Exception {
        Integer pageSize = randomBoolean() ? null : randomIntBetween(500, 10_000);
        String transformId = randomAlphaOfLength(10);
        TransformConfig config = new TransformConfig(
            transformId,
            randomSourceConfig(),
            randomDestConfig(),
            null,
            null,
            null,
            randomPivotConfig(),
            null,
            randomBoolean() ? null : randomAlphaOfLengthBetween(1, 1000),
            new SettingsConfig(pageSize, null, (Boolean) null),
            null,
            null,
            null
        );
        AtomicReference<IndexerState> state = new AtomicReference<>(IndexerState.STOPPED);
        Function<SearchRequest, SearchResponse> searchFunction = searchRequest -> {
            throw new SearchPhaseExecutionException(
                "query",
                "Partial shards failure",
                new ShardSearchFailure[] {
                    new ShardSearchFailure(
                        new ScriptException(
                            "runtime error",
                            new ArithmeticException("/ by zero"),
                            singletonList("stack"),
                            "test",
                            "painless"
                        )
                    ) }

            );
        };

        Function<BulkRequest, BulkResponse> bulkFunction = bulkRequest -> new BulkResponse(new BulkItemResponse[0], 100);

        final AtomicBoolean failIndexerCalled = new AtomicBoolean(false);
        final AtomicReference<String> failureMessage = new AtomicReference<>();
        Consumer<String> failureConsumer = message -> {
            failIndexerCalled.compareAndSet(false, true);
            failureMessage.compareAndSet(null, message);
        };

        MockTransformAuditor auditor = MockTransformAuditor.createMockAuditor();
        TransformContext.Listener contextListener = mock(TransformContext.Listener.class);
        TransformContext context = new TransformContext(TransformTaskState.STARTED, "", 0, contextListener);

        MockedTransformIndexer indexer = createMockIndexer(
            config,
            state,
            searchFunction,
            bulkFunction,
            failureConsumer,
            threadPool,
            ThreadPool.Names.GENERIC,
            auditor,
            context
        );

        final CountDownLatch latch = indexer.newLatch(1);

        indexer.start();
        assertThat(indexer.getState(), equalTo(IndexerState.STARTED));
        assertTrue(indexer.maybeTriggerAsyncJob(System.currentTimeMillis()));
        assertThat(indexer.getState(), equalTo(IndexerState.INDEXING));

        latch.countDown();
        assertBusy(() -> assertThat(indexer.getState(), equalTo(IndexerState.STARTED)), 10, TimeUnit.SECONDS);
        assertTrue(failIndexerCalled.get());
        verify(contextListener, times(1)).fail(
            matches("Failed to execute script with error: \\[.*ArithmeticException: / by zero\\], stack trace: \\[stack\\]"),
            any()
        );

        assertThat(
            failureMessage.get(),
            matchesRegex("Failed to execute script with error: \\[.*ArithmeticException: / by zero\\], stack trace: \\[stack\\]")
        );
    }

    public void testInitializeFunction_WithNoWarnings() {
        String transformId = randomAlphaOfLength(10);
        SourceConfig sourceConfig = new SourceConfig(
            generateRandomStringArray(10, 10, false, false),
            QueryConfigTests.randomQueryConfig(),
            new HashMap<String, Object>() {
                {
                    put("field-A", singletonMap("script", "some script"));
                    put("field-B", emptyMap());
                    put("field-C", singletonMap("script", "some script"));
                }
            }
        );
        SyncConfig syncConfig = new TimeSyncConfig("field", null);
        LatestConfig latestConfig = new LatestConfig(Arrays.asList("field-A", "field-B"), "sort");
        TransformConfig config = new TransformConfig(
            transformId,
            sourceConfig,
            randomDestConfig(),
            null,
            syncConfig,
            null,
            null,
            latestConfig,
            null,
            null,
            null,
            null,
            null
        );

        MockTransformAuditor auditor = MockTransformAuditor.createMockAuditor();
        auditor.addExpectation(
            new MockTransformAuditor.UnseenAuditExpectation(
                "warn when all the group-by fields are script-based runtime fields",
                Level.WARNING,
                transformId,
                "all the group-by fields are script-based runtime fields"
            )
        );
        TransformContext.Listener contextListener = mock(TransformContext.Listener.class);
        TransformContext context = new TransformContext(TransformTaskState.STARTED, "", 0, contextListener);
        createMockIndexer(config, null, null, null, null, threadPool, ThreadPool.Names.GENERIC, auditor, context);
        auditor.assertAllExpectationsMatched();
    }

    public void testInitializeFunction_WithWarnings() {
        String transformId = randomAlphaOfLength(10);
        SourceConfig sourceConfig = new SourceConfig(
            generateRandomStringArray(10, 10, false, false),
            QueryConfigTests.randomQueryConfig(),
            new HashMap<String, Object>() {
                {
                    put("field-A", singletonMap("script", "some script"));
                    put("field-B", singletonMap("script", "some script"));
                    put("field-C", singletonMap("script", "some script"));
                    put("field-t", singletonMap("script", "some script"));
                }
            }
        );
        SyncConfig syncConfig = new TimeSyncConfig("field-t", null);
        LatestConfig latestConfig = new LatestConfig(Arrays.asList("field-A", "field-B"), "sort");
        TransformConfig config = new TransformConfig(
            transformId,
            sourceConfig,
            randomDestConfig(),
            null,
            syncConfig,
            null,
            null,
            latestConfig,
            null,
            null,
            null,
            null,
            null
        );

        MockTransformAuditor auditor = MockTransformAuditor.createMockAuditor();
        auditor.addExpectation(
            new MockTransformAuditor.SeenAuditExpectation(
                "warn when all the group-by fields are script-based runtime fields",
                Level.WARNING,
                transformId,
                "all the group-by fields are script-based runtime fields"
            )
        );
        auditor.addExpectation(
            new MockTransformAuditor.SeenAuditExpectation(
                "warn when the sync time field is a script-based runtime field",
                Level.WARNING,
                transformId,
                "sync time field is a script-based runtime field"
            )
        );
        TransformContext.Listener contextListener = mock(TransformContext.Listener.class);
        TransformContext context = new TransformContext(TransformTaskState.STARTED, "", 0, contextListener);
        createMockIndexer(config, null, null, null, null, threadPool, ThreadPool.Names.GENERIC, auditor, context);
        auditor.assertAllExpectationsMatched();
    }

    private MockedTransformIndexer createMockIndexer(
        TransformConfig config,
        AtomicReference<IndexerState> state,
        Function<SearchRequest, SearchResponse> searchFunction,
        Function<BulkRequest, BulkResponse> bulkFunction,
        Consumer<String> failureConsumer,
        ThreadPool threadPool,
        String executorName,
        TransformAuditor auditor,
        TransformContext context
    ) {
        MockedTransformIndexer indexer = new MockedTransformIndexer(
            threadPool,
            executorName,
            mock(IndexBasedTransformConfigManager.class),
            mock(CheckpointProvider.class),
            config,
            Collections.emptyMap(),
            auditor,
            state,
            null,
            new TransformIndexerStats(),
            context,
            searchFunction,
            bulkFunction,
            failureConsumer
        );

        indexer.initialize();
        return indexer;
    }

}

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

package org.elasticsearch.indices.memory.breaker;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.indices.breaker.BreakerSettings;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.CircuitBreakerStats;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.cardinality;
import static org.elasticsearch.test.ESIntegTestCase.Scope.TEST;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFailures;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Integration tests for InternalCircuitBreakerService
 */
@ClusterScope(scope = TEST, randomDynamicTemplates = false)
public class CircuitBreakerServiceIT extends ESIntegTestCase {

    /** Reset all breaker settings back to their defaults */
    private void reset() {
        logger.info("--> resetting breaker settings");
        Settings resetSettings = settingsBuilder()
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_LIMIT_SETTING,
                        HierarchyCircuitBreakerService.DEFAULT_FIELDDATA_BREAKER_LIMIT)
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_OVERHEAD_SETTING,
                        HierarchyCircuitBreakerService.DEFAULT_FIELDDATA_OVERHEAD_CONSTANT)
                .put(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_LIMIT_SETTING,
                        HierarchyCircuitBreakerService.DEFAULT_REQUEST_BREAKER_LIMIT)
                .put(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.0)
                .put(HierarchyCircuitBreakerService.IN_FLIGHT_REQUESTS_CIRCUIT_BREAKER_LIMIT_SETTING,
                        HierarchyCircuitBreakerService.DEFAULT_IN_FLIGHT_REQUESTS_BREAKER_LIMIT)
                .put(HierarchyCircuitBreakerService.IN_FLIGHT_REQUESTS_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.0)
                .build();
        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(resetSettings));
    }

    @Before
    public void setup() {
        reset();
    }

    @After
    public void teardown() {
        reset();
    }

    /** Returns true if any of the nodes used a noop breaker */
    private boolean noopBreakerUsed() {
        NodesStatsResponse stats = client().admin().cluster().prepareNodesStats().setBreaker(true).get();
        for (NodeStats nodeStats : stats) {
            if (nodeStats.getBreaker().getStats(CircuitBreaker.REQUEST).getLimit() == NoopCircuitBreaker.LIMIT) {
                return true;
            }
            if (nodeStats.getBreaker().getStats(CircuitBreaker.IN_FLIGHT_REQUESTS).getLimit() == NoopCircuitBreaker.LIMIT) {
                return true;
            }
            if (nodeStats.getBreaker().getStats(CircuitBreaker.FIELDDATA).getLimit() == NoopCircuitBreaker.LIMIT) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testMemoryBreaker() throws Exception {
        if (noopBreakerUsed()) {
            logger.info("--> noop breakers used, skipping test");
            return;
        }
        assertAcked(prepareCreate("cb-test", 1, settingsBuilder().put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))));
        final Client client = client();

        // index some different terms so we have some field data for loading
        int docCount = scaledRandomIntBetween(300, 1000);
        List<IndexRequestBuilder> reqs = new ArrayList<>();
        for (long id = 0; id < docCount; id++) {
            reqs.add(client.prepareIndex("cb-test", "type", Long.toString(id)).setSource("test", "value" + id));
        }
        indexRandom(true, false, true, reqs);

        // clear field data cache (thus setting the loaded field data back to 0)
        clearFieldData();

        // Update circuit breaker settings
        Settings settings = settingsBuilder()
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_LIMIT_SETTING, "100b")
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.05)
                .build();
        assertAcked(client.admin().cluster().prepareUpdateSettings().setTransientSettings(settings));

        // execute a search that loads field data (sorting on the "test" field)
        // again, this time it should trip the breaker
        SearchRequestBuilder searchRequest = client.prepareSearch("cb-test").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC);
        assertFailures(searchRequest, RestStatus.INTERNAL_SERVER_ERROR,
                containsString("Data too large, data for [test] would be larger than limit of [100/100b]"));

        NodesStatsResponse stats = client.admin().cluster().prepareNodesStats().setBreaker(true).get();
        int breaks = 0;
        for (NodeStats stat : stats.getNodes()) {
            CircuitBreakerStats breakerStats = stat.getBreaker().getStats(CircuitBreaker.FIELDDATA);
            breaks += breakerStats.getTrippedCount();
        }
        assertThat(breaks, greaterThanOrEqualTo(1));
    }

    @Test
    public void testRamAccountingTermsEnum() throws Exception {
        if (noopBreakerUsed()) {
            logger.info("--> noop breakers used, skipping test");
            return;
        }
        final Client client = client();

        // Create an index where the mappings have a field data filter
        assertAcked(prepareCreate("ramtest").setSource("{\"mappings\": {\"type\": {\"properties\": {\"test\": " +
                "{\"type\": \"string\",\"fielddata\": {\"filter\": {\"regex\": {\"pattern\": \"^value.*\"}}}}}}}}"));

        ensureGreen("ramtest");

        // index some different terms so we have some field data for loading
        int docCount = scaledRandomIntBetween(300, 1000);
        List<IndexRequestBuilder> reqs = new ArrayList<>();
        for (long id = 0; id < docCount; id++) {
            reqs.add(client.prepareIndex("ramtest", "type", Long.toString(id)).setSource("test", "value" + id));
        }
        indexRandom(true, false, true, reqs);

        // execute a search that loads field data (sorting on the "test" field)
        client.prepareSearch("ramtest").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC).get();

        // clear field data cache (thus setting the loaded field data back to 0)
        clearFieldData();

        // Update circuit breaker settings
        Settings settings = settingsBuilder()
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_LIMIT_SETTING, "100b")
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.05)
                .build();
        assertAcked(client.admin().cluster().prepareUpdateSettings().setTransientSettings(settings));

        // execute a search that loads field data (sorting on the "test" field)
        // again, this time it should trip the breaker
        assertFailures(client.prepareSearch("ramtest").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC),
                RestStatus.INTERNAL_SERVER_ERROR,
                containsString("Data too large, data for [test] would be larger than limit of [100/100b]"));

        NodesStatsResponse stats = client.admin().cluster().prepareNodesStats().setBreaker(true).get();
        int breaks = 0;
        for (NodeStats stat : stats.getNodes()) {
            CircuitBreakerStats breakerStats = stat.getBreaker().getStats(CircuitBreaker.FIELDDATA);
            breaks += breakerStats.getTrippedCount();
        }
        assertThat(breaks, greaterThanOrEqualTo(1));
    }

    /**
     * Test that a breaker correctly redistributes to a different breaker, in
     * this case, the fielddata breaker borrows space from the request breaker
     */
    @Test
    @AwaitsFix(bugUrl = "way too unstable request size. Needs a proper and more stable fix.")
    public void testParentChecking() throws Exception {
        if (noopBreakerUsed()) {
            logger.info("--> noop breakers used, skipping test");
            return;
        }
        assertAcked(prepareCreate("cb-test", 1, settingsBuilder().put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))));
        Client client = client();

        // index some different terms so we have some field data for loading
        int docCount = scaledRandomIntBetween(300, 1000);
        List<IndexRequestBuilder> reqs = new ArrayList<>();
        for (long id = 0; id < docCount; id++) {
            reqs.add(client.prepareIndex("cb-test", "type", Long.toString(id)).setSource("test", "value" + id));
        }
        indexRandom(true, reqs);

        // We need the request limit beforehand, just from a single node because the limit should always be the same
        long beforeReqLimit = client.admin().cluster().prepareNodesStats().setBreaker(true).get()
                .getNodes()[0].getBreaker().getStats(CircuitBreaker.REQUEST).getLimit();

        Settings resetSettings = settingsBuilder()
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_LIMIT_SETTING, "10b")
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.0)
                .build();
        assertAcked(client.admin().cluster().prepareUpdateSettings().setTransientSettings(resetSettings));

        // Perform a search to load field data for the "test" field
        try {
            client.prepareSearch("cb-test").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC).get();
            fail("should have thrown an exception");
        } catch (Exception e) {
            String errMsg = "[fielddata] Data too large, data for [test] would be larger than limit of [10/10b]";
            assertThat("Exception: [" + e.toString() + "] should contain a CircuitBreakingException",
                e.toString(), containsString(errMsg));
        }

        assertFailures(client.prepareSearch("cb-test").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC),
                RestStatus.INTERNAL_SERVER_ERROR,
                containsString("Data too large, data for [test] would be larger than limit of [10/10b]"));

        // Adjust settings so the parent breaker will fail, but neither the fielddata breaker nor the node request breaker will fail
        // There is no "one size fits all" breaker size as internal request size will vary based on doc count.
        int parentBreakerSize = docCount * 3;
        resetSettings = Settings.builder()
                .put(HierarchyCircuitBreakerService.TOTAL_CIRCUIT_BREAKER_LIMIT_SETTING, parentBreakerSize + "b")
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_LIMIT_SETTING, "90%")
                .put(HierarchyCircuitBreakerService.FIELDDATA_CIRCUIT_BREAKER_OVERHEAD_SETTING, 1.0)
                .build();
        client.admin().cluster().prepareUpdateSettings().setTransientSettings(resetSettings).execute().actionGet();

        // Perform a search to load field data for the "test" field
        try {
            client.prepareSearch("cb-test").setQuery(matchAllQuery()).addSort("test", SortOrder.DESC).get();
            fail("should have thrown an exception");
        } catch (Exception e) {
            String errMsg = "[parent] Data too large, data for [test] would be larger than limit of [" + parentBreakerSize;
            assertThat("Exception: [" + e.toString() + "] should contain a CircuitBreakingException",
                    e.toString(), containsString(errMsg));
        }
    }

    @Test
    public void testRequestBreaker() throws Exception {
        if (noopBreakerUsed()) {
            logger.info("--> noop breakers used, skipping test");
            return;
        }
        assertAcked(prepareCreate("cb-test", 1, settingsBuilder().put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))));
        Client client = client();

        // Make request breaker limited to a small amount
        Settings resetSettings = settingsBuilder()
                .put(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_LIMIT_SETTING, "10b")
                .build();
        assertAcked(client.admin().cluster().prepareUpdateSettings().setTransientSettings(resetSettings));

        // index some different terms so we have some field data for loading
        int docCount = scaledRandomIntBetween(300, 1000);
        List<IndexRequestBuilder> reqs = new ArrayList<>();
        for (long id = 0; id < docCount; id++) {
            reqs.add(client.prepareIndex("cb-test", "type", Long.toString(id)).setSource("test", id));
        }
        indexRandom(true, reqs);

        // A cardinality aggregation uses BigArrays and thus the REQUEST breaker
        try {
            client.prepareSearch("cb-test").setQuery(matchAllQuery()).addAggregation(cardinality("card").field("test")).get();
            fail("aggregation should have tripped the breaker");
        } catch (Exception e) {
            String errMsg = "CircuitBreakingException[[request] Data too large, data for [<reused_arrays>] would be larger than limit of [10/10b]]";
            assertThat("Exception: [" + e.toString() + "] should contain a CircuitBreakingException",
                e.toString(), containsString(errMsg));
        }
    }

    /** Issues a cache clear and waits 30 seconds for the field data breaker to be cleared */
    public void clearFieldData() throws Exception {
        client().admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                NodesStatsResponse resp = client().admin().cluster().prepareNodesStats()
                        .clear().setBreaker(true).get(new TimeValue(15, TimeUnit.SECONDS));
                for (NodeStats nStats : resp.getNodes()) {
                    assertThat("fielddata breaker never reset back to 0",
                            nStats.getBreaker().getStats(CircuitBreaker.FIELDDATA).getEstimated(),
                            equalTo(0L));
                }
            }
        }, 30, TimeUnit.SECONDS);
    }

    @Test
    public void testCustomCircuitBreakerRegistration() throws Exception {
        Iterable<CircuitBreakerService> serviceIter = internalCluster().getInstances(CircuitBreakerService.class);

        final String breakerName = "customBreaker";
        BreakerSettings breakerSettings = new BreakerSettings(breakerName, 8, 1.03);
        CircuitBreaker breaker = null;

        for (CircuitBreakerService s : serviceIter) {
            s.registerBreaker(breakerSettings);
            breaker = s.getBreaker(breakerSettings.getName());
        }

        if (breaker != null) {
            try {
                breaker.addEstimateBytesAndMaybeBreak(16, "test");
            } catch (CircuitBreakingException e) {
                // ignore, we forced a circuit break
            }
        }

        NodesStatsResponse stats = client().admin().cluster().prepareNodesStats().clear().setBreaker(true).get();
        int breaks = 0;
        for (NodeStats stat : stats.getNodes()) {
            CircuitBreakerStats breakerStats = stat.getBreaker().getStats(breakerName);
            breaks += breakerStats.getTrippedCount();
        }
        assertThat(breaks, greaterThanOrEqualTo(1));
    }

    @Test
    public void testLimitsRequestSize() throws Exception {
        ByteSizeValue inFlightRequestsLimit = new ByteSizeValue(8, ByteSizeUnit.KB);
        if (noopBreakerUsed()) {
            logger.info("--> noop breakers used, skipping test");
            return;
        }

        internalCluster().ensureAtLeastNumDataNodes(2);

        NodesStatsResponse nodeStats = client().admin().cluster().prepareNodesStats().get();
        List<NodeStats> dataNodeStats = new ArrayList<>();
        for (NodeStats stat : nodeStats.getNodes()) {
            if (stat.getNode().isDataNode()) {
                dataNodeStats.add(stat);
            }
        }

        assertThat(dataNodeStats.size(), greaterThanOrEqualTo(2));
        Collections.shuffle(dataNodeStats, random());

        // send bulk request from source node to target node later. The sole shard is bound to the target node.
        NodeStats targetNode = dataNodeStats.get(0);
        NodeStats sourceNode = dataNodeStats.get(1);

        assertAcked(prepareCreate("index").setSettings(Settings.builder()
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
            .put("index.routing.allocation.include._name", targetNode.getNode().getName())
            .put(EnableAllocationDecider.INDEX_ROUTING_REBALANCE_ENABLE, EnableAllocationDecider.Rebalance.NONE)
        ));

        Client client = client(sourceNode.getNode().getName());

        // we use the limit size as a (very) rough indication on how many requests we should sent to hit the limit
        int numRequests = inFlightRequestsLimit.bytesAsInt();
        BulkRequest bulkRequest = new BulkRequest();
        for (int i = 0; i < numRequests; i++) {
            IndexRequest indexRequest = new IndexRequest("index", "type", Integer.toString(i));
            indexRequest.source("field", "value", "num", i);
            bulkRequest.add(indexRequest);
        }

        Settings limitSettings = Settings.builder()
            .put(HierarchyCircuitBreakerService.IN_FLIGHT_REQUESTS_CIRCUIT_BREAKER_LIMIT_SETTING, inFlightRequestsLimit)
            .build();

        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(limitSettings));

        // can either fail directly with an exception or the response contains exceptions (depending on client)
        try {
            BulkResponse response = client.bulk(bulkRequest).actionGet();
            if (!response.hasFailures()) {
                fail("Should have thrown CircuitBreakingException");
            } else {
                // each item must have failed with CircuitBreakingException
                for (BulkItemResponse bulkItemResponse : response) {
                    Throwable cause = ExceptionsHelper.unwrapCause(bulkItemResponse.getFailure().getCause());
                    assertThat(cause, instanceOf(CircuitBreakingException.class));
                    assertEquals(((CircuitBreakingException) cause).getByteLimit(), inFlightRequestsLimit.bytes());
                }
            }
        } catch (CircuitBreakingException ex) {
            assertEquals(ex.getByteLimit(), inFlightRequestsLimit.bytes());
        }
    }
}
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

package org.opensearch.search.aggregations.bucket;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongSet;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.time.DateFormatter;
import org.opensearch.index.fielddata.ScriptDocValues;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.ScriptType;
import org.opensearch.search.aggregations.AggregationTestScriptsPlugin;
import org.opensearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.search.aggregations.bucket.histogram.Histogram;
import org.opensearch.search.aggregations.bucket.terms.IncludeExclude;
import org.opensearch.search.aggregations.bucket.terms.Terms;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.search.aggregations.AggregationBuilders.dateHistogram;
import static org.opensearch.search.aggregations.AggregationBuilders.histogram;
import static org.opensearch.search.aggregations.AggregationBuilders.terms;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAllSuccessful;

@OpenSearchIntegTestCase.SuiteScopeTestCase
public class MinDocCountIT extends AbstractTermsTestCase {

    private static final QueryBuilder QUERY = QueryBuilders.termQuery("match", true);
    private static int cardinality;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(CustomScriptPlugin.class);
    }

    public static class CustomScriptPlugin extends AggregationTestScriptsPlugin {

        @Override
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("doc['d']", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Doubles value = (ScriptDocValues.Doubles) doc.get("d");
                return value;
            });

            scripts.put("doc['l']", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Longs value = (ScriptDocValues.Longs) doc.get("l");
                return value;
            });

            scripts.put("doc['s']", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Strings value = (ScriptDocValues.Strings) doc.get("s");
                return value;
            });

            return scripts;
        }
    }

    @Override
    public void setupSuiteScopeCluster() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("idx").addMapping("type", "s", "type=keyword").get());

        cardinality = randomIntBetween(8, 30);
        final List<IndexRequestBuilder> indexRequests = new ArrayList<>();
        final Set<String> stringTerms = new HashSet<>();
        final LongSet longTerms = new LongHashSet();
        for (int i = 0; i < cardinality; ++i) {
            String stringTerm;
            do {
                stringTerm = RandomStrings.randomAsciiOfLength(random(), 8);
            } while (!stringTerms.add(stringTerm));
            long longTerm;
            do {
                longTerm = randomInt(cardinality * 2);
            } while (!longTerms.add(longTerm));
            double doubleTerm = longTerm * Math.PI;

            ZonedDateTime time = ZonedDateTime.of(2014, 1, ((int) longTerm % 20) + 1, 0, 0, 0, 0, ZoneOffset.UTC);
            String dateTerm = DateFormatter.forPattern("yyyy-MM-dd").format(time);
            final int frequency = randomBoolean() ? 1 : randomIntBetween(2, 20);
            for (int j = 0; j < frequency; ++j) {
                indexRequests.add(
                    client().prepareIndex("idx")
                        .setSource(
                            jsonBuilder().startObject()
                                .field("s", stringTerm)
                                .field("l", longTerm)
                                .field("d", doubleTerm)
                                .field("date", dateTerm)
                                .field("match", randomBoolean())
                                .endObject()
                        )
                );
            }
        }
        cardinality = stringTerms.size();

        indexRandom(true, indexRequests);
        ensureSearchable();
    }

    private enum Script {
        NO {
            @Override
            TermsAggregationBuilder apply(TermsAggregationBuilder builder, String field) {
                return builder.field(field);
            }
        },
        YES {
            @Override
            TermsAggregationBuilder apply(TermsAggregationBuilder builder, String field) {
                return builder.script(
                    new org.opensearch.script.Script(
                        ScriptType.INLINE,
                        CustomScriptPlugin.NAME,
                        "doc['" + field + "']",
                        Collections.emptyMap()
                    )
                );
            }
        };

        abstract TermsAggregationBuilder apply(TermsAggregationBuilder builder, String field);
    }

    // check that terms2 is a subset of terms1
    private void assertSubset(Terms terms1, Terms terms2, long minDocCount, int size, String include) {
        final Matcher matcher = include == null ? null : Pattern.compile(include).matcher("");
        final Iterator<? extends Terms.Bucket> it1 = terms1.getBuckets().iterator();
        final Iterator<? extends Terms.Bucket> it2 = terms2.getBuckets().iterator();
        int size2 = 0;
        while (it1.hasNext()) {
            final Terms.Bucket bucket1 = it1.next();
            if (bucket1.getDocCount() >= minDocCount && (matcher == null || matcher.reset(bucket1.getKeyAsString()).matches())) {
                if (size2++ == size) {
                    break;
                }
                assertTrue("minDocCount: " + minDocCount, it2.hasNext());
                final Terms.Bucket bucket2 = it2.next();
                assertEquals("minDocCount: " + minDocCount, bucket1.getDocCount(), bucket2.getDocCount());
            }
        }
        assertFalse(it2.hasNext());
    }

    private void assertSubset(Histogram histo1, Histogram histo2, long minDocCount) {
        final Iterator<? extends Histogram.Bucket> it2 = histo2.getBuckets().iterator();
        for (Histogram.Bucket b1 : histo1.getBuckets()) {
            if (b1.getDocCount() >= minDocCount) {
                final Histogram.Bucket b2 = it2.next();
                assertEquals(b1.getKey(), b2.getKey());
                assertEquals(b1.getDocCount(), b2.getDocCount());
            }
        }
    }

    public void testStringTermAsc() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.key(true));
    }

    public void testStringScriptTermAsc() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.key(true));
    }

    public void testStringTermDesc() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.key(false));
    }

    public void testStringScriptTermDesc() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.key(false));
    }

    public void testStringCountAsc() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.count(true));
    }

    public void testStringScriptCountAsc() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.count(true));
    }

    public void testStringCountDesc() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.count(false));
    }

    public void testStringScriptCountDesc() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.count(false));
    }

    public void testStringCountAscWithInclude() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.count(true), ".*a.*", true);
    }

    public void testStringScriptCountAscWithInclude() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.count(true), ".*a.*", true);
    }

    public void testStringCountDescWithInclude() throws Exception {
        testMinDocCountOnTerms("s", Script.NO, BucketOrder.count(false), ".*a.*", true);
    }

    public void testStringScriptCountDescWithInclude() throws Exception {
        testMinDocCountOnTerms("s", Script.YES, BucketOrder.count(false), ".*a.*", true);
    }

    public void testLongTermAsc() throws Exception {
        testMinDocCountOnTerms("l", Script.NO, BucketOrder.key(true));
    }

    public void testLongScriptTermAsc() throws Exception {
        testMinDocCountOnTerms("l", Script.YES, BucketOrder.key(true));
    }

    public void testLongTermDesc() throws Exception {
        testMinDocCountOnTerms("l", Script.NO, BucketOrder.key(false));
    }

    public void testLongScriptTermDesc() throws Exception {
        testMinDocCountOnTerms("l", Script.YES, BucketOrder.key(false));
    }

    public void testLongCountAsc() throws Exception {
        testMinDocCountOnTerms("l", Script.NO, BucketOrder.count(true));
    }

    public void testLongScriptCountAsc() throws Exception {
        testMinDocCountOnTerms("l", Script.YES, BucketOrder.count(true));
    }

    public void testLongCountDesc() throws Exception {
        testMinDocCountOnTerms("l", Script.NO, BucketOrder.count(false));
    }

    public void testLongScriptCountDesc() throws Exception {
        testMinDocCountOnTerms("l", Script.YES, BucketOrder.count(false));
    }

    public void testDoubleTermAsc() throws Exception {
        testMinDocCountOnTerms("d", Script.NO, BucketOrder.key(true));
    }

    public void testDoubleScriptTermAsc() throws Exception {
        testMinDocCountOnTerms("d", Script.YES, BucketOrder.key(true));
    }

    public void testDoubleTermDesc() throws Exception {
        testMinDocCountOnTerms("d", Script.NO, BucketOrder.key(false));
    }

    public void testDoubleScriptTermDesc() throws Exception {
        testMinDocCountOnTerms("d", Script.YES, BucketOrder.key(false));
    }

    public void testDoubleCountAsc() throws Exception {
        testMinDocCountOnTerms("d", Script.NO, BucketOrder.count(true));
    }

    public void testDoubleScriptCountAsc() throws Exception {
        testMinDocCountOnTerms("d", Script.YES, BucketOrder.count(true));
    }

    public void testDoubleCountDesc() throws Exception {
        testMinDocCountOnTerms("d", Script.NO, BucketOrder.count(false));
    }

    public void testDoubleScriptCountDesc() throws Exception {
        testMinDocCountOnTerms("d", Script.YES, BucketOrder.count(false));
    }

    private void testMinDocCountOnTerms(String field, Script script, BucketOrder order) throws Exception {
        testMinDocCountOnTerms(field, script, order, null, true);
    }

    private void testMinDocCountOnTerms(String field, Script script, BucketOrder order, String include, boolean retry) throws Exception {
        // all terms
        final SearchResponse allTermsResponse = client().prepareSearch("idx")
            .setSize(0)
            .setQuery(QUERY)
            .addAggregation(
                script.apply(terms("terms"), field)
                    .collectMode(randomFrom(SubAggCollectionMode.values()))
                    .executionHint(randomExecutionHint())
                    .order(order)
                    .size(cardinality + randomInt(10))
                    .minDocCount(0)
            )
            .get();
        assertAllSuccessful(allTermsResponse);

        final Terms allTerms = allTermsResponse.getAggregations().get("terms");
        assertEquals(cardinality, allTerms.getBuckets().size());

        for (long minDocCount = 0; minDocCount < 20; ++minDocCount) {
            final int size = randomIntBetween(1, cardinality + 2);
            final SearchRequest request = client().prepareSearch("idx")
                .setSize(0)
                .setQuery(QUERY)
                .addAggregation(
                    script.apply(terms("terms"), field)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .executionHint(randomExecutionHint())
                        .order(order)
                        .size(size)
                        .includeExclude(include == null ? null : new IncludeExclude(include, null))
                        .shardSize(cardinality + randomInt(10))
                        .minDocCount(minDocCount)
                )
                .request();
            final SearchResponse response = client().search(request).get();
            assertAllSuccessful(response);
            assertSubset(allTerms, (Terms) response.getAggregations().get("terms"), minDocCount, size, include);
        }
    }

    public void testHistogramCountAsc() throws Exception {
        testMinDocCountOnHistogram(BucketOrder.count(true));
    }

    public void testHistogramCountDesc() throws Exception {
        testMinDocCountOnHistogram(BucketOrder.count(false));
    }

    public void testHistogramKeyAsc() throws Exception {
        testMinDocCountOnHistogram(BucketOrder.key(true));
    }

    public void testHistogramKeyDesc() throws Exception {
        testMinDocCountOnHistogram(BucketOrder.key(false));
    }

    public void testDateHistogramCountAsc() throws Exception {
        testMinDocCountOnDateHistogram(BucketOrder.count(true));
    }

    public void testDateHistogramCountDesc() throws Exception {
        testMinDocCountOnDateHistogram(BucketOrder.count(false));
    }

    public void testDateHistogramKeyAsc() throws Exception {
        testMinDocCountOnDateHistogram(BucketOrder.key(true));
    }

    public void testDateHistogramKeyDesc() throws Exception {
        testMinDocCountOnDateHistogram(BucketOrder.key(false));
    }

    private void testMinDocCountOnHistogram(BucketOrder order) throws Exception {
        final int interval = randomIntBetween(1, 3);
        final SearchResponse allResponse = client().prepareSearch("idx")
            .setSize(0)
            .setQuery(QUERY)
            .addAggregation(histogram("histo").field("d").interval(interval).order(order).minDocCount(0))
            .get();

        final Histogram allHisto = allResponse.getAggregations().get("histo");

        for (long minDocCount = 0; minDocCount < 50; ++minDocCount) {
            final SearchResponse response = client().prepareSearch("idx")
                .setSize(0)
                .setQuery(QUERY)
                .addAggregation(histogram("histo").field("d").interval(interval).order(order).minDocCount(minDocCount))
                .get();
            assertSubset(allHisto, (Histogram) response.getAggregations().get("histo"), minDocCount);
        }
    }

    private void testMinDocCountOnDateHistogram(BucketOrder order) throws Exception {
        final SearchResponse allResponse = client().prepareSearch("idx")
            .setSize(0)
            .setQuery(QUERY)
            .addAggregation(
                dateHistogram("histo").field("date").dateHistogramInterval(DateHistogramInterval.DAY).order(order).minDocCount(0)
            )
            .get();

        final Histogram allHisto = allResponse.getAggregations().get("histo");

        for (long minDocCount = 0; minDocCount < 50; ++minDocCount) {
            final SearchResponse response = client().prepareSearch("idx")
                .setSize(0)
                .setQuery(QUERY)
                .addAggregation(
                    dateHistogram("histo").field("date")
                        .dateHistogramInterval(DateHistogramInterval.DAY)
                        .order(order)
                        .minDocCount(minDocCount)
                )
                .get();
            assertSubset(allHisto, response.getAggregations().get("histo"), minDocCount);
        }
    }
}

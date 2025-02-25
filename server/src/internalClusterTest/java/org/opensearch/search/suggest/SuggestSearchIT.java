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

package org.opensearch.search.suggest;

import org.opensearch.OpenSearchException;
import org.opensearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.search.SearchPhaseExecutionException;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.Strings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.index.IndexSettings;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.TemplateScript;
import org.opensearch.search.suggest.phrase.DirectCandidateGeneratorBuilder;
import org.opensearch.search.suggest.phrase.Laplace;
import org.opensearch.search.suggest.phrase.LinearInterpolation;
import org.opensearch.search.suggest.phrase.PhraseSuggestionBuilder;
import org.opensearch.search.suggest.phrase.StupidBackoff;
import org.opensearch.search.suggest.term.TermSuggestionBuilder;
import org.opensearch.search.suggest.term.TermSuggestionBuilder.SuggestMode;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.hamcrest.OpenSearchAssertions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.opensearch.index.query.QueryBuilders.matchQuery;
import static org.opensearch.search.suggest.SuggestBuilders.phraseSuggestion;
import static org.opensearch.search.suggest.SuggestBuilders.termSuggestion;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertNoFailures;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertRequestBuilderThrows;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSuggestion;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSuggestionPhraseCollateMatchExists;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSuggestionSize;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for term and phrase suggestions.  Many of these tests many requests that vary only slightly from one another.  Where
 * possible these tests should declare for the first request, make the request, modify the configuration for the next request, make that
 * request, modify again, request again, etc.  This makes it very obvious what changes between requests.
 */
public class SuggestSearchIT extends OpenSearchIntegTestCase {

    // see #3196
    public void testSuggestAcrossMultipleIndices() throws IOException {
        assertAcked(prepareCreate("test").addMapping("type1", "text", "type=text"));
        ensureGreen();

        index("test", "type1", "1", "text", "abcd");
        index("test", "type1", "2", "text", "aacd");
        index("test", "type1", "3", "text", "abbd");
        index("test", "type1", "4", "text", "abcc");
        refresh();

        TermSuggestionBuilder termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary
                                                                                                   // between requests.
            .text("abcd");
        logger.info("--> run suggestions with one index");
        searchSuggest("test", termSuggest);
        assertAcked(prepareCreate("test_1").addMapping("type1", "text", "type=text"));
        ensureGreen();

        index("test_1", "type1", "1", "text", "ab cd");
        index("test_1", "type1", "2", "text", "aa cd");
        index("test_1", "type1", "3", "text", "ab bd");
        index("test_1", "type1", "4", "text", "ab cc");
        refresh();
        termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary between requests.
            .text("ab cd")
            .minWordLength(1);
        logger.info("--> run suggestions with two indices");
        searchSuggest("test", termSuggest);

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("text")
            .field("type", "text")
            .field("analyzer", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(prepareCreate("test_2").addMapping("type1", mapping));
        ensureGreen();

        index("test_2", "type1", "1", "text", "ab cd");
        index("test_2", "type1", "2", "text", "aa cd");
        index("test_2", "type1", "3", "text", "ab bd");
        index("test_2", "type1", "4", "text", "ab cc");
        index("test_2", "type1", "1", "text", "abcd");
        index("test_2", "type1", "2", "text", "aacd");
        index("test_2", "type1", "3", "text", "abbd");
        index("test_2", "type1", "4", "text", "abcc");
        refresh();

        termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary between requests.
            .text("ab cd")
            .minWordLength(1);
        logger.info("--> run suggestions with three indices");
        try {
            searchSuggest("test", termSuggest);
            fail(" can not suggest across multiple indices with different analysis chains");
        } catch (SearchPhaseExecutionException ex) {
            assertThat(ex.getCause(), instanceOf(IllegalStateException.class));
            assertThat(
                ex.getCause().getMessage(),
                anyOf(
                    endsWith("Suggest entries have different sizes actual [1] expected [2]"),
                    endsWith("Suggest entries have different sizes actual [2] expected [1]")
                )
            );
        } catch (IllegalStateException ex) {
            assertThat(
                ex.getMessage(),
                anyOf(
                    endsWith("Suggest entries have different sizes actual [1] expected [2]"),
                    endsWith("Suggest entries have different sizes actual [2] expected [1]")
                )
            );
        }

        termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary between requests.
            .text("ABCD")
            .minWordLength(1);
        logger.info("--> run suggestions with four indices");
        try {
            searchSuggest("test", termSuggest);
            fail(" can not suggest across multiple indices with different analysis chains");
        } catch (SearchPhaseExecutionException ex) {
            assertThat(ex.getCause(), instanceOf(IllegalStateException.class));
            assertThat(
                ex.getCause().getMessage(),
                anyOf(
                    endsWith("Suggest entries have different text actual [ABCD] expected [abcd]"),
                    endsWith("Suggest entries have different text actual [abcd] expected [ABCD]")
                )
            );
        } catch (IllegalStateException ex) {
            assertThat(
                ex.getMessage(),
                anyOf(
                    endsWith("Suggest entries have different text actual [ABCD] expected [abcd]"),
                    endsWith("Suggest entries have different text actual [abcd] expected [ABCD]")
                )
            );
        }
    }

    // see #3037
    public void testSuggestModes() throws IOException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(SETTING_NUMBER_OF_SHARDS, 1)
                .put(SETTING_NUMBER_OF_REPLICAS, 0)
                .put("index.analysis.analyzer.biword.tokenizer", "standard")
                .putList("index.analysis.analyzer.biword.filter", "shingler", "lowercase")
                .put("index.analysis.filter.shingler.type", "shingle")
                .put("index.analysis.filter.shingler.min_shingle_size", 2)
                .put("index.analysis.filter.shingler.max_shingle_size", 3)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("name")
            .field("type", "text")
            .startObject("fields")
            .startObject("shingled")
            .field("type", "text")
            .field("analyzer", "biword")
            .field("search_analyzer", "standard")
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        index("test", "type1", "1", "name", "I like iced tea");
        index("test", "type1", "2", "name", "I like tea.");
        index("test", "type1", "3", "name", "I like ice cream.");
        refresh();

        DirectCandidateGeneratorBuilder generator = candidateGenerator("name").prefixLength(0)
            .minWordLength(0)
            .suggestMode("always")
            .maxEdits(2);
        PhraseSuggestionBuilder phraseSuggestion = phraseSuggestion("name.shingled").addCandidateGenerator(generator).gramSize(3);
        Suggest searchSuggest = searchSuggest("ice tea", "did_you_mean", phraseSuggestion);
        assertSuggestion(searchSuggest, 0, "did_you_mean", "iced tea");

        generator.suggestMode(null);
        searchSuggest = searchSuggest("ice tea", "did_you_mean", phraseSuggestion);
        assertSuggestionSize(searchSuggest, 0, 0, "did_you_mean");
    }

    /**
     * Creates a new {@link DirectCandidateGeneratorBuilder}
     *
     * @param field
     *            the field this candidate generator operates on.
     */
    private DirectCandidateGeneratorBuilder candidateGenerator(String field) {
        return new DirectCandidateGeneratorBuilder(field);
    }

    // see #2729
    public void testSizeOneShard() throws Exception {
        prepareCreate("test").setSettings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1).put(SETTING_NUMBER_OF_REPLICAS, 0)).get();
        ensureGreen();

        for (int i = 0; i < 15; i++) {
            index("test", "type1", Integer.toString(i), "text", "abc" + i);
        }
        refresh();

        SearchResponse search = client().prepareSearch().setQuery(matchQuery("text", "spellchecker")).get();
        assertThat("didn't ask for suggestions but got some", search.getSuggest(), nullValue());

        TermSuggestionBuilder termSuggestion = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can
                                                                                                      // vary between requests.
            .text("abcd")
            .size(10);
        Suggest suggest = searchSuggest("test", termSuggestion);
        assertSuggestion(suggest, 0, "test", 10, "abc0");

        termSuggestion.text("abcd").shardSize(5);
        suggest = searchSuggest("test", termSuggestion);
        assertSuggestion(suggest, 0, "test", 5, "abc0");
    }

    public void testUnmappedField() throws IOException, InterruptedException, ExecutionException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put("index.analysis.analyzer.biword.tokenizer", "standard")
                .putList("index.analysis.analyzer.biword.filter", "shingler", "lowercase")
                .put("index.analysis.filter.shingler.type", "shingle")
                .put("index.analysis.filter.shingler.min_shingle_size", 2)
                .put("index.analysis.filter.shingler.max_shingle_size", 3)
        );
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("name")
            .field("type", "text")
            .startObject("fields")
            .startObject("shingled")
            .field("type", "text")
            .field("analyzer", "biword")
            .field("search_analyzer", "standard")
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        indexRandom(
            true,
            client().prepareIndex("test").setSource("name", "I like iced tea"),
            client().prepareIndex("test").setSource("name", "I like tea."),
            client().prepareIndex("test").setSource("name", "I like ice cream.")
        );
        refresh();

        PhraseSuggestionBuilder phraseSuggestion = phraseSuggestion("name.shingled").addCandidateGenerator(
            candidateGenerator("name").prefixLength(0).minWordLength(0).suggestMode("always").maxEdits(2)
        ).gramSize(3);
        Suggest searchSuggest = searchSuggest("ice tea", "did_you_mean", phraseSuggestion);
        assertSuggestion(searchSuggest, 0, 0, "did_you_mean", "iced tea");

        phraseSuggestion = phraseSuggestion("nosuchField").addCandidateGenerator(
            candidateGenerator("name").prefixLength(0).minWordLength(0).suggestMode("always").maxEdits(2)
        ).gramSize(3);
        {
            SearchRequestBuilder searchBuilder = client().prepareSearch().setSize(0);
            searchBuilder.suggest(new SuggestBuilder().setGlobalText("tetsting sugestion").addSuggestion("did_you_mean", phraseSuggestion));
            assertRequestBuilderThrows(searchBuilder, SearchPhaseExecutionException.class);
        }
        {
            SearchRequestBuilder searchBuilder = client().prepareSearch().setSize(0);
            searchBuilder.suggest(new SuggestBuilder().setGlobalText("tetsting sugestion").addSuggestion("did_you_mean", phraseSuggestion));
            assertRequestBuilderThrows(searchBuilder, SearchPhaseExecutionException.class);
        }
    }

    public void testSimple() throws Exception {
        assertAcked(prepareCreate("test").addMapping("type1", "text", "type=text"));
        ensureGreen();

        index("test", "type1", "1", "text", "abcd");
        index("test", "type1", "2", "text", "aacd");
        index("test", "type1", "3", "text", "abbd");
        index("test", "type1", "4", "text", "abcc");
        refresh();

        SearchResponse search = client().prepareSearch().setQuery(matchQuery("text", "spellcecker")).get();
        assertThat("didn't ask for suggestions but got some", search.getSuggest(), nullValue());

        TermSuggestionBuilder termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary
                                                                                                   // between requests.
            .text("abcd");
        Suggest suggest = searchSuggest("test", termSuggest);
        assertSuggestion(suggest, 0, "test", "aacd", "abbd", "abcc");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));

        suggest = searchSuggest("test", termSuggest);
        assertSuggestion(suggest, 0, "test", "aacd", "abbd", "abcc");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));
    }

    public void testEmpty() throws Exception {
        assertAcked(prepareCreate("test").addMapping("type1", "text", "type=text"));
        ensureGreen();

        index("test", "type1", "1", "text", "bar");
        refresh();

        TermSuggestionBuilder termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS) // Always, otherwise the results can vary
                                                                                                   // between requests.
            .text("abcd");
        Suggest suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));

        suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));
    }

    public void testEmptyIndex() throws Exception {
        assertAcked(prepareCreate("test").addMapping("type1", "text", "type=text"));
        ensureGreen();

        // use SuggestMode.ALWAYS, otherwise the results can vary between requests.
        TermSuggestionBuilder termSuggest = termSuggestion("text").suggestMode(SuggestMode.ALWAYS).text("abcd");
        Suggest suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));

        suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));

        index("test", "type1", "1", "text", "bar");
        refresh();

        suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));

        suggest = searchSuggest("test", termSuggest);
        assertSuggestionSize(suggest, 0, 0, "test");
        assertThat(suggest.getSuggestion("test").getEntries().get(0).getText().string(), equalTo("abcd"));
    }

    public void testWithMultipleCommands() throws Exception {
        assertAcked(prepareCreate("test").addMapping("typ1", "field1", "type=text", "field2", "type=text"));
        ensureGreen();

        index("test", "typ1", "1", "field1", "prefix_abcd", "field2", "prefix_efgh");
        index("test", "typ1", "2", "field1", "prefix_aacd", "field2", "prefix_eeeh");
        index("test", "typ1", "3", "field1", "prefix_abbd", "field2", "prefix_efff");
        index("test", "typ1", "4", "field1", "prefix_abcc", "field2", "prefix_eggg");
        refresh();

        Map<String, SuggestionBuilder<?>> suggestions = new HashMap<>();
        suggestions.put(
            "size1",
            termSuggestion("field1").size(1)
                .text("prefix_abcd")
                .maxTermFreq(10)
                .prefixLength(1)
                .minDocFreq(0)
                .suggestMode(SuggestMode.ALWAYS)
        );
        suggestions.put(
            "field2",
            termSuggestion("field2").text("prefix_eeeh prefix_efgh").maxTermFreq(10).minDocFreq(0).suggestMode(SuggestMode.ALWAYS)
        );
        suggestions.put(
            "accuracy",
            termSuggestion("field2").text("prefix_efgh").accuracy(1f).maxTermFreq(10).minDocFreq(0).suggestMode(SuggestMode.ALWAYS)
        );
        Suggest suggest = searchSuggest(null, 0, suggestions);
        assertSuggestion(suggest, 0, "size1", "prefix_aacd");
        assertThat(suggest.getSuggestion("field2").getEntries().get(0).getText().string(), equalTo("prefix_eeeh"));
        assertSuggestion(suggest, 0, "field2", "prefix_efgh");
        assertThat(suggest.getSuggestion("field2").getEntries().get(1).getText().string(), equalTo("prefix_efgh"));
        assertSuggestion(suggest, 1, "field2", "prefix_eeeh", "prefix_efff", "prefix_eggg");
        assertSuggestionSize(suggest, 0, 0, "accuracy");
    }

    public void testSizeAndSort() throws Exception {
        createIndex("test");
        ensureGreen();

        Map<String, Integer> termsAndDocCount = new HashMap<>();
        termsAndDocCount.put("prefix_aaad", 20);
        termsAndDocCount.put("prefix_abbb", 18);
        termsAndDocCount.put("prefix_aaca", 16);
        termsAndDocCount.put("prefix_abba", 14);
        termsAndDocCount.put("prefix_accc", 12);
        termsAndDocCount.put("prefix_addd", 10);
        termsAndDocCount.put("prefix_abaa", 8);
        termsAndDocCount.put("prefix_dbca", 6);
        termsAndDocCount.put("prefix_cbad", 4);
        termsAndDocCount.put("prefix_aacd", 1);
        termsAndDocCount.put("prefix_abcc", 1);
        termsAndDocCount.put("prefix_accd", 1);

        for (Entry<String, Integer> entry : termsAndDocCount.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                index("test", "type1", entry.getKey() + i, "field1", entry.getKey());
            }
        }
        refresh();

        Map<String, SuggestionBuilder<?>> suggestions = new HashMap<>();
        suggestions.put("size3SortScoreFirst", termSuggestion("field1").size(3).minDocFreq(0).suggestMode(SuggestMode.ALWAYS));
        suggestions.put(
            "size10SortScoreFirst",
            termSuggestion("field1").size(10).minDocFreq(0).suggestMode(SuggestMode.ALWAYS).shardSize(50)
        );
        suggestions.put(
            "size3SortScoreFirstMaxEdits1",
            termSuggestion("field1").maxEdits(1).size(10).minDocFreq(0).suggestMode(SuggestMode.ALWAYS)
        );
        suggestions.put(
            "size10SortFrequencyFirst",
            termSuggestion("field1").size(10).sort(SortBy.FREQUENCY).shardSize(1000).minDocFreq(0).suggestMode(SuggestMode.ALWAYS)
        );
        Suggest suggest = searchSuggest("prefix_abcd", 0, suggestions);

        // The commented out assertions fail sometimes because suggestions are based off of shard frequencies instead of index frequencies.
        assertSuggestion(suggest, 0, "size3SortScoreFirst", "prefix_aacd", "prefix_abcc", "prefix_accd");
        assertSuggestion(suggest, 0, "size10SortScoreFirst", 10, "prefix_aacd", "prefix_abcc", "prefix_accd" /*, "prefix_aaad" */);
        assertSuggestion(suggest, 0, "size3SortScoreFirstMaxEdits1", "prefix_aacd", "prefix_abcc", "prefix_accd");
        assertSuggestion(
            suggest,
            0,
            "size10SortFrequencyFirst",
            "prefix_aaad",
            "prefix_abbb",
            "prefix_aaca",
            "prefix_abba",
            "prefix_accc",
            "prefix_addd",
            "prefix_abaa",
            "prefix_dbca",
            "prefix_cbad",
            "prefix_aacd"
        );

        // assertThat(suggest.get(3).getSuggestedWords().get("prefix_abcd").get(4).getTerm(), equalTo("prefix_abcc"));
        // assertThat(suggest.get(3).getSuggestedWords().get("prefix_abcd").get(4).getTerm(), equalTo("prefix_accd"));
    }

    // see #2817
    public void testStopwordsOnlyPhraseSuggest() throws IOException {
        assertAcked(
            prepareCreate("test").addMapping("typ1", "body", "type=text,analyzer=stopwd")
                .setSettings(
                    Settings.builder()
                        .put("index.analysis.analyzer.stopwd.tokenizer", "standard")
                        .putList("index.analysis.analyzer.stopwd.filter", "stop")
                )
        );
        ensureGreen();
        index("test", "typ1", "1", "body", "this is a test");
        refresh();

        Suggest searchSuggest = searchSuggest(
            "a an the",
            "simple_phrase",
            phraseSuggestion("body").gramSize(1)
                .addCandidateGenerator(candidateGenerator("body").minWordLength(1).suggestMode("always"))
                .size(1)
        );
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");
    }

    public void testPrefixLength() throws IOException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(SETTING_NUMBER_OF_SHARDS, 1)
                .put("index.analysis.analyzer.body.tokenizer", "standard")
                .putList("index.analysis.analyzer.body.filter", "lowercase")
                .put("index.analysis.analyzer.bigram.tokenizer", "standard")
                .putList("index.analysis.analyzer.bigram.filter", "my_shingle", "lowercase")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", false)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 2)
        );
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("body")
            .field("type", "text")
            .field("analyzer", "body")
            .endObject()
            .startObject("bigram")
            .field("type", "text")
            .field("analyzer", "bigram")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        index("test", "type1", "1", "body", "hello world");
        index("test", "type1", "2", "body", "hello world");
        index("test", "type1", "3", "body", "hello words");
        refresh();

        Suggest searchSuggest = searchSuggest(
            "hello word",
            "simple_phrase",
            phraseSuggestion("body").addCandidateGenerator(
                candidateGenerator("body").prefixLength(4).minWordLength(1).suggestMode("always")
            ).size(1).confidence(1.0f)
        );
        assertSuggestion(searchSuggest, 0, "simple_phrase", "hello words");

        searchSuggest = searchSuggest(
            "hello word",
            "simple_phrase",
            phraseSuggestion("body").addCandidateGenerator(
                candidateGenerator("body").prefixLength(2).minWordLength(1).suggestMode("always")
            ).size(1).confidence(1.0f)
        );
        assertSuggestion(searchSuggest, 0, "simple_phrase", "hello world");
    }

    public void testBasicPhraseSuggest() throws IOException, URISyntaxException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put("index.analysis.analyzer.body.tokenizer", "standard")
                .putList("index.analysis.analyzer.body.filter", "lowercase")
                .put("index.analysis.analyzer.bigram.tokenizer", "standard")
                .putList("index.analysis.analyzer.bigram.filter", "my_shingle", "lowercase")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", false)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 2)
                .put("index.number_of_shards", 1)
        );
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("body")
            .field("type", "text")
            .field("analyzer", "body")
            .endObject()
            .startObject("bigram")
            .field("type", "text")
            .field("analyzer", "bigram")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        String[] strings = new String[] {
            "Arthur, King of the Britons",
            "Sir Lancelot the Brave",
            "Patsy, Arthur's Servant",
            "Sir Robin the Not-Quite-So-Brave-as-Sir-Lancelot",
            "Sir Bedevere the Wise",
            "Sir Galahad the Pure",
            "Miss Islington, the Witch",
            "Zoot",
            "Leader of Robin's Minstrels",
            "Old Crone",
            "Frank, the Historian",
            "Frank's Wife",
            "Dr. Piglet",
            "Dr. Winston",
            "Sir Robin (Stand-in)",
            "Knight Who Says Ni",
            "Police sergeant who stops the film", };
        for (String line : strings) {
            index("test", "type1", line, "body", line, "bigram", line);
        }
        refresh();

        PhraseSuggestionBuilder phraseSuggest = phraseSuggestion("bigram").gramSize(2)
            .analyzer("body")
            .addCandidateGenerator(candidateGenerator("body").minWordLength(1).suggestMode("always"))
            .size(1);
        Suggest searchSuggest = searchSuggest("Frank's Wise", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "frank's wife");
        assertThat(searchSuggest.getSuggestion("simple_phrase").getEntries().get(0).getText().string(), equalTo("Frank's Wise"));

        phraseSuggest.realWordErrorLikelihood(0.95f);
        searchSuggest = searchSuggest("Artur, Kinh of the Britons", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");
        // Check the "text" field this one time.
        assertThat(
            searchSuggest.getSuggestion("simple_phrase").getEntries().get(0).getText().string(),
            equalTo("Artur, Kinh of the Britons")
        );

        // Ask for highlighting
        phraseSuggest.highlight("<em>", "</em>");
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");
        assertThat(
            searchSuggest.getSuggestion("simple_phrase").getEntries().get(0).getOptions().get(0).getHighlighted().string(),
            equalTo("<em>arthur</em> king of the <em>britons</em>")
        );

        // pass in a correct phrase
        phraseSuggest.highlight(null, null).confidence(0f).size(1).maxErrors(0.5f);
        searchSuggest = searchSuggest("Arthur, King of the Britons", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        // pass in a correct phrase - set confidence to 2
        phraseSuggest.confidence(2f);
        searchSuggest = searchSuggest("Arthur, King of the Britons", "simple_phrase", phraseSuggest);
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");

        // pass in a correct phrase - set confidence to 0.99
        phraseSuggest.confidence(0.99f);
        searchSuggest = searchSuggest("Arthur, King of the Britons", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        // set all mass to trigrams (not indexed)
        phraseSuggest.clearCandidateGenerators()
            .addCandidateGenerator(candidateGenerator("body").minWordLength(1).suggestMode("always"))
            .smoothingModel(new LinearInterpolation(1, 0, 0));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");

        // set all mass to bigrams
        phraseSuggest.smoothingModel(new LinearInterpolation(0, 1, 0));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        // distribute mass
        phraseSuggest.smoothingModel(new LinearInterpolation(0.4, 0.4, 0.2));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        searchSuggest = searchSuggest("Frank's Wise", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "frank's wife");

        // try all smoothing methods
        phraseSuggest.smoothingModel(new LinearInterpolation(0.4, 0.4, 0.2));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        phraseSuggest.smoothingModel(new Laplace(0.2));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        phraseSuggest.smoothingModel(new StupidBackoff(0.1));
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "arthur king of the britons");

        // check tokenLimit
        phraseSuggest.smoothingModel(null).tokenLimit(4);
        searchSuggest = searchSuggest("Artur, King of the Britns", "simple_phrase", phraseSuggest);
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");

        phraseSuggest.tokenLimit(15).smoothingModel(new StupidBackoff(0.1));
        searchSuggest = searchSuggest("Sir Bedever the Wife Sir Bedever the Wife Sir Bedever the Wife", "simple_phrase", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "sir bedevere the wise sir bedevere the wise sir bedevere the wise");
        // Check the name this time because we're repeating it which is funky
        assertThat(
            searchSuggest.getSuggestion("simple_phrase").getEntries().get(0).getText().string(),
            equalTo("Sir Bedever the Wife Sir Bedever the Wife Sir Bedever the Wife")
        );
    }

    public void testSizeParam() throws IOException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(SETTING_NUMBER_OF_SHARDS, 1)
                .put("index.analysis.analyzer.body.tokenizer", "standard")
                .putList("index.analysis.analyzer.body.filter", "lowercase")
                .put("index.analysis.analyzer.bigram.tokenizer", "standard")
                .putList("index.analysis.analyzer.bigram.filter", "my_shingle", "lowercase")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", false)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 2)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("body")
            .field("type", "text")
            .field("analyzer", "body")
            .endObject()
            .startObject("bigram")
            .field("type", "text")
            .field("analyzer", "bigram")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        String line = "xorr the god jewel";
        index("test", "type1", "1", "body", line, "bigram", line);
        line = "I got it this time";
        index("test", "type1", "2", "body", line, "bigram", line);
        refresh();

        PhraseSuggestionBuilder phraseSuggestion = phraseSuggestion("bigram").realWordErrorLikelihood(0.95f)
            .gramSize(2)
            .analyzer("body")
            .addCandidateGenerator(candidateGenerator("body").minWordLength(1).prefixLength(1).suggestMode("always").size(1).accuracy(0.1f))
            .smoothingModel(new StupidBackoff(0.1))
            .maxErrors(1.0f)
            .size(5);
        Suggest searchSuggest = searchSuggest("Xorr the Gut-Jewel", "simple_phrase", phraseSuggestion);
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");

        // we allow a size of 2 now on the shard generator level so "god" will be found since it's LD2
        phraseSuggestion.clearCandidateGenerators()
            .addCandidateGenerator(
                candidateGenerator("body").minWordLength(1).prefixLength(1).suggestMode("always").size(2).accuracy(0.1f)
            );
        searchSuggest = searchSuggest("Xorr the Gut-Jewel", "simple_phrase", phraseSuggestion);
        assertSuggestion(searchSuggest, 0, "simple_phrase", "xorr the god jewel");
    }

    public void testDifferentShardSize() throws Exception {
        createIndex("test");
        ensureGreen();
        indexRandom(
            true,
            client().prepareIndex("test").setId("1").setSource("field1", "foobar1").setRouting("1"),
            client().prepareIndex("test").setId("2").setSource("field1", "foobar2").setRouting("2"),
            client().prepareIndex("test").setId("3").setSource("field1", "foobar3").setRouting("3")
        );

        Suggest suggest = searchSuggest(
            "foobar",
            "simple",
            termSuggestion("field1").size(10).minDocFreq(0).suggestMode(SuggestMode.ALWAYS)
        );
        OpenSearchAssertions.assertSuggestionSize(suggest, 0, 3, "simple");
    }

    // see #3469
    public void testShardFailures() throws IOException, InterruptedException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put(IndexSettings.MAX_SHINGLE_DIFF_SETTING.getKey(), 4)
                .put("index.analysis.analyzer.suggest.tokenizer", "standard")
                .putList("index.analysis.analyzer.suggest.filter", "lowercase", "shingler")
                .put("index.analysis.filter.shingler.type", "shingle")
                .put("index.analysis.filter.shingler.min_shingle_size", 2)
                .put("index.analysis.filter.shingler.max_shingle_size", 5)
                .put("index.analysis.filter.shingler.output_unigrams", true)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type2")
            .startObject("properties")
            .startObject("name")
            .field("type", "text")
            .field("analyzer", "suggest")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type2", mapping));
        ensureGreen();

        index("test", "type2", "1", "foo", "bar");
        index("test", "type2", "2", "foo", "bar");
        index("test", "type2", "3", "foo", "bar");
        index("test", "type2", "4", "foo", "bar");
        index("test", "type2", "5", "foo", "bar");
        index("test", "type2", "1", "name", "Just testing the suggestions api");
        index("test", "type2", "2", "name", "An other title about equal length");
        // Note that the last document has to have about the same length as the other or cutoff rechecking will remove the useful suggestion
        refresh();

        // When searching on a shard with a non existing mapping, we should fail
        SearchRequestBuilder request = client().prepareSearch()
            .setSize(0)
            .suggest(
                new SuggestBuilder().setGlobalText("tetsting sugestion")
                    .addSuggestion("did_you_mean", phraseSuggestion("fielddoesnotexist").maxErrors(5.0f))
            );
        assertRequestBuilderThrows(request, SearchPhaseExecutionException.class);

        // When searching on a shard which does not hold yet any document of an existing type, we should not fail
        SearchResponse searchResponse = client().prepareSearch()
            .setSize(0)
            .suggest(
                new SuggestBuilder().setGlobalText("tetsting sugestion")
                    .addSuggestion("did_you_mean", phraseSuggestion("name").maxErrors(5.0f))
            )
            .get();
        OpenSearchAssertions.assertNoFailures(searchResponse);
        OpenSearchAssertions.assertSuggestion(searchResponse.getSuggest(), 0, 0, "did_you_mean", "testing suggestions");
    }

    // see #3469
    public void testEmptyShards() throws IOException, InterruptedException {
        XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("name")
            .field("type", "text")
            .field("analyzer", "suggest")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(
            prepareCreate("test").setSettings(
                Settings.builder()
                    .put(indexSettings())
                    .put(IndexSettings.MAX_SHINGLE_DIFF_SETTING.getKey(), 4)
                    .put("index.refresh_interval", -1)  // prevents occasional scoring glitches due to multi segments
                    .put("index.analysis.analyzer.suggest.tokenizer", "standard")
                    .putList("index.analysis.analyzer.suggest.filter", "lowercase", "shingler")
                    .put("index.analysis.filter.shingler.type", "shingle")
                    .put("index.analysis.filter.shingler.min_shingle_size", 2)
                    .put("index.analysis.filter.shingler.max_shingle_size", 5)
                    .put("index.analysis.filter.shingler.output_unigrams", true)
            ).addMapping("type1", mappingBuilder)
        );
        ensureGreen();

        // test phrase suggestion on completely empty index
        SearchResponse searchResponse = client().prepareSearch()
            .setSize(0)
            .suggest(
                new SuggestBuilder().setGlobalText("tetsting sugestion")
                    .addSuggestion("did_you_mean", phraseSuggestion("name").maxErrors(5.0f))
            )
            .get();

        assertNoFailures(searchResponse);
        Suggest suggest = searchResponse.getSuggest();
        assertSuggestionSize(suggest, 0, 0, "did_you_mean");
        assertThat(suggest.getSuggestion("did_you_mean").getEntries().get(0).getText().string(), equalTo("tetsting sugestion"));

        index("test", "type1", "11", "foo", "bar");
        index("test", "type1", "12", "foo", "bar");
        index("test", "type1", "2", "name", "An other title about equal length");
        refresh();

        // test phrase suggestion but nothing matches
        searchResponse = client().prepareSearch()
            .setSize(0)
            .suggest(
                new SuggestBuilder().setGlobalText("tetsting sugestion")
                    .addSuggestion("did_you_mean", phraseSuggestion("name").maxErrors(5.0f))
            )
            .get();

        assertNoFailures(searchResponse);
        suggest = searchResponse.getSuggest();
        assertSuggestionSize(suggest, 0, 0, "did_you_mean");
        assertThat(suggest.getSuggestion("did_you_mean").getEntries().get(0).getText().string(), equalTo("tetsting sugestion"));

        // finally indexing a document that will produce some meaningful suggestion
        index("test", "type1", "1", "name", "Just testing the suggestions api");
        refresh();

        searchResponse = client().prepareSearch()
            .setSize(0)
            .suggest(
                new SuggestBuilder().setGlobalText("tetsting sugestion")
                    .addSuggestion("did_you_mean", phraseSuggestion("name").maxErrors(5.0f))
            )
            .get();

        assertNoFailures(searchResponse);
        suggest = searchResponse.getSuggest();
        assertSuggestionSize(suggest, 0, 3, "did_you_mean");
        assertSuggestion(suggest, 0, 0, "did_you_mean", "testing suggestions");
    }

    /**
     * Searching for a rare phrase shouldn't provide any suggestions if confidence &gt; 1.  This was possible before we rechecked the cutoff
     * score during the reduce phase.  Failures don't occur every time - maybe two out of five tries but we don't repeat it to save time.
     */
    public void testSearchForRarePhrase() throws IOException {
        // If there isn't enough chaf per shard then shards can become unbalanced, making the cutoff recheck this is testing do more harm
        // then good.
        int chafPerShard = 100;

        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put("index.analysis.analyzer.body.tokenizer", "standard")
                .putList("index.analysis.analyzer.body.filter", "lowercase", "my_shingle")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", true)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 2)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("body")
            .field("type", "text")
            .field("analyzer", "body")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        NumShards test = getNumShards("test");

        List<String> phrases = new ArrayList<>();
        Collections.addAll(phrases, "nobel prize", "noble gases", "somethingelse prize", "pride and joy", "notes are fun");
        for (int i = 0; i < 8; i++) {
            phrases.add("noble somethingelse" + i);
        }
        for (int i = 0; i < test.numPrimaries * chafPerShard; i++) {
            phrases.add("chaff" + i);
        }
        for (String phrase : phrases) {
            index("test", "type1", phrase, "body", phrase);
        }
        refresh();

        Suggest searchSuggest = searchSuggest(
            "nobel prize",
            "simple_phrase",
            phraseSuggestion("body").addCandidateGenerator(
                candidateGenerator("body").minWordLength(1).suggestMode("always").maxTermFreq(.99f)
            ).confidence(2f).maxErrors(5f).size(1)
        );
        assertSuggestionSize(searchSuggest, 0, 0, "simple_phrase");

        searchSuggest = searchSuggest(
            "noble prize",
            "simple_phrase",
            phraseSuggestion("body").addCandidateGenerator(
                candidateGenerator("body").minWordLength(1).suggestMode("always").maxTermFreq(.99f)
            ).confidence(2f).maxErrors(5f).size(1)
        );
        assertSuggestion(searchSuggest, 0, 0, "simple_phrase", "nobel prize");
    }

    public void testSuggestWithManyCandidates() throws InterruptedException, ExecutionException, IOException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put(SETTING_NUMBER_OF_SHARDS, 1) // A single shard will help to keep the tests repeatable.
                .put("index.analysis.analyzer.text.tokenizer", "standard")
                .putList("index.analysis.analyzer.text.filter", "lowercase", "my_shingle")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", true)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 3)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("title")
            .field("type", "text")
            .field("analyzer", "text")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        List<String> titles = new ArrayList<>();

        // We're going to be searching for:
        // united states house of representatives elections in washington 2006
        // But we need to make sure we generate a ton of suggestions so we add a bunch of candidates.
        // Many of these candidates are drawn from page names on English Wikipedia.

        // Tons of different options very near the exact query term
        titles.add("United States House of Representatives Elections in Washington 1789");
        for (int year = 1790; year < 2014; year += 2) {
            titles.add("United States House of Representatives Elections in Washington " + year);
        }
        // Six of these are near enough to be viable suggestions, just not the top one

        // But we can't stop there! Titles that are just a year are pretty common so lets just add one per year
        // since 0. Why not?
        for (int year = 0; year < 2015; year++) {
            titles.add(Integer.toString(year));
        }
        // That ought to provide more less good candidates for the last term

        // Now remove or add plural copies of every term we can
        titles.add("State");
        titles.add("Houses of Parliament");
        titles.add("Representative Government");
        titles.add("Election");

        // Now some possessive
        titles.add("Washington's Birthday");

        // And some conjugation
        titles.add("Unified Modeling Language");
        titles.add("Unite Against Fascism");
        titles.add("Stated Income Tax");
        titles.add("Media organizations housed within colleges");

        // And other stuff
        titles.add("Untied shoelaces");
        titles.add("Unit circle");
        titles.add("Untitled");
        titles.add("Unicef");
        titles.add("Unrated");
        titles.add("UniRed");
        titles.add("Jalan Uniten–Dengkil"); // Highway in Malaysia
        titles.add("UNITAS");
        titles.add("UNITER");
        titles.add("Un-Led-Ed");
        titles.add("STATS LLC");
        titles.add("Staples");
        titles.add("Skates");
        titles.add("Statues of the Liberators");
        titles.add("Staten Island");
        titles.add("Statens Museum for Kunst");
        titles.add("Hause"); // The last name or the German word, whichever.
        titles.add("Hose");
        titles.add("Hoses");
        titles.add("Howse Peak");
        titles.add("The Hoose-Gow");
        titles.add("Hooser");
        titles.add("Electron");
        titles.add("Electors");
        titles.add("Evictions");
        titles.add("Coronal mass ejection");
        titles.add("Wasington"); // A film?
        titles.add("Warrington"); // A town in England
        titles.add("Waddington"); // Lots of places have this name
        titles.add("Watlington"); // Ditto
        titles.add("Waplington"); // Yup, also a town
        titles.add("Washing of the Spears"); // Book

        for (char c = 'A'; c <= 'Z'; c++) {
            // Can't forget lists, glorious lists!
            titles.add("List of former members of the United States House of Representatives (" + c + ")");

            // Lots of people are named Washington <Middle Initial>. LastName
            titles.add("Washington " + c + ". Lastname");

            // Lets just add some more to be evil
            titles.add("United " + c);
            titles.add("States " + c);
            titles.add("House " + c);
            titles.add("Elections " + c);
            titles.add("2006 " + c);
            titles.add(c + " United");
            titles.add(c + " States");
            titles.add(c + " House");
            titles.add(c + " Elections");
            titles.add(c + " 2006");
        }

        List<IndexRequestBuilder> builders = new ArrayList<>();
        for (String title : titles) {
            builders.add(client().prepareIndex("test").setSource("title", title));
        }

        indexRandom(true, builders);

        PhraseSuggestionBuilder suggest = phraseSuggestion("title").addCandidateGenerator(
            candidateGenerator("title").suggestMode("always")
                .maxTermFreq(.99f)
                .size(1000) // Setting a silly high size helps of generate a larger list of candidates for testing.
                .maxInspections(1000) // This too
        ).confidence(0f).maxErrors(2f).shardSize(30000).size(30000);
        Suggest searchSuggest = searchSuggest("united states house of representatives elections in washington 2006", "title", suggest);
        assertSuggestion(searchSuggest, 0, 0, "title", "united states house of representatives elections in washington 2006");
        assertSuggestionSize(searchSuggest, 0, 25480, "title");  // Just to prove that we've run through a ton of options

        suggest.size(1);
        searchSuggest = searchSuggest("united states house of representatives elections in washington 2006", "title", suggest);
        assertSuggestion(searchSuggest, 0, 0, "title", "united states house of representatives elections in washington 2006");
    }

    public void testSuggestWithFieldAlias() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type")
            .startObject("properties")
            .startObject("text")
            .field("type", "keyword")
            .endObject()
            .startObject("alias")
            .field("type", "alias")
            .field("path", "text")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(prepareCreate("test").addMapping("type", mapping));

        List<IndexRequestBuilder> builders = new ArrayList<>();
        builders.add(client().prepareIndex("test").setSource("text", "apple"));
        builders.add(client().prepareIndex("test").setSource("text", "mango"));
        builders.add(client().prepareIndex("test").setSource("text", "papaya"));
        indexRandom(true, false, builders);

        TermSuggestionBuilder termSuggest = termSuggestion("alias").text("appple");

        Suggest searchSuggest = searchSuggest("suggestion", termSuggest);
        assertSuggestion(searchSuggest, 0, "suggestion", "apple");
    }

    public void testPhraseSuggestMinDocFreq() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type")
            .startObject("properties")
            .startObject("text")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(
            prepareCreate("test").setSettings(Settings.builder().put("index.number_of_shards", 1).build()).addMapping("type", mapping)
        );

        List<IndexRequestBuilder> builders = new ArrayList<>();
        builders.add(client().prepareIndex("test").setSource("text", "apple"));
        builders.add(client().prepareIndex("test").setSource("text", "apple"));
        builders.add(client().prepareIndex("test").setSource("text", "apple"));
        builders.add(client().prepareIndex("test").setSource("text", "appfle"));
        indexRandom(true, false, builders);

        PhraseSuggestionBuilder phraseSuggest = phraseSuggestion("text").text("appple")
            .size(2)
            .addCandidateGenerator(new DirectCandidateGeneratorBuilder("text").suggestMode("popular"));

        Suggest searchSuggest = searchSuggest("suggestion", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "suggestion", 2, "apple", "appfle");

        phraseSuggest = phraseSuggestion("text").text("appple")
            .addCandidateGenerator(new DirectCandidateGeneratorBuilder("text").suggestMode("popular").minDocFreq(2));

        searchSuggest = searchSuggest("suggestion", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "suggestion", 1, "apple");

        phraseSuggest = phraseSuggestion("text").text("appple")
            .addCandidateGenerator(new DirectCandidateGeneratorBuilder("text").suggestMode("popular").minDocFreq(2));
        searchSuggest = searchSuggest("suggestion", phraseSuggest);
        assertSuggestion(searchSuggest, 0, "suggestion", 1, "apple");
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(DummyTemplatePlugin.class);
    }

    public static class DummyTemplatePlugin extends Plugin implements ScriptPlugin {
        @Override
        public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
            return new DummyTemplateScriptEngine();
        }
    }

    public static class DummyTemplateScriptEngine implements ScriptEngine {

        // The collate query setter is hard coded to use mustache, so lets lie in this test about the script plugin,
        // which makes the collate code thinks mustache is evaluating the query.
        public static final String NAME = "mustache";

        @Override
        public String getType() {
            return NAME;
        }

        @Override
        public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {
            if (context.instanceClazz != TemplateScript.class) {
                throw new UnsupportedOperationException();
            }
            TemplateScript.Factory factory = p -> {
                String script = scriptSource;
                for (Entry<String, Object> entry : p.entrySet()) {
                    script = script.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
                }
                String result = script;
                return new TemplateScript(null) {
                    @Override
                    public String execute() {
                        return result;
                    }
                };
            };
            return context.factoryClazz.cast(factory);
        }

        @Override
        public Set<ScriptContext<?>> getSupportedContexts() {
            return Collections.singleton(TemplateScript.CONTEXT);
        }
    }

    public void testPhraseSuggesterCollate() throws InterruptedException, ExecutionException, IOException {
        CreateIndexRequestBuilder builder = prepareCreate("test").setSettings(
            Settings.builder()
                .put(indexSettings())
                .put(SETTING_NUMBER_OF_SHARDS, 1) // A single shard will help to keep the tests repeatable.
                .put("index.analysis.analyzer.text.tokenizer", "standard")
                .putList("index.analysis.analyzer.text.filter", "lowercase", "my_shingle")
                .put("index.analysis.filter.my_shingle.type", "shingle")
                .put("index.analysis.filter.my_shingle.output_unigrams", true)
                .put("index.analysis.filter.my_shingle.min_shingle_size", 2)
                .put("index.analysis.filter.my_shingle.max_shingle_size", 3)
        );

        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("type1")
            .startObject("properties")
            .startObject("title")
            .field("type", "text")
            .field("analyzer", "text")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(builder.addMapping("type1", mapping));
        ensureGreen();

        List<String> titles = new ArrayList<>();

        titles.add("United States House of Representatives Elections in Washington 2006");
        titles.add("United States House of Representatives Elections in Washington 2005");
        titles.add("State");
        titles.add("Houses of Parliament");
        titles.add("Representative Government");
        titles.add("Election");

        List<IndexRequestBuilder> builders = new ArrayList<>();
        for (String title : titles) {
            builders.add(client().prepareIndex("test").setSource("title", title));
        }
        indexRandom(true, builders);

        // suggest without collate
        PhraseSuggestionBuilder suggest = phraseSuggestion("title").addCandidateGenerator(
            new DirectCandidateGeneratorBuilder("title").suggestMode("always").maxTermFreq(.99f).size(10).maxInspections(200)
        ).confidence(0f).maxErrors(2f).shardSize(30000).size(10);
        Suggest searchSuggest = searchSuggest("united states house of representatives elections in washington 2006", "title", suggest);
        assertSuggestionSize(searchSuggest, 0, 10, "title");

        // suggest with collate
        String filterString = Strings.toString(
            XContentFactory.jsonBuilder()
                .startObject()
                .startObject("match_phrase")
                .field("{{field}}", "{{suggestion}}")
                .endObject()
                .endObject()
        );
        PhraseSuggestionBuilder filteredQuerySuggest = suggest.collateQuery(filterString);
        filteredQuerySuggest.collateParams(Collections.singletonMap("field", "title"));
        searchSuggest = searchSuggest("united states house of representatives elections in washington 2006", "title", filteredQuerySuggest);
        assertSuggestionSize(searchSuggest, 0, 2, "title");

        // collate suggest with no result (boundary case)
        searchSuggest = searchSuggest("Elections of Representatives Parliament", "title", filteredQuerySuggest);
        assertSuggestionSize(searchSuggest, 0, 0, "title");

        NumShards numShards = getNumShards("test");

        // collate suggest with bad query
        String incorrectFilterString = Strings.toString(
            XContentFactory.jsonBuilder().startObject().startObject("test").field("title", "{{suggestion}}").endObject().endObject()
        );
        PhraseSuggestionBuilder incorrectFilteredSuggest = suggest.collateQuery(incorrectFilterString);
        Map<String, SuggestionBuilder<?>> namedSuggestion = new HashMap<>();
        namedSuggestion.put("my_title_suggestion", incorrectFilteredSuggest);
        try {
            searchSuggest("united states house of representatives elections in washington 2006", numShards.numPrimaries, namedSuggestion);
            fail("Post query error has been swallowed");
        } catch (OpenSearchException e) {
            // expected
        }

        // suggest with collation
        String filterStringAsFilter = Strings.toString(
            XContentFactory.jsonBuilder().startObject().startObject("match_phrase").field("title", "{{suggestion}}").endObject().endObject()
        );

        PhraseSuggestionBuilder filteredFilterSuggest = suggest.collateQuery(filterStringAsFilter);
        searchSuggest = searchSuggest(
            "united states house of representatives elections in washington 2006",
            "title",
            filteredFilterSuggest
        );
        assertSuggestionSize(searchSuggest, 0, 2, "title");

        // collate suggest with bad query
        String filterStr = Strings.toString(
            XContentFactory.jsonBuilder().startObject().startObject("pprefix").field("title", "{{suggestion}}").endObject().endObject()
        );

        suggest.collateQuery(filterStr);
        try {
            searchSuggest("united states house of representatives elections in washington 2006", numShards.numPrimaries, namedSuggestion);
            fail("Post filter error has been swallowed");
        } catch (OpenSearchException e) {
            // expected
        }

        // collate script failure due to no additional params
        String collateWithParams = Strings.toString(
            XContentFactory.jsonBuilder()
                .startObject()
                .startObject("{{query_type}}")
                .field("{{query_field}}", "{{suggestion}}")
                .endObject()
                .endObject()
        );

        try {
            searchSuggest("united states house of representatives elections in washington 2006", numShards.numPrimaries, namedSuggestion);
            fail("Malformed query (lack of additional params) should fail");
        } catch (OpenSearchException e) {
            // expected
        }

        // collate script with additional params
        Map<String, Object> params = new HashMap<>();
        params.put("query_type", "match_phrase");
        params.put("query_field", "title");

        PhraseSuggestionBuilder phraseSuggestWithParams = suggest.collateQuery(collateWithParams).collateParams(params);
        searchSuggest = searchSuggest(
            "united states house of representatives elections in washington 2006",
            "title",
            phraseSuggestWithParams
        );
        assertSuggestionSize(searchSuggest, 0, 2, "title");

        // collate query request with prune set to true
        PhraseSuggestionBuilder phraseSuggestWithParamsAndReturn = suggest.collateQuery(collateWithParams)
            .collateParams(params)
            .collatePrune(true);
        searchSuggest = searchSuggest(
            "united states house of representatives elections in washington 2006",
            "title",
            phraseSuggestWithParamsAndReturn
        );
        assertSuggestionSize(searchSuggest, 0, 10, "title");
        assertSuggestionPhraseCollateMatchExists(searchSuggest, "title", 2);
    }

    protected Suggest searchSuggest(String name, SuggestionBuilder<?> suggestion) {
        return searchSuggest(null, name, suggestion);
    }

    protected Suggest searchSuggest(String suggestText, String name, SuggestionBuilder<?> suggestion) {
        Map<String, SuggestionBuilder<?>> map = new HashMap<>();
        map.put(name, suggestion);
        return searchSuggest(suggestText, 0, map);
    }

    protected Suggest searchSuggest(String suggestText, int expectShardsFailed, Map<String, SuggestionBuilder<?>> suggestions) {
        SearchRequestBuilder builder = client().prepareSearch().setSize(0);
        SuggestBuilder suggestBuilder = new SuggestBuilder();
        if (suggestText != null) {
            suggestBuilder.setGlobalText(suggestText);
        }
        for (Entry<String, SuggestionBuilder<?>> suggestion : suggestions.entrySet()) {
            suggestBuilder.addSuggestion(suggestion.getKey(), suggestion.getValue());
        }
        builder.suggest(suggestBuilder);
        SearchResponse actionGet = builder.get();
        assertThat(Arrays.toString(actionGet.getShardFailures()), actionGet.getFailedShards(), equalTo(expectShardsFailed));
        return actionGet.getSuggest();
    }
}

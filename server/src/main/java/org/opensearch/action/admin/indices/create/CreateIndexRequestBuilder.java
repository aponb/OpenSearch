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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.action.admin.indices.create;

import org.opensearch.action.admin.indices.alias.Alias;
import org.opensearch.action.support.ActiveShardCount;
import org.opensearch.action.support.master.AcknowledgedRequestBuilder;
import org.opensearch.client.OpenSearchClient;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentType;

import java.util.Map;

/**
 * Builder for a create index request
 */
public class CreateIndexRequestBuilder extends AcknowledgedRequestBuilder<
    CreateIndexRequest,
    CreateIndexResponse,
    CreateIndexRequestBuilder> {

    public CreateIndexRequestBuilder(OpenSearchClient client, CreateIndexAction action) {
        super(client, action, new CreateIndexRequest());
    }

    public CreateIndexRequestBuilder(OpenSearchClient client, CreateIndexAction action, String index) {
        super(client, action, new CreateIndexRequest(index));
    }

    /**
     * Sets the name of the index to be created
     */
    public CreateIndexRequestBuilder setIndex(String index) {
        request.index(index);
        return this;
    }

    /**
     * The settings to create the index with.
     */
    public CreateIndexRequestBuilder setSettings(Settings settings) {
        request.settings(settings);
        return this;
    }

    /**
     * The settings to create the index with.
     */
    public CreateIndexRequestBuilder setSettings(Settings.Builder settings) {
        request.settings(settings);
        return this;
    }

    /**
     * Allows to set the settings using a json builder.
     */
    public CreateIndexRequestBuilder setSettings(XContentBuilder builder) {
        request.settings(builder);
        return this;
    }

    /**
     * The settings to create the index with (either json or yaml format)
     */
    public CreateIndexRequestBuilder setSettings(String source, XContentType xContentType) {
        request.settings(source, xContentType);
        return this;
    }

    /**
     * The settings to create the index with (either json/yaml/properties format)
     */
    public CreateIndexRequestBuilder setSettings(Map<String, ?> source) {
        request.settings(source);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     * @param xContentType The content type of the source
     */
    @Deprecated
    public CreateIndexRequestBuilder addMapping(String type, String source, XContentType xContentType) {
        request.mapping(type, source, xContentType);
        return this;
    }

    /**
     * The cause for this index creation.
     */
    public CreateIndexRequestBuilder setCause(String cause) {
        request.cause(cause);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     * @deprecated types are being removed
     */
    @Deprecated
    public CreateIndexRequestBuilder addMapping(String type, XContentBuilder source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     * @deprecated types are being removed
     */
    @Deprecated
    public CreateIndexRequestBuilder addMapping(String type, Map<String, Object> source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * A specialized simplified mapping source method, takes the form of simple properties definition:
     * ("field1", "type=string,store=true").
     * @deprecated types are being removed
     */
    @Deprecated
    public CreateIndexRequestBuilder addMapping(String type, Object... source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequestBuilder setAliases(Map<String, ?> source) {
        request.aliases(source);
        return this;
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequestBuilder setAliases(String source) {
        request.aliases(source);
        return this;
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequestBuilder setAliases(XContentBuilder source) {
        request.aliases(source);
        return this;
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequestBuilder setAliases(BytesReference source) {
        request.aliases(source);
        return this;
    }

    /**
     * Adds an alias that will be associated with the index when it gets created
     */
    public CreateIndexRequestBuilder addAlias(Alias alias) {
        request.alias(alias);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(String source, XContentType xContentType) {
        request.source(source, xContentType);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(BytesReference source, XContentType xContentType) {
        request.source(source, xContentType);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(byte[] source, XContentType xContentType) {
        request.source(source, xContentType);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(byte[] source, int offset, int length, XContentType xContentType) {
        request.source(source, offset, length, xContentType);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(Map<String, ?> source) {
        request.source(source, LoggingDeprecationHandler.INSTANCE);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     */
    public CreateIndexRequestBuilder setSource(XContentBuilder source) {
        request.source(source);
        return this;
    }

    /**
     * Sets the number of shard copies that should be active for index creation to return.
     * Defaults to {@link ActiveShardCount#DEFAULT}, which will wait for one shard copy
     * (the primary) to become active. Set this value to {@link ActiveShardCount#ALL} to
     * wait for all shards (primary and all replicas) to be active before returning.
     * Otherwise, use {@link ActiveShardCount#from(int)} to set this value to any
     * non-negative integer, up to the number of copies per shard (number of replicas + 1),
     * to wait for the desired amount of shard copies to become active before returning.
     * Index creation will only wait up until the timeout value for the number of shard copies
     * to be active before returning.  Check {@link CreateIndexResponse#isShardsAcknowledged()} to
     * determine if the requisite shard copies were all started before returning or timing out.
     *
     * @param waitForActiveShards number of active shard copies to wait on
     */
    public CreateIndexRequestBuilder setWaitForActiveShards(ActiveShardCount waitForActiveShards) {
        request.waitForActiveShards(waitForActiveShards);
        return this;
    }

    /**
     * A shortcut for {@link #setWaitForActiveShards(ActiveShardCount)} where the numerical
     * shard count is passed in, instead of having to first call {@link ActiveShardCount#from(int)}
     * to get the ActiveShardCount.
     */
    public CreateIndexRequestBuilder setWaitForActiveShards(final int waitForActiveShards) {
        return setWaitForActiveShards(ActiveShardCount.from(waitForActiveShards));
    }
}

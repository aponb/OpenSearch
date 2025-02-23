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

package org.opensearch.gateway;

import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.cluster.coordination.Coordinator;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.settings.Settings;
import org.opensearch.discovery.Discovery;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.Index;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.InternalTestCluster.RestartCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

@ClusterScope(scope = Scope.TEST, numDataNodes = 0)
public class MetadataNodesIT extends OpenSearchIntegTestCase {
    public void testMetaWrittenAlsoOnDataNode() throws Exception {
        // this test checks that index state is written on data only nodes if they have a shard allocated
        String masterNode = internalCluster().startMasterOnlyNode(Settings.EMPTY);
        String dataNode = internalCluster().startDataOnlyNode(Settings.EMPTY);
        assertAcked(prepareCreate("test").setSettings(Settings.builder().put("index.number_of_replicas", 0)));
        index("test", "_doc", "1", jsonBuilder().startObject().field("text", "some text").endObject());
        ensureGreen("test");
        assertIndexInMetaState(dataNode, "test");
        assertIndexInMetaState(masterNode, "test");
    }

    public void testIndexFilesAreRemovedIfAllShardsFromIndexRemoved() throws Exception {
        // this test checks that the index data is removed from a data only node once all shards have been allocated away from it
        String masterNode = internalCluster().startMasterOnlyNode(Settings.EMPTY);
        List<String> nodeNames = internalCluster().startDataOnlyNodes(2);
        String node1 = nodeNames.get(0);
        String node2 = nodeNames.get(1);

        String index = "index";
        assertAcked(
            prepareCreate(index).setSettings(
                Settings.builder()
                    .put("index.number_of_replicas", 0)
                    .put(IndexMetadata.INDEX_ROUTING_INCLUDE_GROUP_SETTING.getKey() + "_name", node1)
            )
        );
        index(index, "_doc", "1", jsonBuilder().startObject().field("text", "some text").endObject());
        ensureGreen();
        assertIndexInMetaState(node1, index);
        Index resolveIndex = resolveIndex(index);
        assertIndexDirectoryExists(node1, resolveIndex);
        assertIndexDirectoryDeleted(node2, resolveIndex);
        assertIndexInMetaState(masterNode, index);
        assertIndexDirectoryDeleted(masterNode, resolveIndex);

        logger.debug("relocating index...");
        client().admin()
            .indices()
            .prepareUpdateSettings(index)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_ROUTING_INCLUDE_GROUP_SETTING.getKey() + "_name", node2))
            .get();
        client().admin().cluster().prepareHealth().setWaitForNoRelocatingShards(true).get();
        ensureGreen();
        assertIndexDirectoryDeleted(node1, resolveIndex);
        assertIndexInMetaState(node2, index);
        assertIndexDirectoryExists(node2, resolveIndex);
        assertIndexInMetaState(masterNode, index);
        assertIndexDirectoryDeleted(masterNode, resolveIndex);

        client().admin().indices().prepareDelete(index).get();
        assertIndexDirectoryDeleted(node1, resolveIndex);
        assertIndexDirectoryDeleted(node2, resolveIndex);
    }

    @SuppressWarnings("unchecked")
    public void testMetaWrittenWhenIndexIsClosedAndMetaUpdated() throws Exception {
        String masterNode = internalCluster().startMasterOnlyNode(Settings.EMPTY);
        final String dataNode = internalCluster().startDataOnlyNode(Settings.EMPTY);

        final String index = "index";
        assertAcked(prepareCreate(index).setSettings(Settings.builder().put("index.number_of_replicas", 0)));
        logger.info("--> wait for green index");
        ensureGreen();
        logger.info("--> wait for meta state written for index");
        assertIndexInMetaState(dataNode, index);
        assertIndexInMetaState(masterNode, index);

        logger.info("--> close index");
        client().admin().indices().prepareClose(index).get();
        // close the index
        ClusterStateResponse clusterStateResponse = client().admin().cluster().prepareState().get();
        assertThat(clusterStateResponse.getState().getMetadata().index(index).getState().name(), equalTo(IndexMetadata.State.CLOSE.name()));

        // update the mapping. this should cause the new meta data to be written although index is closed
        client().admin()
            .indices()
            .preparePutMapping(index)
            .setSource(
                jsonBuilder().startObject()
                    .startObject("properties")
                    .startObject("integer_field")
                    .field("type", "integer")
                    .endObject()
                    .endObject()
                    .endObject()
            )
            .get();

        GetMappingsResponse getMappingsResponse = client().admin().indices().prepareGetMappings(index).get();
        assertNotNull(
            ((Map<String, ?>) (getMappingsResponse.getMappings().get(index).getSourceAsMap().get("properties"))).get("integer_field")
        );

        // make sure it was also written on red node although index is closed
        ImmutableOpenMap<String, IndexMetadata> indicesMetadata = getIndicesMetadataOnNode(dataNode);
        assertNotNull(
            ((Map<String, ?>) (indicesMetadata.get(index).getMappings().get("_doc").getSourceAsMap().get("properties"))).get(
                "integer_field"
            )
        );
        assertThat(indicesMetadata.get(index).getState(), equalTo(IndexMetadata.State.CLOSE));

        /* Try the same and see if this also works if node was just restarted.
         * Each node holds an array of indices it knows of and checks if it should
         * write new meta data by looking up in this array. We need it because if an
         * index is closed it will not appear in the shard routing and we therefore
         * need to keep track of what we wrote before. However, when the node is
         * restarted this array is empty and we have to fill it before we decide
         * what we write. This is why we explicitly test for it.
         */
        internalCluster().restartNode(dataNode, new RestartCallback());
        client().admin()
            .indices()
            .preparePutMapping(index)
            .setSource(
                jsonBuilder().startObject()
                    .startObject("properties")
                    .startObject("float_field")
                    .field("type", "float")
                    .endObject()
                    .endObject()
                    .endObject()
            )
            .get();

        getMappingsResponse = client().admin().indices().prepareGetMappings(index).get();
        assertNotNull(
            ((Map<String, ?>) (getMappingsResponse.getMappings().get(index).getSourceAsMap().get("properties"))).get("float_field")
        );

        // make sure it was also written on red node although index is closed
        indicesMetadata = getIndicesMetadataOnNode(dataNode);
        assertNotNull(
            ((Map<String, ?>) (indicesMetadata.get(index).getMappings().get("_doc").getSourceAsMap().get("properties"))).get("float_field")
        );
        assertThat(indicesMetadata.get(index).getState(), equalTo(IndexMetadata.State.CLOSE));

        // finally check that meta data is also written of index opened again
        assertAcked(client().admin().indices().prepareOpen(index).get());
        // make sure index is fully initialized and nothing is changed anymore
        ensureGreen();
        indicesMetadata = getIndicesMetadataOnNode(dataNode);
        assertThat(indicesMetadata.get(index).getState(), equalTo(IndexMetadata.State.OPEN));
    }

    protected void assertIndexDirectoryDeleted(final String nodeName, final Index index) throws Exception {
        assertBusy(
            () -> assertFalse(
                "Expecting index directory of " + index + " to be deleted from node " + nodeName,
                indexDirectoryExists(nodeName, index)
            )
        );
    }

    protected void assertIndexDirectoryExists(final String nodeName, final Index index) throws Exception {
        assertBusy(
            () -> assertTrue(
                "Expecting index directory of " + index + " to exist on node " + nodeName,
                indexDirectoryExists(nodeName, index)
            )
        );
    }

    protected void assertIndexInMetaState(final String nodeName, final String indexName) throws Exception {
        assertBusy(() -> {
            try {
                assertTrue(
                    "Expecting meta state of index " + indexName + " to be on node " + nodeName,
                    getIndicesMetadataOnNode(nodeName).containsKey(indexName)
                );
            } catch (Exception e) {
                logger.info("failed to load meta state", e);
                fail("could not load meta state");
            }
        });
    }

    private boolean indexDirectoryExists(String nodeName, Index index) {
        NodeEnvironment nodeEnv = ((InternalTestCluster) cluster()).getInstance(NodeEnvironment.class, nodeName);
        for (Path path : nodeEnv.indexPaths(index)) {
            if (Files.exists(path)) {
                return true;
            }
        }
        return false;
    }

    private ImmutableOpenMap<String, IndexMetadata> getIndicesMetadataOnNode(String nodeName) {
        final Coordinator coordinator = (Coordinator) internalCluster().getInstance(Discovery.class, nodeName);
        return coordinator.getApplierState().getMetadata().getIndices();
    }
}

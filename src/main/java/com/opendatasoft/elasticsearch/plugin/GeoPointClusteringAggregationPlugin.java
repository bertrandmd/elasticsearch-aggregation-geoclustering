package com.opendatasoft.elasticsearch.plugin;

import com.opendatasoft.elasticsearch.rest.RestGeoPointClusteringExpansionAction;
import com.opendatasoft.elasticsearch.rest.RestGeoPointClusteringTileAction;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.GeoPointClusteringAggregationBuilder;
import com.opendatasoft.elasticsearch.search.aggregations.bucket.geopointclustering.InternalGeoPointClustering;

import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class GeoPointClusteringAggregationPlugin extends Plugin implements SearchPlugin, ActionPlugin {
    @Override
    public ArrayList<SearchPlugin.AggregationSpec> getAggregations() {
        ArrayList<SearchPlugin.AggregationSpec> r = new ArrayList<>();

        r.add(
            new AggregationSpec(
                GeoPointClusteringAggregationBuilder.NAME,
                GeoPointClusteringAggregationBuilder::new,
                GeoPointClusteringAggregationBuilder.PARSER
            ).addResultReader(InternalGeoPointClustering::new)
                .setAggregatorRegistrar(GeoPointClusteringAggregationBuilder::registerAggregators)
        );

        return r;
    }

    /**
     * Registers the clustering APIs built on top of the aggregation: vector tiles, ready to be consumed by a map
     * client, and the expansion zoom of a cluster.
     */
    @Override
    public Collection<RestHandler> getRestHandlers(
        Settings settings,
        NamedWriteableRegistry namedWriteableRegistry,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster,
        Predicate<NodeFeature> clusterSupportsFeature
    ) {
        return List.of(new RestGeoPointClusteringTileAction(), new RestGeoPointClusteringExpansionAction());
    }
}

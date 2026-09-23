## Elasticsearch Geo Point clustering aggregation plugin

This plugin extends Elasticsearch with a `geo_point_clustering` aggregation, allowing to fetch [geo_point](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/geo-point.html) documents as clusters of points.
It is very similar to what is done with the official [geohash_grid aggregation](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/search-aggregations-bucket-geohashgrid-aggregation.html) except that final clusters are not bound to the geohash grid.

For example, at zoom level 1 with points across France, `geohash_grid` agg will output 3 clusters stuck to geohash cells u, e, s, while `geo_point_clustering` will merge these clusters into one.
This is done during the reduce phase.

Contrary to `geohash_grid` aggregation, buckets keys are a tuple(centroid, geohash cells) instead of geohash cells only, because one cluster can be linked to several geohash cells, due to the cluster merge process during the reduce phase.
Centroids are built during the shard collect phase.

Please note that [geo_shape data type](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/geo-shape.html) is not supported.


## Usage
### Install
Currently supported Elasticsearch version is 8.x. 

Install plugin with:
`./bin/elasticsearch-plugin install https://github.com/opendatasoft/elasticsearch-aggregation-geoclustering/releases/download/v8.19.19.0/geopoint-clustering-aggregation-8.19.19.0.zip`


### Quickstart
#### Intro
```json
{
  "aggregations": {
    "<aggregation_name>": {
      "geo_point_clustering": {
        "field": "<field_name>",
        "zoom": "<zoom>"
      }
    }
  }
}
```
Input parameters :
- `field`: must be of type [geo_point](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/geo-point.html)
- `zoom`: mandatory integer parameter between 0 and 25. It represents the zoom level used in the request to aggregate geo points
- `radius`: radius in pixel. It is used during the reduce phase to merge close clusters. Default to `40`
- `ratio`: ratio used to make a second merging pass during the reduce phase. If the value is `0`, no second pass is made. Default to `0`
- `extent`: Extent of the tiles. Default to `256`


#### Real-life example

Create an index:
```json
PUT test
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "location": {
        "type": "geo_point"
      }
    }
  }
}
```

Push some points:
```json
POST test/_bulk?refresh
{"index":{"_id":1}}
{"location":[2.454929, 48.821578]}
{"index":{"_id":2}}
{"location":[2.245858, 48.86914]}
{"index":{"_id":3}}
{"location":[2.240358, 48.863481]}
{"index":{"_id":4}}
{"location":[2.25292, 48.847176]}
{"index":{"_id":5}}
{"location":[2.279111, 48.872383]}
{"index":{"_id":6}}
{"location":[2.336267, 48.822021]}
{"index":{"_id":7}}
{"location":[2.338677, 48.822672]}
{"index":{"_id":8}}
{"location":[2.336643, 48.822493]}
{"index":{"_id":9}}
{"location":[2.438465, 48.84204]}
{"index":{"_id":10}}
{"location":[2.381554, 48.835382]}
{"index":{"_id":11}}
{"location":[2.407744, 48.83733]}
{"index":{"_id":12}}
{"location":[2.34521, 48.849358]}
{"index":{"_id":13}}
{"location":[2.252938, 48.846041]}
{"index":{"_id":14}}
{"location":[2.279715, 48.871775]}
{"index":{"_id":15}}
{"location":[2.380629, 48.879757]}
```

Perform an aggregation:
```json
POST test/_search?size=0
{
  "aggregations": {
    "clusters": {
      "geo_point_clustering": {
        "field": "location",
        "zoom": 9
      }}}}
```

Result:
```json
"aggregations" : {
    "clusters" : {
      "buckets" : [
        {
          "geohash_grids" : [
            "u09wn",
            "u09tz",
            "u09ty",
            "u09tx",
            "u09tv",
            "u09tt"
          ],
          "doc_count" : 9,
          "centroid" : {
            "lat" : 48.83695897646248,
            "lon" : 2.380013056099415
          }
        },
        {
          "geohash_grids" : [
            "u09w5",
            "u09tg",
            "u09tf"
          ],
          "doc_count" : 6,
          "centroid" : {
            "lat" : 48.86166598415002,
            "lon" : 2.258483301848173
          }
        }
      ]
    }
```


## Vector tile API

The aggregation gives you clusters, but a map client needs a bit more: tiles, single points rendered as points instead
of clusters of one, and the zoom a cluster breaks apart at. The plugin ships two endpoints doing all of that inside
Elasticsearch, so that no service has to sit between the map and the cluster.

### Clustered tiles

```
GET  /<index>/_geo_point_clustering/_mvt/<field>/<z>/<x>/<y>
POST /<index>/_geo_point_clustering/_mvt/<field>/<z>/<x>/<y>
```

Returns a [Mapbox Vector Tile](https://github.com/mapbox/vector-tile-spec/tree/master/2.1)
(`application/vnd.mapbox-vector-tile`) with two point layers:

| Layer | Content |
| --- | --- |
| `clusters` | one point per cluster of two documents or more |
| `pois` | the documents that are alone in their cluster, and every document above `cluster_max_zoom` |

`clusters` features carry:

| Property | Type | Description |
| --- | --- | --- |
| `cluster` | boolean | always `true`, to style both layers with a single expression |
| `point_count` | integer | number of documents in the cluster |
| `point_count_abbreviated` | string | `1.2k`, `24k`… same rules as supercluster |
| `geohash_grids` | string | comma separated geohash cells of the cluster, to be passed back to the expansion endpoint |
| `expansion_zoom` | integer | zoom to zoom to in order to break the cluster apart (see below) |

`pois` features carry `cluster: false`, the document id (as the vector tile feature id when it is numeric, and always as
the `id` property) and the source fields listed in `fields`.

Every parameter can be given either in the query string or in the request body, so a plain tile URL is enough to drive
the whole thing:

| Parameter | Default | Description |
| --- | --- | --- |
| `radius` | `40` | clustering radius, in `extent` pixels |
| `extent` | `256` | tile size, in pixels, the `radius` is expressed in |
| `ratio` | `0` | second merging pass ratio of the aggregation |
| `cluster_max_zoom` | `16` | above this zoom, clustering is turned off and documents are returned as points |
| `buffer` | `2 * radius` | pixels the queried area is expanded by, so that clusters are not cut by tile borders |
| `mvt_extent` | `4096` | coordinate extent of the returned tile |
| `size` | `10000` | maximum number of clusters per tile |
| `max_hits` | `10000` | maximum number of documents per tile above `cluster_max_zoom` |
| `fields` | none | comma separated source fields to put in the `pois` features |
| `include_id` | `true` | fetch the document ids of single points |
| `expansion_zoom` | `true` | compute the `expansion_zoom` property of the clusters |
| `expansion_zoom_depth` | `4` | how many zoom levels ahead the expansion zoom is looked for |
| `clusters_layer` / `pois_layer` | `clusters` / `pois` | layer names |
| `q` | none | [query string](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-query-string-query.html) filtering the documents, along with the usual `df`, `analyzer`, `analyze_wildcard`, `lenient` and `default_operator` |
| `preference`, `routing` | none | passed to the underlying search |

The request body accepts the same parameters, plus a full `query` (any query DSL) and `runtime_mappings`. Body values
win over query string ones, and `q` and `query` are combined:

```json
POST /pois/_geo_point_clustering/_mvt/location/12/2074/1409
{
  "radius": 60,
  "extent": 512,
  "cluster_max_zoom": 17,
  "fields": ["name", "category_name"],
  "query": {
    "bool": {
      "filter": [
        { "terms": { "category_name": ["bar", "cafe"] } }
      ]
    }
  }
}
```

### Expansion zoom

`expansion_zoom` is the equivalent of supercluster's `getClusterExpansionZoom()`: the first zoom level at which a
cluster breaks into several clusters. Tiles carry it as a cluster property, computed by clustering the same area at the
next `expansion_zoom_depth` zoom levels in the very same search request, so a click handler needs no round trip:

```js
map.on('click', 'clusters', (e) => {
  const cluster = e.features[0];
  map.easeTo({ center: cluster.geometry.coordinates, zoom: cluster.properties.expansion_zoom });
});
```

A cluster that does not break within `expansion_zoom_depth` levels reports the deepest level that was looked at, which
still moves the map closer to the split. A cluster whose absence of split could not be established, because a level
returned more clusters than it was allowed to, carries no `expansion_zoom` at all: an absent property means "ask", not
"does not split". When the exact value is needed, ask for it:

```
GET  /<index>/_geo_point_clustering/_expansion/<field>?zoom=12&cells=u09tz,u09tw
POST /<index>/_geo_point_clustering/_expansion/<field>
```

`cells` holds the `geohash_grids` property of the clicked cluster, as is. `zoom` is the zoom the cluster was rendered
at. `radius`, `extent`, `ratio`, `q` and the body `query` must match the ones the tile was built with; `max_zoom`
(default `18`) bounds the search. The answer is the expansion zoom and the clusters found there:

```json
{
  "zoom": 14,
  "bucket_count": 2,
  "doc_count": 57,
  "clusters": [
    { "geohash_grids": ["u09tz"], "doc_count": 31, "centroid": { "lat": 48.83, "lon": 2.38 } },
    { "geohash_grids": ["u09tw"], "doc_count": 26, "centroid": { "lat": 48.86, "lon": 2.25 } }
  ]
}
```

### Using it from MapLibre GL JS

```js
map.addSource('pois', {
  type: 'vector',
  tiles: [
    'http://localhost:9200/pois/_geo_point_clustering/_mvt/location/{z}/{x}/{y}' +
    '?radius=60&extent=512&cluster_max_zoom=17&fields=name,category_name'
  ],
  maxzoom: 20
});

map.addLayer({ id: 'clusters', type: 'circle', source: 'pois', 'source-layer': 'clusters' });
map.addLayer({
  id: 'cluster-count', type: 'symbol', source: 'pois', 'source-layer': 'clusters',
  layout: { 'text-field': ['get', 'point_count_abbreviated'] }
});
map.addLayer({ id: 'pois', type: 'circle', source: 'pois', 'source-layer': 'pois' });
```

Tiles are filtered by appending `&q=category_name:bar` to the URL, which makes an interactive search a plain source
URL change.

A ready to run test page sits in [`examples/maplibre-clustered-tiles.html`](examples/maplibre-clustered-tiles.html). Open it
with `?es=http://localhost:9200&index=pois&field=location`: it draws both layers, filters them, expands clusters on click
(through the tile property or the expansion endpoint) and can fetch a single tile to tell an authentication problem from a
CORS one. Reaching Elasticsearch straight from a browser needs CORS to be enabled on the nodes:

```yaml
http.cors.enabled: true
http.cors.allow-origin: "*"
http.cors.allow-headers: X-Requested-With, Content-Type, Content-Length, Authorization
```

### Good to know

- Clusters are built from a buffered area around the tile, then rendered by the single tile owning their centroid:
  no cluster is cut by a tile border, and none is drawn twice.
- Clustered tiles are aggregation only searches (`size: 0`), so they go through the shard request cache.
- Above `cluster_max_zoom`, document positions are read from the doc values of the geo field: keep `doc_values`
  enabled on it, which is the default.
- `expansion_zoom` costs one extra aggregation per zoom level looked ahead. Each level holds roughly four times more
  clusters than the one above it, so the levels share a bucket budget instead of each being allowed `size` buckets: a
  tile never asks for much more than twice `size` buckets, whatever the depth, and stays clear of `search.max_buckets`.
  The counterpart is that on a dense area a deep level gets truncated and the clusters it cannot rule out lose their
  `expansion_zoom`; raise `size` to look further, or leave the exact answer to the expansion endpoint on click.
- A deep `expansion_zoom_depth` mostly pays off at high zoom, where a tile holds few documents. At low zoom prefer a
  depth of 2 or 3, or `expansion_zoom=false` plus the expansion endpoint.


## Development environment setup

### Build

Built with Java 17.
Apply spotless code formatting with:
``` shell
./gradlew spotlessApply
```

### Development Environment Setup

Build the plugin using gradle:
``` shell
./gradlew build
```

or
``` shell
./gradlew assemble  # (to avoid the test suite)
```

In case you have to upgrade Gradle, you can do it with `./gradlew wrapper --gradle-version x.y.z`.

Then the following command will start a dockerized ES and will install the previously built plugin:
``` shell
docker compose up
```

You can now check the Elasticsearch instance on `localhost:9200` and the plugin version at `localhost:9200/_cat/plugins`.

Please be careful during development: you'll need to manually rebuild the .zip using `./gradlew build` on each code
change before running `docker compose up` up again.

> NOTE: In `docker-compose.yml` you can uncomment the debug env and attach a REMOTE JVM on `*:5005` to debug the plugin.

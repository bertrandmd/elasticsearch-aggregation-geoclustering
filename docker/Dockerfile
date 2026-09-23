# The version is injected by docker-compose.yml from gradle.properties, so the image
# and the plugin zip never drift apart.
ARG ES_VERSION
ARG PLUGIN_VERSION

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION} AS elasticsearch-plugin-debug

ARG PLUGIN_VERSION
COPY /build/distributions/geopoint-clustering-aggregation-${PLUGIN_VERSION}.zip /tmp/plugin.zip
RUN ./bin/elasticsearch-plugin install --batch file:/tmp/plugin.zip

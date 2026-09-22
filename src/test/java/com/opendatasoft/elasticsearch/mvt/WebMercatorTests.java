package com.opendatasoft.elasticsearch.mvt;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class WebMercatorTests extends ESTestCase {

    public void testWorldCoordinatesRoundTrip() {
        for (int i = 0; i < 100; i++) {
            double lon = randomDoubleBetween(-180, 180, true);
            double lat = randomDoubleBetween(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE, true);
            assertEquals(lon, WebMercator.worldXToLon(WebMercator.lonToWorldX(lon)), 1e-9);
            assertEquals(lat, WebMercator.worldYToLat(WebMercator.latToWorldY(lat)), 1e-9);
        }
    }

    public void testTileBoundsOfTheWorld() {
        double[] bounds = WebMercator.tileBounds(0, 0, 0, 0, 256);
        assertEquals(-180.0, bounds[0], 1e-9);
        assertEquals(-WebMercator.MAX_LATITUDE, bounds[1], 1e-6);
        assertEquals(180.0, bounds[2], 1e-9);
        assertEquals(WebMercator.MAX_LATITUDE, bounds[3], 1e-6);
    }

    public void testTileBoundsAreBuffered() {
        double[] tight = WebMercator.tileBounds(9, 259, 176, 0, 256);
        double[] buffered = WebMercator.tileBounds(9, 259, 176, 80, 256);

        assertThat(buffered[0], lessThan(tight[0]));
        assertThat(buffered[1], lessThan(tight[1]));
        assertThat(buffered[2], greaterThan(tight[2]));
        assertThat(buffered[3], greaterThan(tight[3]));

        // A 80 pixels buffer on a 256 pixels tile widens the tile by 80/256 of its width on each side.
        double tileWidth = tight[2] - tight[0];
        assertEquals(tight[0] - tileWidth * 80 / 256, buffered[0], 1e-9);
    }

    public void testBufferedBoundsWrapAroundTheAntimeridian() {
        double[] bounds = WebMercator.tileBounds(2, 0, 1, 64, 256);
        // The western edge of the world wraps to the eastern one: west is then greater than east.
        assertThat(bounds[0], greaterThan(bounds[2]));
        assertThat(bounds[0], greaterThanOrEqualTo(-180.0));
        assertThat(bounds[2], lessThanOrEqualTo(180.0));
    }

    public void testEveryPointBelongsToExactlyOneTile() {
        int zoom = randomIntBetween(1, 12);
        int tiles = 1 << zoom;
        for (int i = 0; i < 50; i++) {
            double lon = randomDoubleBetween(-180, 180, true);
            double lat = randomDoubleBetween(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE, true);

            int owners = 0;
            int ownerX = -1;
            int ownerY = -1;
            for (int x = 0; x < tiles; x++) {
                for (int y = 0; y < tiles; y++) {
                    if (WebMercator.insideTile(lon, lat, zoom, x, y)) {
                        owners++;
                        ownerX = x;
                        ownerY = y;
                    }
                }
            }
            assertEquals("[" + lon + ", " + lat + "] at zoom " + zoom, 1, owners);

            // The owning tile is also the one the point projects into.
            int[] coordinates = WebMercator.tileCoordinates(lon, lat, zoom, ownerX, ownerY, 4096);
            assertThat(coordinates[0], allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(4096)));
            assertThat(coordinates[1], allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(4096)));
        }
    }

    public void testTileCoordinatesOfKnownPoints() {
        // North west corner of the tile.
        double[] bounds = WebMercator.tileBounds(9, 259, 176, 0, 256);
        int[] northWest = WebMercator.tileCoordinates(bounds[0], bounds[3], 9, 259, 176, 4096);
        assertArrayEquals(new int[] { 0, 0 }, northWest);

        // South east corner, which belongs to the next tile but is still projected at the tile end.
        int[] southEast = WebMercator.tileCoordinates(bounds[2], bounds[1], 9, 259, 176, 4096);
        assertArrayEquals(new int[] { 4096, 4096 }, southEast);
    }
}

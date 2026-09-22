package com.opendatasoft.elasticsearch.mvt;

/**
 * Web Mercator (EPSG:3857) helpers for the XYZ tiling scheme used by map clients.
 * <p>
 * World coordinates are normalized in the [0, 1] range, (0, 0) being the top left corner of the world (north west)
 * and (1, 1) its bottom right corner (south east).
 */
public final class WebMercator {

    /** Latitude bounds of the Web Mercator projection. */
    public static final double MAX_LATITUDE = 85.05112877980659;

    private WebMercator() {
        throw new AssertionError("No instances intended");
    }

    public static double lonToWorldX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    public static double latToWorldY(double lat) {
        double clamped = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
        double sin = Math.sin(Math.toRadians(clamped));
        return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
    }

    public static double worldXToLon(double x) {
        return x * 360.0 - 180.0;
    }

    public static double worldYToLat(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI - 2.0 * Math.PI * y)));
    }

    /**
     * Returns the geographic bounds of a tile, expanded by a pixel buffer.
     *
     * @param zoom        tile zoom level
     * @param x           tile x coordinate
     * @param y           tile y coordinate
     * @param bufferPixels number of pixels the tile is expanded by on each side
     * @param tilePixels  number of pixels a tile is made of, the buffer is expressed in that pixel space
     * @return {@code {west, south, east, north}}, in degrees. West may be greater than east when the bounds cross the
     *         antimeridian; when the buffered bounds cover the whole world, the full [-180, 180] range is returned.
     */
    public static double[] tileBounds(int zoom, int x, int y, double bufferPixels, int tilePixels) {
        double tiles = tileCount(zoom);
        double padding = (bufferPixels / tilePixels) / tiles;

        double left = x / tiles - padding;
        double right = (x + 1) / tiles + padding;
        double top = Math.max(0, y / tiles - padding);
        double bottom = Math.min(1, (y + 1) / tiles + padding);

        double north = worldYToLat(top);
        double south = worldYToLat(bottom);

        if (right - left >= 1) {
            return new double[] { -180.0, south, 180.0, north };
        }
        return new double[] { normalizeLon(worldXToLon(left)), south, normalizeLon(worldXToLon(right)), north };
    }

    /**
     * Returns whether a point belongs to a tile. Points on the eastern and southern edges belong to the next tile, so
     * that a point is always owned by exactly one tile. This is what makes it safe to query a buffered area and still
     * render each cluster or point only once.
     */
    public static boolean insideTile(double lon, double lat, int zoom, int x, int y) {
        double tiles = tileCount(zoom);
        int max = (int) tiles - 1;
        int pointX = (int) Math.min(max, Math.floor(lonToWorldX(lon) * tiles));
        int pointY = (int) Math.min(max, Math.floor(latToWorldY(lat) * tiles));
        return pointX == x && pointY == y;
    }

    /**
     * Projects a point to the tile local coordinate space used by vector tiles: the origin is the top left corner of
     * the tile and the y axis points south.
     *
     * @return {@code {x, y}}, clamped to the [0, extent] range
     */
    public static int[] tileCoordinates(double lon, double lat, int zoom, int x, int y, int extent) {
        double tiles = tileCount(zoom);
        double pointX = (lonToWorldX(lon) * tiles - x) * extent;
        double pointY = (latToWorldY(lat) * tiles - y) * extent;
        return new int[] { clampToExtent(pointX, extent), clampToExtent(pointY, extent) };
    }

    private static int clampToExtent(double value, int extent) {
        return (int) Math.max(0, Math.min(extent, Math.round(value)));
    }

    private static double tileCount(int zoom) {
        return Math.pow(2, zoom);
    }

    /** Wraps a longitude into the [-180, 180] range. */
    private static double normalizeLon(double lon) {
        if (lon >= -180 && lon <= 180) {
            return lon;
        }
        double wrapped = ((lon + 180) % 360 + 360) % 360 - 180;
        return wrapped;
    }
}

package com.openlibrary.map;

import java.util.List;
import java.util.Map;

public final class MapDtos {

    private MapDtos() {
    }

    /** What the 3D view needs to frame the library without walking the whole list. */
    public record Bounds(double minX, double maxX, double minZ, double maxZ,
                         double width, double depth) {
    }

    /**
     * One room, aisle, shelf or depot. Geometry is in metres with the origin at
     * the entrance, already resolved: a location nobody has measured still comes
     * back with a usable position instead of null.
     */
    public record MapNode(
            Long id,
            String code,
            String name,
            String kind,
            Long parentId,
            double x,
            double y,
            double z,
            double width,
            double depth,
            double height,
            int copies,
            Map<String, Integer> occupancy,
            List<MapItem> items) {
    }

    /** A copy sitting on that node, so the map can drill down to the book. */
    public record MapItem(
            Long copyId,
            String code,
            String barcode,
            String status,
            Long bookId,
            String bookTitle) {
    }

    public record MapView(Bounds bounds, List<MapNode> nodes) {
    }
}

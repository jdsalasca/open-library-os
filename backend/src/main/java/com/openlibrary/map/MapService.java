package com.openlibrary.map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openlibrary.inventory.Location;
import com.openlibrary.inventory.LocationKind;
import com.openlibrary.inventory.LocationRepository;

/**
 * Everything the 3D view needs in one call, including the geometry.
 *
 * <p>The important decision lives here and not in the browser: x/y/z are nullable
 * because a library registers its shelves long before anyone walks the room with a
 * measuring tape. Unmeasured nodes get an automatic layout here, so every client
 * draws the same floor plan and the view is never empty or stacked at 0,0.
 */
@Service
class MapService {

    /** Default footprint for a node nobody has measured. */
    private static final double FALLBACK_WIDTH = 2.0;
    private static final double FALLBACK_DEPTH = 1.0;
    private static final double FALLBACK_HEIGHT = 2.0;
    /** Gap between automatically placed siblings, in metres. */
    private static final double FALLBACK_GAP = 0.6;
    private static final double AISLE_DEPTH = 2.0;

    private final LocationRepository locations;
    private final JdbcTemplate jdbc;

    MapService(LocationRepository locations, JdbcTemplate jdbc) {
        this.locations = locations;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    MapDtos.MapView view() {
        var all = locations.findAll(Sort.by(Sort.Direction.ASC, "sortOrder", "code"));
        var placements = place(all);
        var occupancy = occupancyByLocation();
        var items = itemsByLocation();

        var nodes = all.stream()
                .map(location -> {
                    var box = placements.get(location.getId());
                    var copies = occupancy.getOrDefault(location.getId(), Map.of());
                    int total = copies.values().stream().mapToInt(Integer::intValue).sum();
                    return new MapDtos.MapNode(
                            location.getId(), location.getCode(), location.getName(),
                            location.getKind().name(), location.getParentId(),
                            box.x(), box.y(), box.z(), box.width(), box.depth(), box.height(),
                            total, copies, items.getOrDefault(location.getId(), List.of()));
                })
                .toList();

        return new MapDtos.MapView(bounds(nodes), nodes);
    }

    /** A shelf is a box: floor at its own height, centred on its coordinates. */
    private record Box(double x, double y, double z, double width, double depth, double height) {
    }

    private Map<Long, Box> place(List<Location> all) {
        var children = all.stream()
                .filter(l -> l.getParentId() != null)
                .collect(Collectors.groupingBy(Location::getParentId));
        var roots = all.stream().filter(l -> l.getParentId() == null).toList();

        var boxes = new LinkedHashMap<Long, Box>();
        double cursor = 0;
        for (var root : roots) {
            var size = measure(root);
            double x = root.getX() != null ? number(root.getX()) : cursor;
            double z = root.getZ() != null ? number(root.getZ()) : 0;
            boxes.put(root.getId(), new Box(x, number(root.getY()), z,
                    size.width(), size.depth(), size.height()));
            // Children start at the back wall of whatever holds them.
            boxes.putAll(placeChildren(children, children.getOrDefault(root.getId(), List.of()),
                    z + size.depth(), 0));
            cursor = x + size.width() + FALLBACK_GAP;
        }
        return boxes;
    }

    /**
     * Any node can hold children: a room holds aisles, an aisle holds shelves.
     * Placing them all the same way is why every measured node still ends up in
     * the map instead of only the ones hanging directly off a room.
     */
    private Map<Long, Box> placeChildren(Map<Long, List<Location>> children, List<Location> siblings,
                                         double startZ, double startX) {
        var boxes = new LinkedHashMap<Long, Box>();
        double x = startX;
        double z = startZ;
        for (var child : siblings) {
            var size = measure(child);
            double cx = child.getX() != null ? number(child.getX()) : x;
            double cz = child.getZ() != null ? number(child.getZ()) : z;
            boxes.put(child.getId(), new Box(cx, number(child.getY()), cz,
                    size.width(), size.depth(), size.height()));
            boxes.putAll(placeChildren(children, children.getOrDefault(child.getId(), List.of()),
                    cz + size.depth(), 0));
            x = cx + size.width() + FALLBACK_GAP;
        }
        return boxes;
    }

    private record Size(double width, double depth, double height) {
    }

    private Size measure(Location location) {
        return new Size(
                location.getWidth() == null ? FALLBACK_WIDTH : number(location.getWidth()),
                location.getDepth() == null ? FALLBACK_DEPTH : number(location.getDepth()),
                location.getHeight() == null ? FALLBACK_HEIGHT : number(location.getHeight()));
    }

    private Map<Long, Map<String, Integer>> occupancyByLocation() {
        Map<Long, Map<String, Integer>> result = new LinkedHashMap<>();
        jdbc.query("""
                select location_id, status, count(*)
                from copies
                where location_id is not null
                group by location_id, status
                """, rs -> {
                    result
                            .computeIfAbsent(rs.getLong("location_id"), key -> new LinkedHashMap<>())
                            .put(rs.getString("status"), rs.getInt(3));
                });
        return result;
    }

    private Map<Long, List<MapDtos.MapItem>> itemsByLocation() {
        Map<Long, List<MapDtos.MapItem>> result = new LinkedHashMap<>();
        jdbc.query("""
                select c.location_id, c.id, c.code, c.barcode, c.status, c.book_id, b.title
                from copies c join books b on b.id = c.book_id
                where c.location_id is not null
                order by b.title, c.code
                """, rs -> {
                    result
                            .computeIfAbsent(rs.getLong("location_id"), key -> new java.util.ArrayList<>())
                            .add(new MapDtos.MapItem(rs.getLong("id"), rs.getString("code"),
                                    rs.getString("barcode"), rs.getString("status"),
                                    rs.getLong("book_id"), rs.getString("title")));
                });
        return result;
    }

    private MapDtos.Bounds bounds(List<MapDtos.MapNode> nodes) {
        if (nodes.isEmpty()) {
            return new MapDtos.Bounds(0, 10, 0, 10, 10, 10);
        }
        double minX = nodes.stream().mapToDouble(MapDtos.MapNode::x).min().orElse(0);
        double minZ = nodes.stream().mapToDouble(MapDtos.MapNode::z).min().orElse(0);
        double maxX = nodes.stream()
                .mapToDouble(n -> n.x() + n.width()).max().orElse(10);
        double maxZ = nodes.stream()
                .mapToDouble(n -> n.z() + n.depth()).max().orElse(10);
        // A single node would otherwise produce a zero-sized frame and a division by zero.
        double width = Math.max(maxX - minX, 1);
        double depth = Math.max(maxZ - minZ, 1);
        return new MapDtos.Bounds(minX, maxX, minZ, maxZ, width, depth);
    }

    private static double number(java.math.BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}

/** Authorisation for this path lives in {@code SecurityConfig}. */
@RestController
class MapController {

    private final MapService maps;

    MapController(MapService maps) {
        this.maps = maps;
    }

    @GetMapping(value = "/map", produces = MediaType.APPLICATION_JSON_VALUE)
    MapDtos.MapView map() {
        return maps.view();
    }
}

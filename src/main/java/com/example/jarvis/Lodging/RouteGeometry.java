package com.example.jarvis.Lodging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class RouteGeometry {
    private static final double M_PER_DEG_LAT = 110_540;
    private static final double M_PER_DEG_LON_AT_EQUATOR = 111_320;

    public record Projection(double offRouteMeters, double alongRouteMeters) {}

    private final List<LatLon> pts;
    private final double[] cumulative; // meters from the start to each vertex

    public RouteGeometry(List<LatLon> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A route needs at least 2 points");
        }
        this.pts = List.copyOf(points);
        this.cumulative = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cumulative[i] = cumulative[i - 1] + haversine(pts.get(i - 1), pts.get(i));
        }
    }

    public double totalMeters() {
        return cumulative[cumulative.length - 1];
    }

    /** Closest point on the route to p: how far off the road it is, and how far along the trip. */
    public Projection project(LatLon p) {
        double bestDist = Double.MAX_VALUE;
        double bestAlong = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double[] r = segment(p, pts.get(i), pts.get(i + 1));
            if (r[0] < bestDist) {
                bestDist = r[0];
                bestAlong = cumulative[i] + r[1] * (cumulative[i + 1] - cumulative[i]);
            }
        }
        return new Projection(bestDist, bestAlong);
    }

    /** Douglas-Peucker: drops points that deviate less than the tolerance, keeping the shape. */
    public List<LatLon> simplified(double toleranceMeters) {
        int n = pts.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int first = range[0], last = range[1];
            double maxDist = 0;
            int index = -1;
            for (int i = first + 1; i < last; i++) {
                double d = segment(pts.get(i), pts.get(first), pts.get(last))[0];
                if (d > maxDist) {
                    maxDist = d;
                    index = i;
                }
            }
            if (index != -1 && maxDist > toleranceMeters) {
                keep[index] = true;
                stack.push(new int[]{first, index});
                stack.push(new int[]{index, last});
            }
        }
        List<LatLon> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    /** Returns {distance in meters from p to segment a-b, fraction 0..1 along the segment}. */
    private static double[] segment(LatLon p, LatLon a, LatLon b) {
        double cos = Math.cos(Math.toRadians(p.lat()));
        double ax = (a.lon() - p.lon()) * M_PER_DEG_LON_AT_EQUATOR * cos;
        double ay = (a.lat() - p.lat()) * M_PER_DEG_LAT;
        double bx = (b.lon() - p.lon()) * M_PER_DEG_LON_AT_EQUATOR * cos;
        double by = (b.lat() - p.lat()) * M_PER_DEG_LAT;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (-ax * dx - ay * dy) / len2));
        return new double[]{Math.hypot(ax + t * dx, ay + t * dy), t};
    }

    static double haversine(LatLon a, LatLon b) {
        double r = 6_371_000;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

}

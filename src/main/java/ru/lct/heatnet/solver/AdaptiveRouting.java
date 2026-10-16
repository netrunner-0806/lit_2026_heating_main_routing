package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.routing.ComplexityLimitException;
import ru.lct.heatnet.routing.NavGraph;
import java.util.*;

/** Extra visibility and spatial coverage, used only for a consumer the ordinary search could not attach. */
final class AdaptiveRouting {
    // Per-attempt work/memory bounds, not engineering restrictions. Exhaustion is reported explicitly.
    static final int MAX_VISIBILITY_VERTICES = 20_000;
    static final int MAX_GRID_CELLS = 4096;

    /** Add coverage without discarding earlier vertices/edges; the region boundary is never an obstacle. */
    static void expandRegion(NavGraph graph,Envelope region,double spacing,double radius) {
        double nx=Math.floor(region.getWidth()/spacing)+1,ny=Math.floor(region.getHeight()/spacing)+1;
        // Region growth replaces coverage of the primary graph, not the optional fine-grid refinement.
        // A smaller 4096-cell refinement budget would reject detours that the ordinary graph can represent.
        if(!Double.isFinite(nx*ny)||nx*ny>NavGraph.MAX_GRID_NODES)
            throw new ComplexityLimitException("expanded navigation region requires " + (nx*ny) + " grid cells; budget " + NavGraph.MAX_GRID_NODES);
        List<Coordinate> vertices=graph.space().navigationVertices(region,MAX_VISIBILITY_VERTICES);
        if(vertices.size()+graph.size()>NavGraph.MAX_NAVIGATION_NODES)
            throw new ComplexityLimitException("expanded region exceeds navigation vertex budget");
        for(Coordinate p:vertices)if(graph.indexOf(p)<0)graph.addVertex(p,Exemptions.NONE,radius,null);
        for(int ix=0;ix<(int)nx;ix++)for(int iy=0;iy<(int)ny;iy++) {
            Coordinate p=new Coordinate(region.getMinX()+ix*spacing,region.getMinY()+iy*spacing);
            if(graph.indexOf(p)<0&&!graph.space().insideAnyZone(p))graph.addVertex(p,Exemptions.NONE,radius,null);
        }
    }

    static void refineClearanceVertices(NavGraph graph, double radius) {
        List<Coordinate> vertices = graph.space().refinedNavigationVertices(MAX_VISIBILITY_VERTICES);
        if (vertices.size() + graph.size() > NavGraph.MAX_NAVIGATION_NODES)
            throw new ComplexityLimitException("adaptive clearance vertices exceed navigation node budget");
        for (Coordinate p : vertices) if (graph.indexOf(p) < 0) graph.addVertex(p, Exemptions.NONE, radius, null);
    }

    static Approach wideTerminal(Approach ap, NavGraph graph) {
        if (graph.size() > MAX_VISIBILITY_VERTICES)
            throw new ComplexityLimitException("adaptive terminal visibility exceeds " + MAX_VISIBILITY_VERTICES + " vertices");
        double radius = 0;
        for (int i = 0; i < graph.size(); i++) radius = Math.max(radius, ap.freePoint().distance(graph.coord(i)));
        Approach wide = new Approach(ap.cp(), ap.ownPolygon(), ap.boundaryPoint(), ap.freePoint(), ap.finalSegment(), ap.nearestBoundary());
        wide.setVertex(graph.addVertex(ap.freePoint(), Exemptions.NONE, radius + 1, ap.heading()));
        return wide;
    }

    /** Cover terminals as well as obstacles. The original helper grid covers only obstacle envelopes. */
    static void expandGrid(NavGraph graph, double spacing, double radius) {
        Envelope env = new Envelope();
        for (int i = 0; i < graph.size(); i++) env.expandToInclude(graph.coord(i));
        if (env.isNull()) return;
        env.expandBy(spacing);
        double nx = Math.floor(env.getWidth() / spacing) + 1, ny = Math.floor(env.getHeight() / spacing) + 1;
        if (!Double.isFinite(nx * ny) || nx * ny > MAX_GRID_CELLS)
            throw new ComplexityLimitException("adaptive grid requires " + (nx * ny) + " cells at " + spacing + " m; budget " + MAX_GRID_CELLS);
        for (int ix = 0; ix < (int) nx; ix++) for (int iy = 0; iy < (int) ny; iy++) {
            Coordinate p = new Coordinate(env.getMinX() + ix * spacing, env.getMinY() + iy * spacing);
            if (graph.indexOf(p) < 0 && !graph.space().insideForbidden(p)) graph.addVertex(p, Exemptions.NONE, radius, null);
        }
    }
}

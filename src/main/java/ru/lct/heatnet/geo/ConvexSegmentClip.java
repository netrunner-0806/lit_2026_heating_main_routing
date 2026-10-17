package ru.lct.heatnet.geo;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.*;

/** Exact-predicate fast path for a segment intersecting one small convex polygon.
 * Returns null for unsupported/numerically ambiguous cases: callers retain the robust overlay path.
 * No engineering distance or special passage is approximated here. */
public final class ConvexSegmentClip {
    public static final ConvexSegmentClip UNSUPPORTED = new ConvexSegmentClip(null,0);
    private final Coordinate[] ring;
    private final int winding;
    private ConvexSegmentClip(Coordinate[] ring,int winding){this.ring=ring;this.winding=winding;}
    public static ConvexSegmentClip prepare(Geometry g) {
        if(!(g instanceof Polygon))return UNSUPPORTED;
        Polygon p=(Polygon)g;
        if(p.isEmpty()||p.getNumInteriorRing()!=0||p.getNumPoints()>512||!p.isValid())return UNSUPPORTED;
        Coordinate[] c=p.getExteriorRing().getCoordinates();int winding=0;
        for(int i=0;i<c.length-1;i++) {
            int sign=Orientation.index(c[i],c[(i+1)%(c.length-1)],c[(i+2)%(c.length-1)]);
            if(sign!=0){if(winding!=0&&sign!=winding)return UNSUPPORTED;winding=sign;}
        }
        return winding==0?UNSUPPORTED:new ConvexSegmentClip(c,winding);
    }
    /** Empty array = no intersection; pair = distances from a, matching legacy intervalOf semantics. */
    public double[] interval(Coordinate a,Coordinate b,double length) {
        if(ring==null||length<1e-9)return null;
        boolean aInside=true,bInside=true;
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        RobustLineIntersector li=new RobustLineIntersector();
        for(int i=0;i<ring.length-1;i++) {
            Coordinate c=ring[i],d=ring[i+1];
            int sa=Orientation.index(c,d,a),sb=Orientation.index(c,d,b);
            if(sa!=0&&sa!=winding)aInside=false;
            if(sb!=0&&sb!=winding)bInside=false;
            if(sa==sb&&sa!=0)continue;
            li.computeIntersection(a,b,c,d);
            for(int k=0;k<li.getIntersectionNum();k++) {
                double t=a.distance(li.getIntersection(k));low=Math.min(low,t);high=Math.max(high,t);
            }
        }
        if(aInside){low=0;high=Math.max(high,0);}
        if(bInside){low=Math.min(low,length);high=length;}
        if(low==Double.POSITIVE_INFINITY)return new double[0];
        return new double[]{Math.max(0,Math.min(length,low)),Math.max(0,Math.min(length,high))};
    }
}

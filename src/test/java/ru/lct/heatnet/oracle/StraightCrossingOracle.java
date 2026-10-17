package ru.lct.heatnet.oracle;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineSegment;
import java.util.*;

/** Independent oracle for straight PLANAR fixtures on y=0, using raw metric edges and Table 1/2.
 * No production routing, overlay, cost or validator calls. This intentionally has a limited domain:
 * simple polygons without holes, proper edge crossings and one constant-flow hydraulic section.
 * It does NOT claim to validate arbitrary output networks. */
final class StraightCrossingOracle {
    static final class ObjectShape {
        final String type; final double[] xy; final boolean polygon;
        ObjectShape(String type,boolean polygon,double... xy) {this.type=type;this.polygon=polygon;this.xy=xy;}
    }
    static final class Piece {
        String id; double from,to,k,cost; int du; double flow;
        Piece(String id,double from,double to,int du,double flow,double k) {
            this.id=id;this.from=from;this.to=to;this.du=du;this.flow=flow;this.k=k;
            this.cost=Math.round((to-from)*ReferenceRules.byDu(du).costPerMetre*k);
        }
    }
    private static final class Passage {final double a,b,k; Passage(double a,double b,double k){this.a=a;this.b=b;this.k=k;}}
    static List<String> check(double from,double to,double demand,List<ObjectShape> objects,List<Piece> pieces) {
        List<String> errors=new ArrayList<>();List<Passage> all=new ArrayList<>();
        int du=pieces.isEmpty()?80:pieces.get(0).du;
        double width=ReferenceRules.byDu(du).widthM;
        for(ObjectShape o:objects) {
            boolean forbidden=Arrays.asList("water","railway","park","social_area","prohibited_site").contains(o.type);
            boolean road=o.type.equals("road")||o.type.equals("tram_tracks");
            double k=road?(o.type.equals("road")?1.6:1.75):o.type.equals("gas_pipeline")?1.25:o.type.equals("power_cable")?1.15:1.05;
            double radius=(road?1.5:forbidden||o.type.equals("heat_network")?1:2)+width/2
                    +(o.type.equals("gas_pipeline")?.2:o.type.equals("power_cable")?.1:o.type.equals("heat_network")?.575:0);
            List<LineSegment> edges=new ArrayList<>();List<Double> xs=new ArrayList<>();Set<Integer> crossing=new HashSet<>();
            for(int i=2;i<o.xy.length;i+=2) {
                LineSegment e=new LineSegment(o.xy[i-2],o.xy[i-1],o.xy[i],o.xy[i+1]);edges.add(e);
                if(e.p0.y*e.p1.y<0) {
                    double x=e.p0.x-e.p0.y*(e.p1.x-e.p0.x)/(e.p1.y-e.p0.y);
                    if(x>=from&&x<=to) {xs.add(x);crossing.add(edges.size()-1);
                        if(road&&Math.abs(e.p1.y-e.p0.y)/e.getLength()<Math.sqrt(.5)-1e-12) errors.add("ANGLE");}
                }
            }
            Collections.sort(xs);List<Passage> local=new ArrayList<>();
            if(forbidden&&!xs.isEmpty())errors.add("FORBIDDEN");
            if(!forbidden) {
                if(road) {for(int i=1;i<xs.size();i+=2)local.add(new Passage(xs.get(i-1)-3,xs.get(i)+3,k));}
                else for(double x:xs)local.add(new Passage(x-2,x+2,k));
            }
            all.addAll(local);
            // Subtract only this object's normative passages. Each *non-crossing* original edge
            // must satisfy exact segment distance outside them, even if another edge crosses.
            List<Double> cuts=new ArrayList<>(Arrays.asList(from,to));
            for(Passage p:local){cuts.add(Math.max(from,p.a));cuts.add(Math.min(to,p.b));}Collections.sort(cuts);
            for(int i=1;i<cuts.size();i++) {
                double a=cuts.get(i-1),b=cuts.get(i),mid=(a+b)/2;
                if(b<=a||local.stream().anyMatch(p->mid>=p.a&&mid<=p.b))continue;
                LineSegment span=new LineSegment(a,0,b,0);
                for(int j=0;j<edges.size();j++) {
                    if(!forbidden&&crossing.contains(j))continue; // only the actual crossing edge, never its connected neighbours
                    if(span.distance(edges.get(j))<radius-1e-8)errors.add("CLEARANCE");
                }
            }
        }
        Set<String> ids=new HashSet<>();double at=from;
        ReferenceRules.Du minimum=null;
        for(var row:ReferenceRules.TABLE_1)if(row.capacityTph>=demand&&row.maxLengthM>=to-from){minimum=row;break;}
        for(Piece p:pieces) {
            if(!ids.add(p.id))errors.add("ID");
            if(Math.abs(p.from-at)>1e-8)errors.add("CONNECTIVITY");at=p.to;
            if(minimum==null||p.du!=minimum.du||Math.abs(p.flow-demand)>1e-9)errors.add("HYDRAULICS");
            double expected=1;
            for(Passage pass:all) {
                if(pass.a>p.from+1e-8&&pass.a<p.to-1e-8||pass.b>p.from+1e-8&&pass.b<p.to-1e-8)errors.add("SPLIT");
                double mid=(p.from+p.to)/2;if(mid>pass.a&&mid<pass.b)expected=Math.max(expected,pass.k);
            }
            if(Math.abs(expected-p.k)>1e-9)errors.add("KSPEC");
            if(Math.abs(p.cost-Math.round((p.to-p.from)*ReferenceRules.byDu(p.du).costPerMetre*expected))>.5)errors.add("COST");
        }
        if(Math.abs(at-to)>1e-8)errors.add("CONNECTIVITY");
        return errors;
    }
}

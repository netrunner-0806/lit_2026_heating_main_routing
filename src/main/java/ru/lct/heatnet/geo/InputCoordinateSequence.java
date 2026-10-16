package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;

/** Lossless XY storage for retained input geometry. Unlike the general JTS packed sequence, never retain
 * a second Coordinate[] representation after a geometry operation asks for it. Callers still receive copies,
 * as specified by PackedCoordinateSequence; coordinate changes must use the sequence setters. */
final class InputCoordinateSequence extends PackedCoordinateSequence.Double {
    InputCoordinateSequence(double[] xy) { super(xy, 2, 0); }

    @Override public Coordinate[] toCoordinateArray() {
        Coordinate[] result=new Coordinate[size()];
        for(int i=0;i<result.length;i++) result[i]=getCoordinateCopy(i);
        return result;
    }

    @Override public InputCoordinateSequence copy() {
        double[] xy=new double[Math.multiplyExact(size(),2)];
        for(int i=0;i<size();i++) {xy[2*i]=getX(i);xy[2*i+1]=getY(i);}
        return new InputCoordinateSequence(xy);
    }
}

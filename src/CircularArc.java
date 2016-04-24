/*
Copyright (C) 2016  S Combes

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/
package cisolate;
import java.lang.Math;
import java.util.Iterator;

class CircularArc implements Iterable<Point2D> {

/* Once constructed, arc moves to the next rookwise-connected integer pixel
   on the arc whenever increment() is called and then getX/getY can be
   called to get location.  increment() returns false once complete.

   Visits first pixel (if getX()/getY() called before increment), but not
   necessarily last, because isn't actually guaranteed to hit it. */
   
private enum Quadrant { NE,SE,SW,NW  }
private enum Cardinal { N,S,E,W  }  

// Coordinates +ve to right (East) +ve up (North)
final int sx,sy;   // Start of arc
final int x0,y0;   // Centre of arc
final int fx,fy;   // End of arc 
final boolean cw;  // Clockwise.  
final long rsq;  // Squared radius of arc

private long distancesq(int deltax, int deltay) 
  { return ((long)deltax*(long)deltax+(long)deltay*(long)deltay);}

private long distance(int deltax, int deltay) 
  { return (int)(0.5+Math.sqrt((double)distancesq(deltax,deltay)));}

// -----------------------------------------------------------------
CircularArc(boolean cw,Point2D halfStart,Point2D halfEnd,int halfradius)
{ 
// Sets up a circular arc from (x,y) to an endpoint (fx,fy) with a 
// defined radius, and running clockwise or anticlockwise.
// Arc will be the shortest for the two possible centre points for that
// radius.

// ************ Internally all numbers are doubled **************
// Allows integers to be used, with calculation using the corner points
// as tests.  i.e. move to next pixel : add/subtract 2 to either axis.
// Test corner of pixel to decide where to move : add/subtract 1 to both axes.
// Centre of pixels at even numbers.  Corner points are at odd numbers.
 
this.cw=cw;
sx=2*halfStart.getX();
sy=2*halfStart.getY();
fx=2*halfEnd.getX();
fy=2*halfEnd.getY();
int radius=2*halfradius;

// Now find arc centre.  First calculate distance pt-to-pt
int deltax=(fx-sx);
int deltay=(fy-sy);
long line=distance(deltax,deltay);

if (radius < (line/2))
  throw new IllegalArgumentException(
   "Radius needs to be at least half distance but "+radius+" is less than half "+line);

// Relative to the line joining the start and end points, the
// centre will lie at an angle of theta=acos(line/(2*radius)).
// Conveniently we want the result in the first quadrant to
// ensure the arc is direct.

double theta=Math.acos((double)line/(2.0*(double)radius));

if (cw) theta=(-theta);  // Pick which centre we want

// Now, if we rotate the vector joining start to finish points 
// by theta and scale it back by (radius/line) it will be a vector
// from the start point to the centre.

// Rotate
double dx=(double)deltax*Math.cos(theta)-(double)deltay*Math.sin(theta);
double dy=(double)deltax*Math.sin(theta)+(double)deltay*Math.cos(theta);

// Scale
dx*=((double)radius/(double)line);
dy*=((double)radius/(double)line);

// Offset
x0=sx+(int)(dx+0.5);  // Centre of circle set.
y0=sy+(int)(dy+0.5);

deltax=(sx-x0); // N.B. different definition of delta
deltay=(sy-y0);

rsq=distancesq(sx-x0,sy-y0);  // The circle radius squared
}
// ------------------------------------------------------------------------------
@Override
public Iterator<Point2D> iterator() { return new CircularArcIterator(); }
// ------------------------------------------------------------------------------
public String toString() {

  return ((cw?"":"Anti-")+"Clockwise Circular arc with centre ("+(x0/2)+","+(y0/2)+
         "), Start point ("+(sx/2)+","+(sy/2)+"), End ("+(fx/2)+","+(fy/2)+")");
}
// ------------------------------------------------------------------------------
private class CircularArcIterator implements Iterator<Point2D> {

boolean done;
boolean there;
int x;
int y;

public CircularArcIterator() 
{
done=false;
there=false;
x=sx;
y=sy;
}
// ------------------------------------------------------------------------------
@Override
public boolean hasNext() { return !done; }
// ------------------------------------------------------------------------------
@Override
public Point2D next()
{ // Finds the next move for a circle whose parameters have previously been set up

Point2D result=new Point2D(x/2,y/2);
long r2sq;
int deltax=(x-x0);
int deltay=(y-y0);

Quadrant quadrant;
Cardinal cardinal;

// Not easy to explain without diagram ...
// First determine, based on tangent to circle and the handedness, 
// which quadrant we are moving to next i.e. the two potential 
// directions we can move next ...

if (cw) {
  if (deltax>0) quadrant=(deltay>0)?Quadrant.SE:Quadrant.SW;
  else          quadrant=(deltay>0)?Quadrant.NE:Quadrant.NW;
} else {
  if (deltax>0) quadrant=(deltay>0)?Quadrant.NW:Quadrant.NE;
  else          quadrant=(deltay>0)?Quadrant.SW:Quadrant.SE;
}

// ... then test the distance of that corner of the pixel from the arc centre
// against the arc radius to determine which of those pixels is the correct
// one to move into.  Since the pixel centre is guaranteed to be an even
// point (from internal doubling), corner will be odd and +/-1 from centre.

switch (quadrant) {
  case NE:
    r2sq=distancesq(x-x0+1,y-y0+1);
    cardinal=(cw ^ (r2sq > rsq))?Cardinal.N:Cardinal.E;
    break;

  case SE:
    r2sq=distancesq(x-x0+1,y-y0-1);
    cardinal=(cw ^ (r2sq > rsq))?Cardinal.E:Cardinal.S;
    break;

  case SW:
    r2sq=distancesq(x-x0-1,y-y0-1);
    cardinal=(cw ^ (r2sq > rsq))?Cardinal.S:Cardinal.W;
    break;

  case NW: default: // Avoids 'might not be initialised' compile error
    r2sq=distancesq(x-x0-1,y-y0+1);
    cardinal=(cw ^ (r2sq > rsq))?Cardinal.W:Cardinal.N;
    break;
  }

switch (cardinal) {    // So now make the correct move
  case E: x+=2; break;
  case N: y+=2; break;
  case W: x-=2; break;
  case S: y-=2; break;
}

// And see if we have reached the endpoint
if ((x-fx)==0 && (y-fy)==0 ) done=true;  // At end
if (Math.abs(x-fx)<3 && Math.abs(y-fy)<3 ) there=true; // Close
else if (there) done=true; // Had started moving away

return result;  
}
// --------------------------------------------------------------
@Override
public void remove() {}
}
// --------------------------------------------------------------
public static void main(String [] args) {

// Testing harness

for (int r=81;r<100810;r*=2) {

  CircularArc ca = new CircularArc(true,new Point2D(10,15),new Point2D(100,100),r);

  System.out.println(ca);
  for (Point2D point : ca)
    System.out.println(ca+" "+point.getX()+" "+point.getY());
 
}
}
}

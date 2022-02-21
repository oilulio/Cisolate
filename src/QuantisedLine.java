/*
Copyright (C) 2018  S Combes

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
import java.util.Iterator;

class QuantisedLine implements Iterable<Point2D> {

/* Once constructed, moves to the next rookwise-connected integer pixel
   on the line whenever increment() is called and then getX/getY can be
   called to get location.  increment() returns false once complete.

   Visits first pixel (if getX()/getY() called before increment), but not
   necessarily last, because isn't actually guaranteed to hit it. */
   
private enum Quadrant { NE,SE,SW,NW  }
static final int NO_MOVE=(0);
static final int PLUS_X =(1);
static final int PLUS_Y =(2);
static final int MINUS_X=(3);
static final int MINUS_Y=(4);

// Coordinates +ve to right (East) +ve up (North)
final int sx,sy;   // Start of line
final int fx,fy;   // End of line 

Quadrant direction;
boolean vertical;
int move=NO_MOVE;
double dp,dq;
// -----------------------------------------------------------------
QuantisedLine(Point2D start,Point2D end)
{ 
// Sets up a line from (x,y) to an endpoint (fx,fy) 

// ************ Internally all numbers are doubled **************
// Allows integers to be used, with calculation using the corner points
// as tests.  i.e. move to next pixel : add/subtract 2 to either axis.
// Test corner of pixel to decide where to move : add/subtract 1 to both axes.
// Centre of pixels at even numbers.  Corner points are at odd numbers.
 
sx=2*start.getX();
sy=2*start.getY();
fx=2*end.getX();
fy=2*end.getY();

int deltax,deltay;
deltax=(fx-sx);
deltay=(fy-sy);
dp=(double)deltax;
dq=(double)deltay;

vertical=(deltax==0);  // Useful flag to avoid later division by zero
if (deltax>0)  direction=(deltay>0)?Quadrant.NE:Quadrant.SE;
else           direction=(deltay>0)?Quadrant.NW:Quadrant.SW; 
}
// ------------------------------------------------------------------------------
public Route2D asRoute2D() 
{  
Route2D route=new Route2D();
for (Point2D point : this) route.add(point);  
return route;
}
// ------------------------------------------------------------------------------
@Override
public Iterator<Point2D> iterator() { return new LineIterator(); }
// ------------------------------------------------------------------------------
public String toString() 
{
  return ("Line with start point ("+(sx/2)+","+(sy/2)+"), End ("+
                                    (fx/2)+","+(fy/2)+")");
}
// ------------------------------------------------------------------------------
private class LineIterator implements Iterator<Point2D> {

boolean done;
boolean there;
int x;
int y;

public LineIterator() 
{
done=false;
x=sx;
y=sy;
}
// ------------------------------------------------------------------------------
@Override
public boolean hasNext() { return !done; }
// ------------------------------------------------------------------------------
@Override
public Point2D next()
{ 
// Finds the next move for a line whose parameters are already set up
// returning False indicates no further move
int y1;

if (x==fx && y==fy) { done=true; return new Point2D(fx/2,fy/2); }
if (Math.abs(x-fx)<3 && Math.abs(y-fy)<3 ) {
  x=fx; // TODO : Ensure rookwise
  y=fy;
  done=true; 
  return new Point2D(fx/2,fy/2); 
}

if (vertical) { // Avoid division by zero - simple.  Go up or down based on N or S
  switch (direction) {
    case NE: // Fall-though
    case NW: move=PLUS_Y;  break;
    case SE: // Fall-though
    case SW: move=MINUS_Y; break;
  }
}
else {
  y1=(int)(sy+((x+1-sx)/dp)*dq);

  switch (direction) {
    case NE: move=(y1<(y+1))? PLUS_X :PLUS_Y;  break;
    case SE: move=(y1<(y-1))? MINUS_Y:PLUS_X;  break;
    case SW: move=(y1<(y-1))? MINUS_Y:MINUS_X; break;
    case NW: move=(y1<(y+1))? MINUS_X:PLUS_Y;  break;
  }
}
switch (move) {
  case (PLUS_X):  x+=2; break;
  case (PLUS_Y):  y+=2; break;
  case (MINUS_X): x-=2; break;
  case (MINUS_Y): y-=2; break;
}
move=NO_MOVE;

return new Point2D(x/2,y/2);
}
// --------------------------------------------------------------
@Override
public void remove() {}
}
// --------------------------------------------------------------
}

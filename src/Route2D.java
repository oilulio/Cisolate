/*
Copyright (C) 2016-18  S Combes, with thanks to jasoroony for correction.

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

import java.util.List;
import java.util.ArrayList;
import java.awt.*; 

// Despite title, this Class is not generic - it is closely bound to
// Cisolate, because the 'heat map' input is unlikely to be produced elsewhere.
// Use caution if re-using 

// Note that Screen Y (and hence internal representation) moves from top down and 
// GCode Y moves from Bottom up - annoying

class Route2D extends Points2D implements Runnable
{
// A group of 2D points ordered as a route, i.e. with an explicit transit ordering

static int [][] lastTouch;
static double xOrigin;
static double yOrigin;
static double xPerPixel;
static double yPerPixel;
static String fstr="%.3f";

ControlPoints cp;
static final int BASERADIUS=4;
static final int MAXRADIUS=100000;
static final int FLEXPERCENT=10;
static final double FLEX=(100.0+FLEXPERCENT)/100.0;
private volatile boolean stop = false;
static final String nL = System.getProperty("line.separator");
static int solved=0;
static Points2D rawJunctions;
static Points2D smoothJunctions;

public static void initialise(int [][] slastTouch,
          double sxOrigin,double syOrigin,
          double sxPerPixel,double syPerPixel)
{
lastTouch=slastTouch;
xOrigin=sxOrigin;
yOrigin=syOrigin;
xPerPixel=sxPerPixel;
yPerPixel=syPerPixel;
rawJunctions   =new Points2D();
smoothJunctions=new Points2D();
solved=0;
}

Route2D(Points2D points)  { super(); this.points=points.points; }
Route2D()                 { super(); }

// ------------------------------------------------------------------
private double dlength(int x1,int y1,int x2,int y2)
{ return Math.sqrt((double)((x1-x2)*(x1-x2)+(y1-y2)*(y1-y2))); }
// ---------------------------------------------------------------
public void gracefulExit() { stop = true; }
// ---------------------------------------------------------------
public static void resetCount() { solved=0; }
// ---------------------------------------------------------------
public static int noSolved() { return solved; }
// ------------------------------------------------------------------
@Override
public void run() 
{
/* Takes a detailed 2D route and 'straightens the curves' to create 
   smooth G code with minimal (for a given optimisation constraint) 
   deviation from the intended curve. Uses a 'heat map' of deviation 
   penalties to inform optimisation, via a calibration function 
  (mappedScore()).

   G code only allows straight lines or circular arcs.  Hence these 
   are the key primitives.  Arbitrarily, allowable arcs are constrained 
   to a fixed set to ease computation.  Again, arbitrarily, the radii 
   of this set follow a power law.  The largest radius is set s.t. w.l.g. 
   it may be considered a straight line.  Hence, during the computation, 
   only 'circular' arcs are considered, some later assigned as straight 
   lines when the G Code is written. 
  
   Uses 'de minimus' concept in the optimisation to decide whether it
   is worth worrying about differences - and prioritises a minimisation
   of the number of different sequences on this basis.   
 */
stop=false;
Thread t = Thread.currentThread();  
t.setPriority(Thread.MIN_PRIORITY);  

if (lastTouch==null) { 
  System.out.println("Error : last touch unset in Route2D");
  System.exit(0);
}
cp=new ControlPoints();

// First optimise each arc between a pair of control points individually 
for (int i=0;i<cp.getArcs();i++) {
  if (stop) return;
  if (cp.optimiseArc(cp.control.get(i),cp.control.get(i+1))) {
    cp.arcScore.set(i,cp.bestScore);
    cp.arcRadius.set(i,cp.bestRadius);
  } else { // Couldn't find an arc, but there is one allowable case - 
    // diagonally across a 2x2 square where other diagonal has copper
 
    if (Math.abs(cp.control.getX(i)-cp.control.getX(i+1))!=1 && 
        Math.abs(cp.control.getY(i)-cp.control.getY(i+1))!=1) 
      throw new RuntimeException("Can't make curve");

    cp.arcScore.set(i,0.0);
    cp.arcRadius.set(i,MAXRADIUS); // ~Str line  
  }
}

// See if we can optimise better by grouping
// See how big a group of points we can reasonably make, starting with all
// of them. This is On^2.  Works OK, but slow (~minute) if there is one 
// big route (typically the board outline falls in this category)

boolean change;
do {
  if (stop) return;
  change=false;
  for (int group=cp.getPoints()-1;group>1;group--) {
    for (int start=0;start<(cp.getPoints()-group);start++) {
      if (stop) return;
      if (cp.optimiseArc(cp.control.get(start),cp.control.get(start+group))) {
        if (stop) return;

        double previousScore=0.0;
        for (int j=start;j<(start+group);j++) 
          previousScore+=cp.arcScore.get(j);

        if ((cp.bestScore*FLEX) > previousScore) {
 
          cp.replace(start,group,cp.bestRadius,previousScore); 
          // Use previous score for new arc so we don't compound cuts
          change=true;
        }
      }
    }
  }
} while (change);
//System.out.print(" +"); // Show progress
solved++;
return;
}
// ------------------------------------------------------------------
String smoothGcode(boolean reversed,boolean attached,int millRate,
     int plungeRate,double millPlunge,double millTransit,boolean backlash,
     double radius,Skeleton skeleton) {
// Returns the Gcode for the arc - which may be reversed
// and/or attached to the last segment - i.e. no transit required
// Only to be called after smoothing has completed - currently
// handled by caller - not especially safe if code is reused.

StringBuilder result=new StringBuilder("");
int start,end,inc; 

if (reversed) {
  start=cp.getArcs();
  end=0;
  inc=(-1);
} else {
  start=0;
  end=cp.getArcs();
  inc=1;
}

if (!attached) {
  result.append("G00 Z"+millTransit+nL);
  result.append("G00 X"+
        String.format(fstr,  xOrigin+xPerPixel*cp.control.getX(start))+
   " Y"+String.format(fstr,-(yOrigin+yPerPixel*cp.control.getY(start)))+nL);
  result.append("F"+plungeRate+nL);
  result.append("G01 Z"+millPlunge+nL);
  result.append("F"+millRate+nL);
}

for (int i=start;i!=end;i+=inc) {
  int xx=cp.control.getX(i);
  int yy=cp.control.getY(i);
  
  if (backlash && skeleton.isJunction(xx,yy) && 
      !smoothJunctions.contains(xx,yy)) {
    smoothJunctions.add(new Point2D(xx,yy));
    result.append(addBacklashTolerance(xx,yy,radius,
              millRate,plungeRate,millPlunge,millTransit));
  }

  int myArc=i+(reversed?-1:0);
  if (Math.abs(cp.arcRadius.get(myArc))>=MAXRADIUS) {
    result.append("G01 X"+
         String.format(fstr,  xOrigin+xPerPixel*cp.control.getX(i+inc))+
    " Y"+String.format(fstr,-(yOrigin+yPerPixel*cp.control.getY(i+inc)))+nL);
  } else {
    String hand=((xPerPixel<0)^reversed^(cp.arcRadius.get(myArc)>0.0))?"G03 ":"G02 "; 
    result.append(hand+"X"+ // Corrected thanks to jasoroony
          String.format(fstr,  xOrigin+xPerPixel*cp.control.getX(i+inc))+
     " Y"+String.format(fstr,-(yOrigin+yPerPixel*cp.control.getY(i+inc)))+
     " R"+String.format(fstr,yPerPixel*Math.abs(cp.arcRadius.get(myArc)))+nL);
// TODO later allow for different x,y scales
  }
} 
return new String(result);
}
// ------------------------------------------------------------------
void smoothedBits(Graphics2D g2d,Color colour,int thick) {
// Paints the arcs onto the BufferedImage in the relevant colour
// Only to be called after smoothing has completed - currently
// handled by caller - not especially safe if code is reused.

g2d.setColor(colour);

for (int i=0;i!=cp.getArcs();i++) {  

  CircularArc ca = new CircularArc(cp.arcRadius.get(i)>0.0,
    cp.control.get(i),cp.control.get(i+1),Math.abs(cp.arcRadius.get(i)));

  for (Point2D point : ca) 
    g2d.fillRect(point.getX()+(1-thick)/2,
                 point.getY()+(1-thick)/2, thick, thick);
} 
return;
}
// ------------------------------------------------------------------
String rawGcode(boolean reversed,boolean attached,int millRate,
     int plungeRate,double millPlunge,double millTransit,boolean backlash,
     double radius,Skeleton skeleton) {
// Returns the Gcode for the arc - which may be reversed
// and/or attached to the last segment - i.e. no transit required

StringBuilder result=new StringBuilder("");
int start,end,inc; 

if (reversed) {
  start=size()-1;
  end=0;
  inc=(-1);
} else {
  start=0;
  end=size()-1;
  inc=1;
}

if (!attached) {
  result.append("G00 Z"+millTransit+nL);
  result.append("G00 X"+
        String.format(fstr,  xOrigin+xPerPixel*getX(start))+
   " Y"+String.format(fstr,-(yOrigin+yPerPixel*getY(start)))+nL);
  result.append("F"+plungeRate+nL);
  result.append("G01 Z"+millPlunge+nL);
  result.append("F"+millRate+nL);
}
for (int i=start;i!=end;i+=inc) {
  int xx=getX(i);
  int yy=getY(i);

  if (backlash && skeleton.isJunction(xx,yy) && 
      !rawJunctions.contains(xx,yy)) {
    rawJunctions.add(new Point2D(xx,yy));
    result.append(addBacklashTolerance(xx,yy,radius,
              millRate,plungeRate,millPlunge,millTransit));
  }
  result.append("G01 X"+
       String.format(fstr,  xOrigin+xPerPixel*getX(i+inc))+
  " Y"+String.format(fstr,-(yOrigin+yPerPixel*getY(i+inc)))+nL);
} 
return new String(result);
} 
// ------------------------------------------------------------------
String addBacklashTolerance(int x,int y,double radius,
     int millRate,int plungeRate,double millPlunge,double millTransit) {
// Draws a circle centered at (x,y) to ensure that lines that should meet
// (either threeway junction or fourway) achieve electrical isolation
// in cases where machine inaccuracy/backlash means they do not
// exactly meet.

StringBuilder result=new StringBuilder("");

result.append("G00 Z"+millTransit+nL);
result.append("G00 X"+
        String.format(fstr,  xOrigin+xPerPixel*x+radius)+ // radius in mm so no scale
   " Y"+String.format(fstr,-(yOrigin+yPerPixel*y))+nL);
result.append("F"+plungeRate+nL);
result.append("G01 Z"+millPlunge+nL);
result.append("F"+millRate+nL);
result.append("G02 I"+String.format(fstr,(-radius))+nL);
result.append("G00 Z"+millTransit+nL);
result.append("G00 X"+
        String.format(fstr,  xOrigin+xPerPixel*x)+
   " Y"+String.format(fstr,-(yOrigin+yPerPixel*y))+nL);
result.append("F"+plungeRate+nL);
result.append("G01 Z"+millPlunge+nL);

return new String(result);
} 
// ------------------------------------------------------------------
// Helper class
private class ControlPoints
{
Points2D      control;
List<Double>  arcScore;
List<Integer> arcRadius;
double bestScore;
int bestRadius;

ControlPoints() {
// Construct 'control' points list s.t. there is always a control point
// on the original 2D route within the 'last touch' number of pixels
// from that pixel.  Guarantee that start and end of line are controls 
 
control   = new Points2D();
arcScore  = new ArrayList<Double>();
arcRadius = new ArrayList<Integer>();

if (size()==0) return;

control.add(get(0));   // Don't set score/radius as need 1 fewer than points

int lastCon=0;

for (int i=0;i<size();i++) {

  if (stop) return;

  if (2.0*dlength(control.getX(lastCon),control.getY(lastCon),
        getX(i),getY(i)) > minTouch(getX(i),getY(i))) {  
    lastCon++;
    control.add(get(i));
    arcScore.add(0.0); // dummy
    arcRadius.add(0);  // dummy
  }
}
if (control.getX(lastCon)!=getX(size()-1) ||
    control.getY(lastCon)!=getY(size()-1)) {
  control.add(get(size()-1));
  arcScore.add(0.0); // dummy
  arcRadius.add(0);  // dummy
} // Guarantee last point is present

}
// ------------------------------------------------------------------
int getPoints() { return control.size(); }
int getArcs()   { return (control.size()-1); } // An arc needs 2 ends.
// ------------------------------------------------------------------
boolean optimiseArc(Point2D start,Point2D end)
{ 
  boolean valid=false;
  int line=(int)(0.5+dlength(start.getX(),start.getY(),end.getX(),end.getY()));

  int radius=BASERADIUS;
  while (radius < line) radius=incrementRadius(radius); 
  bestRadius=radius;
  bestScore=-1000000.0;

  for (;radius<MAXRADIUS;radius=incrementRadius(radius)) {
    nexthanded:
    for (int handedness=0;handedness<2;handedness++) {

      CircularArc ca = new CircularArc(handedness==0,
                             start,end,radius);

      double score=0.0;
      for (Point2D point : ca) {  
        if (stop) return true; 
        int touched=lastTouch[point.getX()][point.getY()];
        if (touched==0) {
          score=-1.0;
          continue nexthanded; // Enforce avoidance of original copper
        }
        score+=(mapScore(touched));  
      } 

      valid=true;

      if (score > bestScore) {
        bestScore=score;
        bestRadius=(handedness==0)?radius:(-radius);
      } 
    }
  }
return valid; // Found at least one that didn't violate copper trace
}
// ------------------------------------------------------------------
private double mapScore(double score) { return score; } 
// return Math.pow(score,1.0); }
// Can vary the weighting function for route divergence
// ------------------------------------------------------------------
private int min(int a, int b) { return ((a<b)?a:b); }
// ------------------------------------------------------------------
private int minTouch(int x, int y) 
{ // The actual route doesn't change - so points on the route always 
  // record the maximum iterations, but the lowest of the adjacent 
  // cells is the relevant figure for the last change.   

int touch1=min(lastTouch[x-1][y-1],lastTouch[x-1][y]);
int touch2=min(lastTouch[x+1][y-1],lastTouch[x][y-1]);
int touch3=min(lastTouch[x+1][y+1],lastTouch[x+1][y]);
int touch4=min(lastTouch[x-1][y+1],lastTouch[x][y+1]);

int touch5=min(touch1,touch2);
int touch6=min(touch3,touch4);

return (min(touch5,touch6));
}
// ------------------------------------------------------------------
private int incrementRadius(int radius) { return ((radius*13)/10); }
// Geometric progression.  13/10 ~= cube root of 2, so every third is x2
// ------------------------------------------------------------------
private void replace(int start,int group,int radius,double score) 
{ // Joins the control points from start to start+group using a defined
  // radius and score.

for (int i=0;i<(group-1);i++) { // Remove (group-1) points ...
  control.remove(start+1);
  arcRadius.remove(start+1);
  arcScore.remove(start+1);
}
arcRadius.set(start,radius); // .. and adapt the startpoint
arcScore.set(start,score);
}

}
}
/*
Copyright (C) 2016-2018  S Combes

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
import java.awt.image.*; 
import java.io.*; 
import java.util.Date;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;

import org.w3c.dom.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;

class Skeleton {

// On construction, takes a binary image of a PCB (boolean, true when copper is absent) 
// and thins that image to a skeleton based on the even thinning of the non-copper
// elements (creates a Cellular Automaton to do this in PCBCellEvolve).  Identifies
// the points that are drill points and vertices that are 3- or 4-way (pixel nature 
// precludes more than 4-way junctions)

// Removes any dead ends - i.e. 'skeleton' consists of closed loops because only
// loops can create electrical isolation.  Beware if reusing class.

public Points2D drills;
public Points2D constellation;  // subset of drills as pattern for alignment
public Points2D threeWays;
public Points2D fourWays;
public Lines2D transits;

public int [][] lasttouch;
public int routes0w;       // Circuits

final static int DONE=(-1);
private int pass=0;

private final int width;
private final int height;
private boolean [][] binaryImg;
//protected volatile boolean stop = false;

public BufferedImage boardimg;
protected List<Route2D> routes;

static String nL = System.getProperty("line.separator");

// Data used to interpret automaton output : 
// Cells with 3 separated neighbours = three way junctions
static final int[] threes={0x1a , 0x32 , 0x31 , 0x25 , 0x35 , 0x33 , 0x3a ,
      0x52 , 0x51 , 0x45 , 0x55 , 0x53 , 0x58 , 0x4c , 0x5c , 0x4a , 0x4e , 
      0x5e , 0x59 , 0x4d , 0x5d , 0x5b , 0x72 , 0x71 , 0x65 , 0x75 , 0x73 , 
      0x7a , 0x85 , 0x8c , 0x8a , 0x9a , 0x8e , 0x8d , 0xa4 , 0xa2 , 0xb2 , 
      0xa6 , 0xa1 , 0xb1 , 0xb5 , 0xa3 , 0xb3 , 0xa7 , 0xac , 0xaa , 0xba , 
      0xae , 0xad , 0xc5 , 0xcc , 0xca , 0xda , 0xce , 0xcd , 0xe5};

// Patterns are encoded as 8 bits; representing the 8 neighbours of a pixel
// Counting from top left in 'book' order they are MSb (7) to LSb (0), 
// skipping the middle pixel (the one being assessed).

// Hence pattern in bit numbers is :   7 | 6 | 5
//                                     4 | x | 3
//                                     2 | 1 | 0

Skeleton(boolean [][] bimg,ExecutorService pool,Board board) {

this.binaryImg=bimg;

width=bimg.length;
height=bimg[0].length;

lasttouch=new int[width][height];

int last_change=0;

int [] rgbs=new int[9];

System.out.println("Thinning starting "+new Date());

int SWATH=640; // Arbitrary - large enough for low overhead, small enough to 
// balance tasks on different processors.  Likely multiple of image size.

for (pass=0;true;pass++) {

  if (board.stop) return;

  if ((pass-last_change) > 2) break; // All done when nothing changes for 2 gens

  boolean [][] nextBimg=new boolean[width][height];

  // Farm out vertical swathes queued on different processors 
  ArrayList<Future<Boolean>> changes = new ArrayList<Future<Boolean>>();  

  for (int i=1;i<width;i+=SWATH) { // Start @ 1, cells have implied surround
    int iend=i+SWATH;
    if (iend>(width-1)) iend=width-1; 
    boolean even=((pass%2)==0);
    changes.add(pool.submit(
      new PcbCellEvolve(even,binaryImg,nextBimg,lasttouch,i,iend,pass)));
  }

  try { 
    for (int i=0;i<changes.size();i++)
      if (changes.get(i).get()) last_change=pass; // Blocks until done.
  } // Not the prettiest exception handling
  catch (InterruptedException e) { System.out.println("Skeleton Int ERROR **** "+e); System.exit(0); }
  catch (ExecutionException e)   { System.out.println("Skeleton Exec ERROR **** "+e);e.printStackTrace();  System.exit(0); } 

  binaryImg=nextBimg;

  /* //  For animation
  BufferedImage b = new BufferedImage(width, 
        height, BufferedImage.TYPE_3BYTE_BGR);
  writeBimg(b);
  // end animation */
        
  if ((pass%10)==0) System.out.print("\nGENERATION : "+pass+" "+new Date());
  else              System.out.print(" .");
  board.gen.setText(String.format("Automata generation %d",(pass+1)));
}
pass=DONE;
// ------------------------------------------------------------
// Now extract the traces we need to mill ...

// On a grid of pixels there are only a few things that can happen, in terms of
// the number of lines that lead away from a pixel (defined by the immediate 8
// surrounding pixels):
//   0 Singleton dot - a drillpoint
//   1 Dead-end.  A line end.  Don't exist until we have removed some three-ways.
//   2 In-and-out : middle of a line.
//   3 Three-way junction
//   4 Four-way junction.

drills       =new Points2D(); 
constellation=new Points2D(); 
threeWays    =new Points2D();
fourWays     =new Points2D();

for (int y=1;y<(height-1);y++) { 
  if (board.stop) return;

  for (int x=1;x<(width-1);x++) {      
    if (binaryImg[x][y]) { 
      int abyte=getByte(x,y);  
      if (isThreeway(abyte))      threeWays.add(new Point2D(x,y));
      else if (isFourway(abyte))  fourWays.add(new Point2D(x,y));
      else if (abyte==0)          drills.add(new Point2D(x,y));
    }
  }
}
routes = new ArrayList<Route2D>();

// Now add the thermal relief cuts.  Intended to allow easier soldering, as
// soldering to a large area of copper is hard.

// Each is a set of arcs, egually spaced around a drill point at a given
// radius.  We do this before we destroy the binaryImage[][] because we can
// use the image to avoid the arcs being drawn if we are close to a cut anyway

if (board.relieved) {
  int radius=board.xDPI/31;  // pixels
  for (Point2D d : drills) { 
    Points2D nearbyDrills=new Points2D();
    for (Point2D another : drills) {
      if (!another.equals(d)) {
        if (d.distance(another)<2.1*radius)
          nearbyDrills.add(another);
      }
    }
    for (int segments=0;segments<2;segments++) { // Currently only works if segments=2
      QuantisedCircularArc ca=new QuantisedCircularArc(true,
                        new Point2D(d.getX(),d.getY()+radius*(2*segments-1)),
                        new Point2D(d.getX()+radius*(2*segments-1),d.getY()),radius);
      Route2D route=new Route2D();
      for (Point2D point : ca) {
        // Only allow that pixel to be drawn if it is closer to its own drill centre than
        // to any other and we have a clear run from the drill centre to
        // a certain factor beyond the pixel.  Prevents arcs cutting the existing traces
        // and prevents them being wastefully close to the traces.        
        boolean clear=true;
        for (Point2D near : nearbyDrills)
          clear&=(near.distance(point)>radius);
       
        Point2D extended=new Point2D(d.getX()+(int)(0.5+1.7*(point.getX()-d.getX())),
                                     d.getY()+(int)(0.5+1.7*(point.getY()-d.getY())));
        QuantisedLine ql=new QuantisedLine(d,extended); // centre to +70% behind the arc
        for (Point2D p : ql) {
          try {
            clear&=(!binaryImg[p.getX()][p.getY()]);
          } catch (ArrayIndexOutOfBoundsException e) { clear=false; /* off edge */ } 
        }
        if (clear) route.add(point);  
        else {
          if (route.size()>5) { // Keep reasonably sized orphans ...
            routes.add(route);
          }
          route=new Route2D();  // ... and prepare next route        
        }
      }
      if (route.size()>5)
        routes.add(route);
    }
  }
}

// Now walk from the start points to get routes

for (Point2D point : threeWays) {
  if (board.stop) return;

  for (int j=0;j<3;j++) { // Worst case - rip all 3 lines from a 3-way junction
    Route2D route=ripLine(point);
    if (route.size()>1) routes.add(route);
  }
}
for (Point2D point : fourWays) {
  if (board.stop) return;
  for (int j=0;j<4;j++) { // Worst case - rip all 4 lines from a 4-way junction
    Route2D route=ripLine(point);
    if (route.size()>1) routes.add(route);
  }
}
// Finding remaining closed-loops;
// Usually only an outer circuit as inner loops collapse to points

routes0w=0;
for (int y=1;y<(height-1);y++) { 
if (board.stop) return;

  for (int x=1;x<(width-1);x++) {      
    if (binaryImg[x][y]) { 
        Route2D route=ripLine(new Point2D(x,y));
        if (route.size()>4) { // drill points will be rejected 
          routes.add(route);
          routes0w++; // A count of loops is a useful diagnostic
        }
    }
  }
}




transits=new Lines2D(endPairs());  // For mill route optimisation

// Setup a subset of drill points to assist with board alignment on
// a machine (especially to align two sided boards)
// Look for fairly extreme points to give good orientation
// Includes all those that are at the extreme E,N,S and W positions and
// (as long as points exist in those quadrants) the longest NE-SW and
// NW-SE diagonals

int minx=Integer.MAX_VALUE;
int maxx=Integer.MIN_VALUE;
int miny=Integer.MAX_VALUE;
int maxy=Integer.MIN_VALUE;

for (Point2D d : drills) {  // Bounding box
  if (d.getX()>maxx) maxx=d.getX();
  if (d.getX()<minx) minx=d.getX();
  if (d.getY()>maxy) maxy=d.getY();
  if (d.getY()<miny) miny=d.getY();
}
int maxNWSE=0;
int maxSWNE=0;
Point2D NW=null;
Point2D NE=null;
Point2D SW=null;
Point2D SE=null;

for (Point2D d0 : drills) {  
  if (d0.getX()>((minx+maxx)/2)) continue; // Want one on LHS
  for (Point2D d1 : drills) {  
    if (d1.getX()<((minx+maxx)/2)) continue; // Want one on RHS
    int dist=(int)(0.5+Math.sqrt(Math.pow(d0.getX()-d1.getX(),2)+Math.pow(d0.getY()-d1.getY(),2)));
    if (d0.getY()>((miny+maxy)/2)) {  // SW
      if (dist>maxSWNE) {
        SW=new Point2D(d0);
        NE=new Point2D(d1);
        maxSWNE=dist;
      }  
    } else { // NW
      if (dist>maxNWSE) {
        NW=new Point2D(d0);
        SE=new Point2D(d1);
        maxNWSE=dist;
      }  
    }
  }
}
if (NW!=null) constellation.add(NW);  
if (SE!=null) constellation.add(SE);  
if (SW!=null) constellation.add(SW);  
if (NE!=null) constellation.add(NE);  
for (Point2D d : drills) {  
  if (d.getX()==minx || d.getX()==maxx ||
      d.getY()==miny || d.getY()==maxy) {
    constellation.add(d);          
  }
}
}
// ------------------------------------------------------------------------
public final Points2D endPairs() 
{
// Make paired route for Travelling Salesman optimisation.  Optimiser 
// only needs to know starts and ends (because the route itself isn't 
// optimised by the Travelling Salesman routine).  Places start points
// at even indices (0,2,4 ...) End points at following odd number (1,2,5 ...)

Points2D pairs=new Points2D();

for (Route2D route : routes) {
  pairs.add(route.get(0));               // Start
  pairs.add(route.get(route.size()-1));  // End
}

return pairs;
}
// ---------------------------------------------------------------
private Route2D ripLine(Point2D point) 
{
// Extract a line from the boolean array, hoovering it up as we go. 
// We are at a point on that line = (x,y) as a start.  
// We can rely on the line being 1-pixel wide and hence hoovering 
// needs few special cases to ensure it is total.
// Line is true, background is false.

Route2D trace = new Route2D();
trace.add(point);
int x=point.getX();
int y=point.getY();
int initX=x;         // Keep to reinstate the vertex later
int initY=y;

while (true) {

  // Proceed via cardinal points from N, clockwise then via
  // quarters from NE, clockwise.  Ensures that we always hit the
  // root of a 3- or 4-way intersection without glancing within 1 pixel. 
  binaryImg[x][y]=false; // Roll up line behind us to prevents revisits

  if      (binaryImg[x][y-1])   {       y-=1; trace.add(new Point2D(x,y)); } // N
  else if (binaryImg[x+1][y])   { x+=1;       trace.add(new Point2D(x,y)); } // E
  else if (binaryImg[x][y+1])   {       y+=1; trace.add(new Point2D(x,y)); } // S
  else if (binaryImg[x-1][y])   { x-=1;       trace.add(new Point2D(x,y)); } // W
  else if (binaryImg[x+1][y-1]) { x+=1; y-=1; trace.add(new Point2D(x,y)); } // NE
  else if (binaryImg[x+1][y+1]) { x+=1; y+=1; trace.add(new Point2D(x,y)); } // SE
  else if (binaryImg[x-1][y+1]) { x-=1; y+=1; trace.add(new Point2D(x,y)); } // SW
  else if (binaryImg[x-1][y-1]) { x-=1; y-=1; trace.add(new Point2D(x,y)); } // NW
  else break; // end of the line : space all around us
  
  if (knownThreeway(x,y) || knownFourway(x,y)) break;   // A vertex 
  // we've already found, hence a good place to end the line, as 
  // the optimiser can later mix and match the lines between junctions
}

binaryImg[initX][initY]=true; // Reinstate vertex in case it's on another route

return trace;
}
// ---------------------------------------------------------------
public void drawRoutes(BufferedImage board,int colour)
{
for (Route2D route : routes) 
  for (Point2D point : route) 
    board.setRGB(point.getX(),point.getY(),colour);
}
// ---------------------------------------------------------------
private int getByte(int x,int y) { 
// Make a test byte from the surrounding pattern in the array
    return ((binaryImg[x-1][y-1]?1<<7:0) | (binaryImg[x][y-1]  ?1<<6:0) |
            (binaryImg[x+1][y-1]?1<<5:0) | (binaryImg[x-1][y]  ?1<<4:0) |
            (binaryImg[x+1][y]  ?1<<3:0) | (binaryImg[x-1][y+1]?1<<2:0) |
            (binaryImg[x][y+1]  ?1<<1:0) | (binaryImg[x+1][y+1]?1:0));  
} // Returns int, but only uses LS 8 bits - hence 'getByte'
// ---------------------------------------------------------------
private boolean knownThreeway(int x,int y) 
{ // Is there a 3-way intersection we've already found at x,y?
  for (Point2D tw : threeWays) 
    if (x==tw.getX() && y==tw.getY())
      return true;

  return false;
}
// ---------------------------------------------------------------
private boolean knownFourway(int x,int y) 
{ // A four-way intersection we've already found?
  for (Point2D fw : fourWays) 
    if (x==fw.getX() && y==fw.getY())
      return true;

  return false;
}
// --------------------------------------------------------------
private static boolean isFourway(int abyte) 
          {  return (abyte==0x5A || abyte==0xA5 || abyte==0xD1); }
// ---------------------------------------------------------------
private static boolean isThreeway(int abyte) 
{// Is the pattern a 3-way intersection?
  for (int i : threes) 
    if (abyte==i)
      return true;

  return false;
}
// ---------------------------------------------------------------
public boolean isJunction (int x,int y) 
           { return (knownThreeway(x,y) || knownFourway(x,y)); }
// --------------------------------------------------------------
public int getIteration()  { return pass; }

// Below is temp debug for animation
// ---------------------------------------------------------------
private void writeFile(BufferedImage image) 
{
ImageWriter writer;
writer=ImageIO.getImageWritersBySuffix("jpeg").next();
try {
  File outputfile = new File("Iter"+Integer.toString(pass)+".jpg");

  FileOutputStream fos=new FileOutputStream(outputfile);
  ImageOutputStream ios=ImageIO.createImageOutputStream(fos); // Creating ios directly gets null!
  ImageWriteParam jpegParams=writer.getDefaultWriteParam();

  IIOMetadata data=writer.getDefaultImageMetadata(new ImageTypeSpecifier(image),jpegParams);
  Element tree=(Element)data.getAsTree("javax_imageio_jpeg_image_1.0");
  Element jfif=(Element)tree.getElementsByTagName("app0JFIF").item(0);
  jfif.setAttribute("Xdensity", Integer.toString(300));
  jfif.setAttribute("Ydensity", Integer.toString(300));
  jfif.setAttribute("resUnits", "1"); // density is dots per inch                 
  data.mergeTree("javax_imageio_jpeg_image_1.0",tree);

  writer.setOutput(ios);

  writer.write(data,new IIOImage(image,null,data),jpegParams);
  ios.flush();
  ios.close();

} catch (IOException e) { }
}
// ---------------------------------------------------------------
private void writeBimg(BufferedImage img) // Write from the binary image
{
for (int y=0;y<height;y++) 
  for (int x=0;x<width;x++)  
    img.setRGB(x,y,binaryImg[x][y]?0:0xFFFFFF);
writeFile(img);
}
}
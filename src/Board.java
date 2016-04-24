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

import org.w3c.dom.*;
import java.awt.*;
import javax.swing.*;
import java.awt.image.*; 
import java.awt.event.*; 
import java.io.*; 
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;
import java.util.Date;
import java.lang.Thread;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList; 
import java.util.Observable;

class Board extends Observable implements Runnable {

// Main object within Cisolate program, represents a Board and actions upon it
// but excludes GUI, which gets Observer notifications

static final int THRESHOLD=30;  // Pixel considered black if all of RGB less than this out of 255.
static final int CU_WHITE=1;
static final int CU_BLACK=2;
static final int CU_GUESS=0;
static String nL = System.getProperty("line.separator");
static final String defaultG="G17 G40 G49 G80 G90";
// G17=x-y plane.  G40=radius compensation off.  G49=length comp off.
// G80=cancel canned cycle  G90=absolute coordinates

public BufferedImage  img;    // working, e.g. result of writeBimg
// **** Always create images in img and copy to final (e.g. drill) when complete
// so that caller can treat any non-null image as ready, and we only need one Graphics2D context
public BufferedImage board,cuts,drill,drillPath,heat,junction,millPath,pitch, 
                     duke,etched; 
public boolean complete=false;
public Skeleton skeleton;
 
private String fname;
private boolean [][] bimg;
int xsize,ysize;
private File mydir;
private File file;
boolean verbose,raw,overwrite;
int tsd,tsm,copper,maxprocs,etch;
boolean smoothEtch=false;  
private double xmmPerPixel;
private double ymmPerPixel;  
private double xoffset=0.0;
private double yoffset=0.0;  // Board coordinates of BL pixel (mm)
private int forceDPI;
private int xDPI,yDPI;
private int resUnits;
private int Xdensity;
private int Ydensity;
private ImageWriter writer;
private String descriptive;
public StringBuilder log;
StringBuffer imgProperties;
public boolean millCode=true;
public boolean drillCode=true;
public int assessedCu=CU_GUESS;
private volatile boolean stop = false;
Anneal anneal,annealPair;
//Progress progress;

final static int CLOSEST=0;
final static int TENTHINCH=1;

// ---------------------------------------------------------------
Board(File file) //,Progress progress)
  { this(file,true,2,2,CU_GUESS,1,true,3,true,0); } //,progress); } // TESTING VALUES
// ---------------------------------------------------------------
Board(String fname) 
    { this(new File(fname),true,2,2,CU_GUESS,1,true,3,true,0); } // TESTING VALUES

Board(String fname,boolean verbose,int tsd,int tsm,int copper,
    int maxprocs,boolean raw,int etch,boolean overwrite,int forceDPI) {
  this(new File(fname),verbose,tsd,tsm,copper,maxprocs,raw,etch,overwrite,forceDPI); 
}

Board(File file,boolean verbose,int tsd,int tsm,int copper,
    int maxprocs,boolean raw,int etch,boolean overwrite,int forceDPI) 
{
this.file=file;
this.fname=file.getName();
this.verbose=verbose;
this.tsm=tsm;
this.tsd=tsd;
this.maxprocs=maxprocs;
this.copper=copper;
this.raw=raw;
this.etch=etch;
this.overwrite=overwrite;
this.forceDPI=forceDPI;
//this.progress=progress;

log=new StringBuilder();
imgProperties=new StringBuffer();

String ext = fname.substring(fname.indexOf(".")+1).toLowerCase();  

ImageReader reader=ImageIO.getImageReadersBySuffix(ext).next();
writer=ImageIO.getImageWritersBySuffix("jpeg").next();
System.out.println("Reading : "+fname);
log.append("Based on : "+fname+nL);
imgProperties.append("File : "+fname+nL);

if (ext.equals("jpg")) {

  try {  
    reader.setInput(ImageIO.createImageInputStream(file)); 

    IIOMetadata metadata = reader.getImageMetadata(0);

    String[] names = metadata.getMetadataFormatNames();

    for (int i=0;i<names.length;i++) {
      if (names[i].substring(0,Math.min(13,names[i].length()))
          .equals("javax_imageio")) {

        Node node=metadata.getAsTree(names[i]);
        Node child=node.getFirstChild();
        while (child!=null) {
          if (child.getNodeName().equals("Dimension")) {

            Node grandchild=child.getFirstChild();
            while (grandchild!=null) {
              if (grandchild.getNodeName().equals("HorizontalPixelSize")) 
                xmmPerPixel=extractAttribute(grandchild,"value");
              else if (grandchild.getNodeName().equals("VerticalPixelSize")) 
                ymmPerPixel=extractAttribute(grandchild,"value");
              grandchild=grandchild.getNextSibling();
            }
          } 
        child=child.getNextSibling();
        }        
      }
    }
  } catch (IOException e) { System.out.println("File Read Error"); e.printStackTrace(); System.exit(0);} 

} else if (ext.equals("bmp")) {  // Metadata seems not to work.  Grab from raw file.

  try {
    DataInputStream dis=new DataInputStream(
                          new BufferedInputStream(
                            new FileInputStream(file)));

    dis.skipBytes(38);

    xmmPerPixel=1000.0/endianism(dis.readInt());
    ymmPerPixel=1000.0/endianism(dis.readInt());

    dis.close();

  } catch (IOException e) { System.out.println("File Read Error"); System.exit(0);} 
} 
else if (forceDPI == 0) {
  System.out.println("Currently only supports .jpg and .bmp unless DPI is forced.  Exiting");
  System.exit(0);
}

if (xmmPerPixel > 0.0001 && ymmPerPixel > 0.0001)
{
  xDPI=(int)(0.5+25.4/xmmPerPixel);
  yDPI=(int)(0.5+25.4/ymmPerPixel);
  System.out.println(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI");
  log.append(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI"+nL);
  imgProperties.append(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI"+nL);
} 
else if (forceDPI == 0) {
  System.out.println("Unable to find plausible DPI.  Exiting");
  System.exit(0);
} 

if (forceDPI !=0) {
  xmmPerPixel=25.4/forceDPI;
  ymmPerPixel=25.4/forceDPI;
  xDPI=forceDPI;
  yDPI=forceDPI;
  System.out.println("*********** Forcing DPI to be "+forceDPI);
  log.append("DPI was forced to be "+forceDPI+nL);
}
 
try { img = ImageIO.read(file); }
catch (IOException e) { System.out.println("File Read Error"); System.exit(0);} 

board=makeImage(img);

long black=0;
long total=img.getWidth()*(long)img.getHeight();

for (int i=0;i<img.getWidth();i++)
  for (int j=0;j<img.getHeight();j++)
    if (isBlack(img.getRGB(i,j))) black++;
  
double bwratio=(double)black/total;
assessedCu=(bwratio<0.5)?CU_BLACK:CU_WHITE;  // Less black than white => Black probably Cu

if (copper==CU_GUESS) {
  this.copper=assessedCu;  
  System.out.println(nL+"Assessed that the copper is "+((this.copper==CU_BLACK)?"black":"white"));
  System.out.println("but it is safer if you specify with options -cb or -cw."+nL);
  log.append("Assessed that the copper is "+((this.copper==CU_BLACK)?"black":"white")+nL);
  imgProperties.append("Assessed that the copper is "+((this.copper==CU_BLACK)?"black":"white")+nL);
}
else if ((bwratio<0.5 && copper==CU_WHITE) ||
         (bwratio>0.5 && copper==CU_BLACK))
 {  
  System.out.println("*** You specified the copper was "+((copper==CU_BLACK)?"black":"white")+
     " but I would have guessed the opposite.  May be error. ***"+nL);
  log.append("*** You specified the copper was "+((copper==CU_BLACK)?"black":"white")+
     " but I would have guessed the opposite.  May be error. ***"+nL);
}
else {
  System.out.println("You specified the copper was "+((copper==CU_BLACK)?"black":"white")+nL);
  log.append("You specified the copper was "+((copper==CU_BLACK)?"black":"white")+nL);
}

Toolkit toolkit =  Toolkit.getDefaultToolkit();
Dimension dim = toolkit.getScreenSize();
double scalex=(double)img.getWidth()/(double)(dim.width-50);   
double scaley=(double)img.getHeight()/(double)(dim.height-150); 

double scale=(scalex<scaley)?scaley:scalex;

if (scale<1.0) scale=1.0;

xsize=(int)(img.getWidth()/scale);
ysize=(int)(img.getHeight()/scale);

descriptive=String.format("Board is approximately %.1f x %.1f inches",
          (double)img.getWidth()/xDPI,(double)img.getHeight()/yDPI);

System.out.println(descriptive); // Mostly to trap obvious bloopers
log.append(descriptive+nL);
imgProperties.append(descriptive+nL);

// mydir = filename pre extension tagged on our path
String absPath = file.getAbsolutePath();

mydir = new File(absPath.substring(0,absPath.lastIndexOf(File.separator)+1)+
                 fname.substring(0,fname.indexOf(".")));  
System.out.println(mydir);
if (!mydir.exists())
  mydir.mkdir();  

}
// ---------------------------------------------------------------
public void aChange() 
{ 
setChanged();       // Time to update GUI
notifyObservers(); 
}
// ---------------------------------------------------------------
public static BufferedImage makeImage(BufferedImage source) {
  BufferedImage b = new BufferedImage(source.getWidth(), 
        source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
  Graphics g = b.getGraphics();
  g.drawImage(source,0,0,null);
  g.dispose();
  return b;
}
// ---------------------------------------------------------------
public static void copyImage(BufferedImage source,BufferedImage dest) {
  Graphics g = dest.getGraphics();
  g.drawImage(source,0,0,null);
  g.dispose();
  return;
}
// ---------------------------------------------------------------
private int endianism(int i) // 32 bit endiamism swap
{
return ((i&0xFF000000)>>24) | ((i&0x00FF0000)>>8) | 
       ((i&0x0000FF00)<<8)  | ((i&0x000000FF)<<24);
}
// ---------------------------------------------------------------
private double extractAttribute(Node node,String attrib)
{
double result=0.0;
NamedNodeMap map=node.getAttributes();
if (map!=null) {
  for (int j=0;j<map.getLength();j++) {
    Node attribute=map.item(j);
    String name=attribute.getNodeName();
    if (name.equals(attrib))  
      result=Double.parseDouble(attribute.getNodeValue());
  }
}
return result;
}
// ---------------------------------------------------------------
private void overwriteFail(String f) 
{
System.out.println("File already exists "+f+nL+
  "Can force overwrites with -o option"+nL+"Exiting.");
System.exit(0);
}
// ---------------------------------------------------------------
private void writeFile(String name,BufferedImage image) 
{
if (!verbose) return;

// ideas from http://stackoverflow.com/questions/8618778/storing-dpi-and-paper-size-information-in-a-jpeg-with-java
// but needed some modding!

String f=mydir+File.separator+name+".jpg";

try {
  File outputfile = new File(f);
  if (!overwrite && outputfile.exists()) overwriteFail(f);  

  FileOutputStream fos=new FileOutputStream(f);
  ImageOutputStream ios=ImageIO.createImageOutputStream(fos); // Creating ios directly gets null!
  ImageWriteParam jpegParams = writer.getDefaultWriteParam();

  IIOMetadata data = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), jpegParams);
  Element tree = (Element)data.getAsTree("javax_imageio_jpeg_image_1.0");
  Element jfif = (Element)tree.getElementsByTagName("app0JFIF").item(0);
  jfif.setAttribute("Xdensity", Integer.toString(xDPI));
  jfif.setAttribute("Ydensity", Integer.toString(yDPI));
  jfif.setAttribute("resUnits", "1"); // density is dots per inch                 
  data.mergeTree("javax_imageio_jpeg_image_1.0",tree);

  writer.setOutput(ios);

  IIOImage iio=new IIOImage(image,null,data);
  writer.write(data, new IIOImage(image, null, data), jpegParams);
  ios.flush();
  ios.close();

} catch (IOException e) { }

}
// ---------------------------------------------------------------
private void writeBimg(String name) // Write from the binary image
{
for (int y=0;y<img.getHeight();y++) 
  for (int x=0;x<img.getWidth();x++)  
    img.setRGB(x,y,bimg[x][y]?0:0xFFFFFF);
writeFile(name,img);
}
// ---------------------------------------------------------------
private void writeTouch(String name) 
{ // Write out the heat map of the last 'touch' points - highlights
//   how much scope there is to vary the milling cut line.
for (int y=0;y<img.getHeight();y++) 
  for (int x=0;x<img.getWidth();x++) {
    int a=(int)(0.5+16.0*(Math.sqrt(skeleton.lasttouch[x][y])));
    a=(a>255)?255:a;
    img.setRGB(x,y,(a<<16)|(a<<8)|a);
  }
if (!verbose) return;
writeFile(name,img);
}
// ---------------------------------------------------------------
boolean isBlack(int pix)
{  return (((pix & 0xFF) < THRESHOLD && ((pix>>8)&0xFF) < THRESHOLD && 
           ((pix>>16)&0xFF) < THRESHOLD));
}
// ---------------------------------------------------------------
void drawCircles(Points2D points, Graphics2D g2d,Color c,int d) 
{ 
g2d.setColor(c);

for (int i=0;i<points.size();i++) 
  g2d.fillOval(points.getX(i)-(d+1)/2,points.getY(i)-(d+1)/2,d,d);
}
// ---------------------------------------------------------------
void writeDrillCode(Points2D points,PrintWriter pw,double clear,
                    double plunge)
{
// Note that Screen Y (and hence internal representation) moves from top down and 
// GCode Y moves from Bottom up - annoying

if (pw==null) return;
 
pw.println("G00 Z"+String.format("%.3f",clear));

PathOrder po=skeleton.drills.optimum;

for (int i=0;i<points.size();i++) {
  pw.println("G00 X"+String.format("%.3f",
              xoffset+skeleton.drills.getX(po.mapping(i))*xmmPerPixel)+
             "    Y"+String.format("%.3f",
              -(yoffset+skeleton.drills.getY(po.mapping(i))*ymmPerPixel)));  
  pw.println("G01 Z"+String.format("%.3f",plunge));
  pw.println("G00 Z"+String.format("%.3f",clear));  
} 
}
// ---------------------------------------------------------------
Points2D [] detectPitch(Points2D drill) {  
// Detect drill points that appear to be on 0.1mm spacing
// and also return the smallest drill point spacing
// Aim to correlate this with specified DPI
  
int [] nearest=new int[drill.size()];
int [] nearSqDist=new int[drill.size()];
int smallestSq=Integer.MAX_VALUE;

Points2D [] found=new Points2D[2];

for (int i=0;i<drill.size();i++) {
  nearest[i]=-1;  // The TR drill point should stay as -1.
  for (int j=0;j<drill.size();j++) {
    if (i==j) continue;
    int deltaX=drill.getX(i)-drill.getX(j);
    int deltaY=drill.getY(i)-drill.getY(j);
    if (deltaX < 0 || deltaY < 0) continue;  // Note < not <=

    int dist=deltaX*deltaX+deltaY*deltaY;
    if (nearSqDist[i]==0 || nearSqDist[i]>dist) {
      nearest[i]=j;
      nearSqDist[i]=dist;
      if (smallestSq>dist) smallestSq=dist;
    }
  }
}
double smallest=Math.pow((double)smallestSq,0.5)*xmmPerPixel/25.4;  // TODO presumes x=y

String tmp=String.format("Smallest drill-point separation = %.2f\"",smallest);

System.out.println(tmp);
log.append(tmp+nL);

found[CLOSEST]=new Points2D();
found[TENTHINCH]=new Points2D();

int [] histogram=new int[2+(int)Math.pow(49*smallestSq,0.5)];

int expectedNext=0;
int expectedSqPitch=(xDPI*xDPI)/100; // 0.1" squared
for (int i=0;i<drill.size();i++) {
  double r=(double)nearSqDist[i]/expectedSqPitch;
  if (0.95 < r && 1.05 > r) { // Close to 0.1" pitch
    found[TENTHINCH].add(drill.get(i));
    found[TENTHINCH].add(drill.get(nearest[i]));  // Will lead to duplicates.  Doesn't matter
    expectedNext++;
  }
  else if (nearSqDist[i]==smallestSq) {
    found[CLOSEST].add(drill.get(i));
    found[CLOSEST].add(drill.get(nearest[i]));  // Will lead to duplicates.  Doesn't matter
  }
  if (nearSqDist[i]<(49*smallestSq)) 
    histogram[(int)(0.5+Math.pow(nearSqDist[i],0.5))]++;
}
int hmax=0;
int index=0;
for (int i=0;i<histogram.length;i++) {
  if (histogram[i]>hmax) {
    hmax=histogram[i];
    index=i;
  }
}

tmp=(String.format("%.1f ",(double)expectedNext*100.0/drill.size())+
         "% of drill points are on 0.1\" grid");
System.out.println(tmp);
log.append(tmp+nL);

tmp="Based on commonest nearest neighbours, estimate of DPI is "+(index*10)+nL;
if (Math.abs(index*10-xDPI)>1) {
  tmp+="*** Conflict with expected DPI of "+xDPI+". You may want to re-run with -f"+(index*10)+nL;
  tmp+="*** Looking at pitches.jpg for more details.  Closest points are amber."+nL;
  tmp+="*** Points whose closest neighbout is at 0.1\" pitch are green."; 
  System.out.println(tmp);
  log.append(tmp+nL); 
}

return found;
}
// ---------------------------------------------------------------
public void gracefulExit() { 
  stop = true; 
  if (skeleton!=null)
    skeleton.gracefulExit();
  if (anneal!=null)
    anneal.gracefulExit(); 
  if (annealPair!=null)
    annealPair.gracefulExit(); 
//  for (int i=0;i<routes.size();i++) // ??? TODO
  //  routes.get(i).gracefulExit();
}
// ------------------------------------------------------------
private void drawEtch(Graphics2D g2d) {

g2d.setColor(Color.WHITE);
g2d.fillRect(0,0,img.getWidth(),img.getHeight());
g2d.setColor(Color.BLACK);

if (smoothEtch)
  for (Route2D route : skeleton.routes) 
    route.smoothedBits(g2d,Color.BLACK,etch);
else 
  for (Route2D route : skeleton.routes) 
    for (Point2D point : route)
      g2d.fillRect(point.getX()-etch/2,point.getY()-etch/2,etch,etch);

// Cheat, because etch overrides verbose
boolean tmp=verbose;
verbose=true;
etched=makeImage(img);
writeFile("etch",etched);
verbose=tmp;
}
// ------------------------------------------------------------
private void drawDuke(Graphics2D g2d) {

g2d.setColor(Color.WHITE);
g2d.fillRect(0,0,img.getWidth(),img.getHeight());

for (Route2D route : skeleton.routes) 
  route.smoothedBits(g2d,Color.RED,2);

for (Route2D route : skeleton.routes) 
  for (Point2D point : route)
    img.setRGB(point.getX(),point.getY(),0x0000FF);

duke=makeImage(img);
}
// ---------------------------------------------------------------
public void run()
{

stop=false;

writeFile("board",board);  // Echos startpoint out for the record

if (!millCode)  tsm=0;  // Zero optimisations if we're not doing the code
if (!drillCode) tsd=0; 

bimg=new boolean[img.getWidth()][img.getHeight()];
Graphics2D g2d = img.createGraphics();

for (int y=0;y<img.getHeight();y++) 
  for (int x=0;x<img.getWidth();x++) 
    bimg[x][y]=((copper==CU_BLACK)^isBlack(img.getRGB(x,y)));

if (maxprocs>Runtime.getRuntime().availableProcessors())
  maxprocs=Runtime.getRuntime().availableProcessors();
ExecutorService pool=
       Executors.newFixedThreadPool(maxprocs);

if (stop) return;
skeleton=new Skeleton(bimg,pool);
Route2D.initialise(skeleton.lasttouch,xoffset,yoffset,xmmPerPixel,ymmPerPixel);

writeTouch("heat");
heat=makeImage(img);

g2d.setColor(Color.WHITE);
g2d.fillRect(0,0,img.getWidth(),img.getHeight());

skeleton.drawRoutes(img,0x000000);
drawCircles(skeleton.threeWays,g2d,Color.BLUE,8);     
drawCircles(skeleton.fourWays,g2d,Color.MAGENTA,8);   
junction=makeImage(img);
writeFile("junctions",junction);

copyImage(board,img);
skeleton.drawRoutes(img,0x00FF00);
cuts=makeImage(img);
writeFile("cuts",cuts);

copyImage(board,img);
drawCircles(skeleton.drills,g2d,Color.MAGENTA,8);  
drill=makeImage(img);
writeFile("drill",drill);

if (!smoothEtch && etch>0) drawEtch(g2d); // Can have it now if not smoothed

setChanged();       // Time to update GUI
notifyObservers();

System.out.println("\n\n"+skeleton.drills.size()+" drill points found");
log.append(skeleton.drills.size()+" drill points found"+nL);
System.out.println(skeleton.threeWays.size()+" three-way intersections found");
System.out.println(skeleton.fourWays.size()+" four-way intersections found\n");

// ------------------------------------------------------------
// Initiate thread to optimise drilling order

anneal=new Anneal(skeleton.drills, // last two params are tuneable
              new PathOrder(skeleton.drills.size()),tsd,0.002,0.9);

Future< ? > tsDoneFuture;
tsDoneFuture=pool.submit(anneal);
if (stop) anneal.gracefulExit(); 

boolean drillPathDone=false;
// ------------------------------------------------------------
// Kick off the milling paths optimisation thread

annealPair=
      new Anneal(skeleton.transits, // last two params are tuneable
         new PathPairOrder(skeleton.endPairs().size()),tsm,0.002,0.9);
Future< ? > tspDoneFuture;
if (tsm==0)
  System.out.println("No mill order optimisation requested");
else
  System.out.println("Mill order optimisation starting "+new Date());
tspDoneFuture=pool.submit(annealPair);

if (stop) annealPair.gracefulExit();

System.out.println("\nFound "+skeleton.routes.size()+" routes to optimise");

if ((skeleton.drills.size()/(skeleton.routes0w+1)) < 20.0) { 
// ratio of drill points to circuits (+1 to avoid div by zero)
  System.out.println("*** The ratio between drill points and circuits "+
          "suggests the copper colour may be wrongly assigned ***");
  log.append("*** The ratio between drill points and circuits suggests "+
          "the copper colour may be wrongly assigned ***"+nL);
}

// ------------------------------------------------------------
// Put the jobs for route smoothing into the pool
ArrayList<Future<?>> route_done_future = new ArrayList<Future<?>>();  
int bigIndex=0;
double taskSize=0.0; // used for % complete
int [] submitOrder=new int[skeleton.routes.size()];
boolean [] done=new boolean[skeleton.routes.size()];

if (!raw && millCode) {
  System.out.println("Mill path smoothing starting at "+new Date());

  int index=0;
  // The biggest one takes >> longer than the others.  For parallel 
  // processing efficiency, start this one first.  A bit overkill.
  int maxLen=0;
  for (int i=0;i<skeleton.routes.size();i++) {
    if (skeleton.routes.get(i).size()>maxLen) {
      maxLen=skeleton.routes.get(i).size(); 
      bigIndex=i; 
    } 
    taskSize+=(Math.pow(skeleton.routes.get(i).size(),2));
  }
  if (stop) return;
  route_done_future.add(pool.submit(skeleton.routes.get(bigIndex))); // Biggest one
  submitOrder[bigIndex]=index++;

  // Put the rest in, never having more than max processors running
  // While optimisations are still running, we will have maxprocs+2
  // threads going

  double complete=0.0;
  try {
    for (int i=0;i<skeleton.routes.size();i++) {
      if (i!=bigIndex) {
        do {
          int active=0;
          for (int j=0;j<index;j++) {
            if (done[j]) continue;

/*            if (!drillPathDone) {

              if (tsDoneFuture.isDone()) {
                drillPathDone=true;
                setChanged();       // Time to update GUI
                notifyObservers();
              }
            }*/

            try { route_done_future.get(j).get(10, TimeUnit.MILLISECONDS); 
                  complete+=(Math.pow(skeleton.routes.get(j).size(),2)); 
                  done[j]=true;
            } catch (TimeoutException e) { active++; }  
          }
          if (active < maxprocs) break;
          Thread.sleep(1000);  // Don't test again too soon
        } while (true);

        if (stop) return;

        route_done_future.add(pool.submit(skeleton.routes.get(i))); 
        submitOrder[i]=index++;
      }
    }
  }
  catch (InterruptedException e) { System.out.println("A Int ERROR **** "+e);e.printStackTrace(); }
  catch (ExecutionException e)   { System.out.println("A Exec ERROR **** "+e);e.printStackTrace(); }
 
}
pool.shutdown(); // No more submissions

// ------------------------------------------------------------
// Display the drill travelling salesman result

// Wait for the drill travelling salesman result
try {  do {} while (tsDoneFuture.get()!=null); }
catch (InterruptedException e) { System.out.println("TS Int ERROR ****"); }
catch (ExecutionException e)   { System.out.println("TS Exec ERROR ****"); }

if (tsd!=0) {
  System.out.println(nL+"Drill order optimisation complete "+new Date());

  log.append("G code drilling transits have been reduced to "+
     String.format("%.1f",anneal.getFraction()*100.0)+"% of original"+nL);
} 
if (drillCode) {
  try {
    String fd=mydir+"/drill.tap";
    File fid=new File(fd);
    PrintWriter pwd=new PrintWriter(fid); 

    if (!overwrite && fid.exists()) overwriteFail(fd);
    pwd.println("(G code for drilling of PCB : "+fname+")");
    pwd.println("("+descriptive+")");
    pwd.println("(Created "+new Date()+")");
    pwd.println(defaultG);
    pwd.println("G21");  // Metric.  
    pwd.println("F10");  // TODO, make variable
    pwd.println("M30");  
    writeDrillCode(skeleton.drills,pwd,1.0,-1.0); // TODO make variable
    pwd.close();
  } catch (IOException e) { e.printStackTrace(); System.exit(0); }

  copyImage(drill,img); 
  g2d.setColor(Color.MAGENTA);
  PathOrder po=skeleton.drills.optimum;
  for (int i=0;i<(skeleton.drills.size()-1);i++) {
    int x0=skeleton.drills.getX(po.mapping(i));
    int y0=skeleton.drills.getY(po.mapping(i));
    int x1=skeleton.drills.getX(po.mapping(i+1));
    int y1=skeleton.drills.getY(po.mapping(i+1));
    g2d.drawLine(x0,y0,x1,y1);
  }
  drillPath=makeImage(img);
  writeFile("drillPaths",drillPath);
}
setChanged();       // Time to update GUI
notifyObservers();

// ------------------------------------------------------------
// Wait for the milling optimisation to finish - we're stuck without it
try { {}  while (tspDoneFuture.get()!=null); }
catch (InterruptedException e) { System.out.println("TSP Int ERROR ****");  e.printStackTrace(); }
catch (ExecutionException e)   { System.out.println("TSP Exec ERROR ****"); e.printStackTrace(); }

if (tsm!=0) { // Only say it's done if we did something
  System.out.println("Mill order optimisation complete "+new Date());
  log.append("G code milling transits have been reduced to "+
     String.format("%.1f",annealPair.getFraction()*100.0)+"% of original"+nL);
}
copyImage(board,img);

int cnc_x=0; // Initially
int cnc_y=0;
int cnc_z=0;

if (millCode) {
  try {
    String f=mydir+"/smoothIsolation.tap";
    File fi=new File(f);
    PrintWriter pw=new PrintWriter(fi); // But won't use if raw

    if (!raw) {
      if (!overwrite && fi.exists()) overwriteFail(f);
      pw.println("(G code for smoothed isolation milling of PCB : "+fname+")");
      pw.println("("+descriptive+")");
      pw.println("(Created "+new Date()+")");
      pw.println(defaultG);
      pw.println("G21");   // Metric.  
      pw.println("F100");  // TODO, make variable
    }

    String fr=mydir+"/rawIsolation.tap";
    File fir=new File(fr);
    if (!overwrite && fir.exists()) overwriteFail(fr);
    PrintWriter pwr = new PrintWriter(fir);

    pwr.println("(G code for detailed isolation milling of PCB : "+fname+")");
    pwr.println("("+descriptive+")");
    pwr.println("(Created "+new Date()+")");

    pwr.println(defaultG);
    pwr.println("G21");   // Metric.  
    pwr.println("F100");  // TODO, make variable

// Optimiser can choose to cut some traces in the 'reverse' direction to
// the way we found them.  j>k is the clue.  Note, j=k+1 or k=j+1. Hence j!=k. 

    PathPairOrder ppo=skeleton.transits.optimum;
    for (int i=0;i<skeleton.routes.size()*2;i+=2) { 
      int j=ppo.mapping(i);  
      int k=ppo.mapping(i+1);

      int baseRoute=j/2;
      boolean reversed=(j>k);
      int startIndex=0; // Always 0, but now has a name
      int endIndex=skeleton.routes.get(baseRoute).size()-1;

      // Need to see if that specific route has been smoothed yet
      if (!raw) {
        try { do {} while (route_done_future.get(submitOrder[baseRoute]).get()!=null); }
        catch (InterruptedException e) { System.out.println("Int ERROR **** "+e); }
        catch (ExecutionException e)   { System.out.println("Exec ERROR **** "+e); }
      }
      boolean attached= // Does it follow on from the last one?
       (skeleton.routes.get(baseRoute).getX(reversed?endIndex:startIndex)==cnc_x &&
        skeleton.routes.get(baseRoute).getY(reversed?endIndex:startIndex)==cnc_y);

      // Set the end for next time 
      cnc_x=skeleton.routes.get(baseRoute).getX(reversed?startIndex:endIndex); 
      cnc_y=skeleton.routes.get(baseRoute).getY(reversed?startIndex:endIndex); 
      if (!raw)
        pw.print(skeleton.routes.get(baseRoute).smoothGcode(reversed,attached));
      pwr.print(skeleton.routes.get(baseRoute).rawGcode(reversed,attached));

      // Draw cutting routes in green
      for (int m=0;m<skeleton.routes.get(baseRoute).size();m++) {
        for (int p=-2;p<2;p++)
          for (int q=-2;q<2;q++) {
            try { img.setRGB(skeleton.routes.get(baseRoute).getX(reversed?endIndex-m:startIndex+m)+p,
                     skeleton.routes.get(baseRoute).getY(reversed?endIndex-m:startIndex+m)+q,0x00FF00);
            } catch (ArrayIndexOutOfBoundsException e)  {  }  // Discard      
          }  
      }
   
      // Draw intermediates (transits, not cuts)  in red
      if ((i+2) >= (skeleton.routes.size()*2)) break;  // No 'intermediate' here  
      int xstart=skeleton.transits.getX(ppo.mapping(i+1));
      int ystart=skeleton.transits.getY(ppo.mapping(i+1));
      int xend=skeleton.transits.getX(ppo.mapping(i+2));
      int yend=skeleton.transits.getY(ppo.mapping(i+2));

      double length=Math.sqrt((xend-xstart)*(xend-xstart)+(yend-ystart)*(yend-ystart));

      for (int l=0;l<length;l++) {
        double fract=(double)l/length;
        int x=(int)(0.5+xstart+(xend-xstart)*fract);
        int y=(int)(0.5+ystart+(yend-ystart)*fract);
        for (int p=-2;p<2;p++) {
          for (int q=-2;q<2;q++) { 
            try {
              if ((img.getRGB(x+p,y+q)&0xFFFFFF)!=0x00FF00) 
                img.setRGB(x+p,y+q,0xFF0000);
            } catch (ArrayIndexOutOfBoundsException e)  {  } // Discard
          }
        }
      }  
    }

    if (!raw) {
      pw.println("M30");  
      System.out.println(nL+"G code smoothed file is "+
        String.format("%.1f",100.0*(double)fi.length()/fir.length())+
        "% of raw file length.");

      log.append("G code smoothed file is "+
        String.format("%.1f",100.0*(double)fi.length()/fir.length())+
        "% of raw file length."+nL);

      pw.close();
    }
    pwr.println("M30");  
    pwr.close();
  } catch (IOException e) { e.printStackTrace(); System.exit(0); }

  millPath=makeImage(img);
  writeFile("milling",millPath);
}

// ------------------------------------------------------------
if (smoothEtch && etch>0) drawEtch(g2d);
if (!raw && millCode) drawDuke(g2d);
// ------------------------------------------------------------
setChanged();       // Time to update GUI
notifyObservers();

// Do a sanity check on the DPI based on looking for ICs with 0.1" leg spacing
Points2D [] pitches=detectPitch(skeleton.drills);

copyImage(board,img);
drawCircles(pitches[0],g2d,Color.GREEN,8);     
drawCircles(pitches[1],g2d,Color.ORANGE,12);     

pitch=makeImage(img);
writeFile("pitches",pitch);

complete=true;
setChanged();       // Time to update GUI
notifyObservers();

System.out.println(nL+"All done at "+new Date());
System.out.println(nL+"-------------------SUMMARY--------------------");
System.out.println(nL+"Complete at "+new Date());
System.out.println(log);
System.out.println("----------------------------------------------");
System.out.println();
//System.out.println("Window will close in 20s, or you can close it manually.");

try {
  String fl=mydir+"/log.txt";
  File fil=new File(fl);
  PrintWriter pwl=new PrintWriter(fil); 
  pwl.println("------------------CISOLATE LOG--------------------");
  pwl.println(nL+"Complete at "+new Date());
  pwl.println(log);

  pwl.close();
} catch (IOException e) { e.printStackTrace(); System.exit(0); }

}
}
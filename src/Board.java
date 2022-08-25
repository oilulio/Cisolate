/*
Copyright (C) 2016-22  S Combes

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
import javax.swing.border.*;
import java.awt.image.*; 
import java.awt.event.*; 
import java.io.*; 
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;
import java.util.concurrent.*;
import java.util.concurrent.SubmissionPublisher;
import java.text.NumberFormat;
import java.text.ParseException;

class Board extends SubmissionPublisher<Board> implements Runnable {

// Main object within Cisolate program, represents a Board and actions upon it
// but excludes GUI, which gets Observer notifications

static final int THRESHOLD=30;  // Pixel considered black if all of RGB less than this out of 255.
static final int CU_WHITE=1;
static final int CU_BLACK=2;
static final int CU_GUESS=0;
static String nL = System.getProperty("line.separator");
static final String defaultG="G17 (X-Y plane)"+nL+
                             "G40 (Radius compensation off)"+nL+
                             "G49 (Length compensation off)"+nL+
                             "G80 (Cancel canned cycle)"+nL+
                             "G90 (Absolute coordinates)";

public BufferedImage  img;    // working, e.g. result of writeBimg
// **** Always create images in img and copy to final (e.g. drill) when complete
// so that caller can treat any non-null image as ready, and we only need one Graphics2D context
public BufferedImage board,cuts,drill,drillPath,heat,junction,
          millPath,pitch,duke,etched,gcode; 
public boolean complete=false;
public Skeleton skeleton;

private String fname;
private boolean [][] bimg;
int xsize,ysize;
private File mydir;
private File file;
private JFrame frame;
boolean verbose,raw,overwrite,doBacklash,relieved;
int flipped=1; // 1=Not; -1=L-R flip.
// Flipped is  L-R mirror.  All internal calcs done un-flipped,
// and output images unaffected (but G-code is flipped as this is critcial). 
// Images shown in GUI do flip.

static final double INCHASMM=25.4;
double backlashRad;
int tsd,tsm,copper,maxprocs,etch;
boolean smoothEtch=false;  
private double xmmPerPixel;
private double ymmPerPixel;  
private double xoffset=0.0;
private double yoffset=0.0;  // Board coordinates of TL pixel (mm)
protected int forceDPI;
private int nativexDPI,nativeyDPI; // As per the image file
protected int xDPI,yDPI;             // Used for our analysis
private ImageWriter writer;
private String descriptive;
public StringBuilder log;
StringBuffer imgProperties;
public boolean millCode=true;
public boolean drillCode=true;
public int assessedCu=CU_GUESS;
private boolean mixedEdge;
protected volatile boolean stop=false;
public Anneal anneal,annealPair;
private ArrayList<Future<?>> route_done_future;
double drillPlunge=-1.8; // mm, for G Code
double drillTransit=1.0; // mm, for G Code
double millPlunge=-0.3;  // mm, for G Code
double millTransit=1.0;  // mm, for G Code
int plungeRate=10; // mm/min
int millRate=20;   // mm/min
String sExt=".tap";
JProgressBar progressBarDrill,resultBarDrill,progressBarDuke;
JProgressBar progressBarMill,resultBarMill;
JTextArea taskOutput;
JPanel progressPanel;
public JLabel gen;
Draggable win;
protected GCodeInterpreter gci;

final static int CLOSEST=0;
final static int TENTHINCH=1;

// ---------------------------------------------------------------
Board(File file,JFrame frame) 
  { this(file,true,2,2,CU_GUESS,1,true,3,true,0); this.frame=frame;} 
  // Call when GUI active.  Values are dummies later overridden by GUI  
// ---------------------------------------------------------------
Board(String fname) 
    { this(new File(fname),true,2,2,CU_GUESS,1,true,3,true,0); } // TESTING VALUES

Board(String fname,boolean verbose,int tsd,int tsm,int copper,
    int maxprocs,boolean raw,int etch,boolean overwrite,int forceDPI) {
  this(new File(fname),verbose,tsd,tsm,copper,maxprocs,raw,etch,
        overwrite,forceDPI); 
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
complete=false;
descriptive="";

log=new StringBuilder();
imgProperties=new StringBuffer();

String ext = fname.substring(fname.lastIndexOf(".")+1).toLowerCase();  

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

  } catch (IOException e) { System.out.println("File Read Error"); e.printStackTrace(); System.exit(0);} 
} 
else if (forceDPI == 0) {
  System.out.println("Currently only supports .jpg and .bmp unless DPI is forced.  Exiting");
  System.exit(0);
}

if (xmmPerPixel > 0.0001 && ymmPerPixel > 0.0001)
{
  xDPI=nativexDPI=(int)(0.5+INCHASMM/xmmPerPixel);
  yDPI=nativeyDPI=(int)(0.5+INCHASMM/ymmPerPixel);
  System.out.println(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI");
  log.append(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI"+nL);
  imgProperties.append(ext.toUpperCase()+" with "+xDPI+" x "+yDPI+" DPI"+nL);
} 
else if (forceDPI == 0) {
  System.out.println("Unable to find plausible DPI.  Exiting");
  System.exit(0);
} 

if (forceDPI !=0) {
  xmmPerPixel=INCHASMM/forceDPI;
  ymmPerPixel=INCHASMM/forceDPI;
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

mixedEdge=false;
boolean edgeCu=isBlack(img.getRGB(0,0));
for (int i=0;i<img.getWidth();i++) {
  mixedEdge|=(edgeCu!=isBlack(img.getRGB(i,0)));
  mixedEdge|=(edgeCu!=isBlack(img.getRGB(i,img.getHeight()-1)));
}
for (int j=0;j<img.getHeight();j++) {
  mixedEdge|=(edgeCu!=isBlack(img.getRGB(0,j)));
  mixedEdge|=(edgeCu!=isBlack(img.getRGB(img.getWidth()-1,j)));
}
if (copper==CU_GUESS) {
  this.copper=assessedCu;  
  System.out.println(nL+"Assessed that the copper is "+((this.copper==CU_BLACK)?"black":"white"));
  //System.out.println("but it is safer if you specify with options -cb or -cw."+nL);
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

descriptive+=String.format("Board is approximately %.1f x %.1f inches",
          (double)img.getWidth()/nativexDPI,(double)img.getHeight()/nativeyDPI)+nL;
if (nativexDPI!=xDPI || nativeyDPI!=yDPI) 
  descriptive+=String.format("Remapped to approximately %.1f x %.1f inches",
          (double)img.getWidth()/xDPI,(double)img.getHeight()/yDPI)+nL;
  

System.out.println(descriptive); // Mostly to trap obvious bloopers
log.append(descriptive);
//imgProperties.append(descriptive);

if (mixedEdge) {
  descriptive+="***********************************************"+nL+
  "Board has an outermost pixel surround that is neither "+nL+
  "wholly copper nor wholly clear.  This is likely to lead "+nL+
  "to unexpected results."+nL+"***********************************************"+nL;
  log.append(descriptive);
  //imgProperties.append(descriptive);
}

// mydir = filename pre extension tagged on our path
String absPath = file.getAbsolutePath();

mydir = new File(absPath.substring(0,absPath.lastIndexOf(File.separator)+1)+
                 fname.substring(0,fname.lastIndexOf(".")));  
if (!mydir.exists())
  mydir.mkdir();  

}
// ---------------------------------------------------------------
private void updateDrill() {
if (anneal.running()) {
  progressBarDrill.setValue((int)(100.0*anneal.getProgress()));
  resultBarDrill.setValue((int)(100.0*anneal.getFraction()));
} 
}
// ---------------------------------------------------------------
private void updateMill() {
if (annealPair.running()) {
  progressBarMill.setValue((int)(100.0*annealPair.getProgress()));                  
  resultBarMill.setValue((int)(100.0*annealPair.getFraction()));
}  
}
// ---------------------------------------------------------------
private void updateDuke() 
    { progressBarDuke.setValue(Route2D.noSolved());}
// ---------------------------------------------------------------
public void aChange() { submit(this); }
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
	  try { result=NumberFormat.getInstance(Locale.getDefault()).parse(attribute.getNodeValue()).doubleValue(); }
      catch (ParseException e) { System.out.println("Attribute Error"); e.printStackTrace(); System.exit(0);} 
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
  ImageWriteParam jpegParams=writer.getDefaultWriteParam();

  IIOMetadata mdata=writer.getDefaultImageMetadata(new ImageTypeSpecifier(image),jpegParams);
  Element tree=(Element)mdata.getAsTree("javax_imageio_jpeg_image_1.0");  
  Element jfif=(Element)tree.getElementsByTagName("app0JFIF").item(0);

  jfif.setAttribute("Xdensity",Integer.toString(xDPI));
  jfif.setAttribute("Ydensity",Integer.toString(yDPI));
  jfif.setAttribute("resUnits", "1"); // density is dots per inch                 
  mdata.mergeTree("javax_imageio_jpeg_image_1.0",tree);

  writer.setOutput(ios);

  writer.write(mdata,new IIOImage(image,null,mdata),jpegParams);
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

if (flipped<0) xoffset=(img.getWidth()*xmmPerPixel);
else           xoffset=0.0;

pw.println("G00 Z"+String.format(Locale.US,"%.3f",clear));

PathOrder po=skeleton.drills.optimum;

for (int i=0;i<points.size();i++) {
  pw.println("G00 X"+String.format(Locale.US,"%.3f",
       xoffset+points.getX(po.mapping(i))*xmmPerPixel*flipped)+
             "    Y"+String.format(Locale.US,"%.3f",
     -(yoffset+points.getY(po.mapping(i))*ymmPerPixel)));  
  pw.println("G01 Z"+String.format(Locale.US,"%.3f",plunge));
  pw.println("G00 Z"+String.format(Locale.US,"%.3f",clear));  
} 
}
// ---------------------------------------------------------------
void writeConstellationCode(PrintWriter pw,double clear,
                    double plunge,boolean mirror)
{
// Note that Screen Y (and hence internal representation) moves from top down and 
// GCode Y moves from Bottom up - annoying

if (pw==null) return;

if (mirror) xoffset=(img.getWidth()*xmmPerPixel);
else        xoffset=0.0;

pw.println("G00 Z"+String.format(Locale.US,"%.3f",clear));

for (int i=0;i<skeleton.constellation.size();i++) {
  pw.println("G00 X"+String.format(Locale.US,"%.3f",
       xoffset+skeleton.constellation.getX(i)*xmmPerPixel*((mirror)?-1:1))+
             "    Y"+String.format(Locale.US,"%.3f",
     -(yoffset+skeleton.constellation.getY(i)*ymmPerPixel)));  
  pw.println("G00 Z"+String.format(Locale.US,"%.3f",clear));  
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
double smallest=Math.pow((double)smallestSq,0.5)*xmmPerPixel/INCHASMM;  // TODO presumes x=y

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
  tmp+="*** Conflict with specified DPI of "+xDPI+". You may want to re-run with DPI as "+(index*10)+nL;
  tmp+="*** Looking at pitches.jpg for more details.  Closest points are amber."+nL;
  tmp+="*** Points whose closest neighbout is at 0.1\" pitch are green."; 
  System.out.println(tmp);
  log.append(tmp+nL); 
}
return found;
}
// ---------------------------------------------------------------
public void gracefulExit() { 

// Called from outside.  Stops any currently running jobs, so run() 
// can just return when it senses stop.  Best to test stop just before
// starting any new (especially, large) thread.

  //if (skeleton!=null)    skeleton.gracefulExit();  
  // Because skeleton does most work in constructor, need to use board.stop directly
  if (win!=null) win.setVisible(false);
  win=null;
  if (anneal!=null)      anneal.gracefulExit(); 
  if (annealPair!=null)  annealPair.gracefulExit(); 
  stop=true; 
  if (route_done_future!=null)
    for (Future<?> f : route_done_future)
      if (!f.isDone()) f.cancel(true);

  String tmp="*** Processing run cancelled at "+new Date();
  log.append(nL+tmp+nL);
  System.out.println(nL+tmp);
}
// ------------------------------------------------------------
public boolean edgeIsMixed() { return mixedEdge; }
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
protected void cleanUp() 
{
cuts=null;
drill=null;
drillPath=null;
heat=null;
junction=null;
millPath=null;
pitch=null;
duke=null;
etched=null;
gcode=null;
}
// ---------------------------------------------------------------
public void run()
{
stop=false;
cleanUp();

aChange(); // Time to update GUI

img=makeImage(board);      // Needed after cancel
writeFile("board",board);  // Echo startpoint out for the record

if (flipped<0) { 
  log.append("*** G-Code is flipped L-R ***"+nL);
  imgProperties.append("*** G-Code is flipped L-R *** from "+fname+nL);
} 
if (forceDPI !=0) {
  xmmPerPixel=INCHASMM/forceDPI;
  ymmPerPixel=INCHASMM/forceDPI;
  xDPI=forceDPI;
  yDPI=forceDPI;
  System.out.println("*********** Forcing DPI to be "+forceDPI);
  log.append("DPI was forced to be "+forceDPI+nL);
  descriptive="DPI was forced to be "+forceDPI+nL;
  descriptive+=String.format("Board is therefore approximately %.1f x %.1f inches",
          (double)img.getWidth()/xDPI,(double)img.getHeight()/yDPI);

  log.append(descriptive+nL);
  imgProperties.append(descriptive+nL);
}
if (!millCode)  tsm=0;  // Zero optimisations if we're not doing the code
if (!drillCode) tsd=0; 

progressBarDrill=new JProgressBar(0,100);
progressBarDrill.setValue(0);
progressBarDrill.setStringPainted(true);

resultBarDrill=new JProgressBar(0,100);
resultBarDrill.setValue(100);
resultBarDrill.setStringPainted(true);

progressBarMill=new JProgressBar(0,100);
progressBarMill.setValue(0);
progressBarMill.setStringPainted(true);

resultBarMill=new JProgressBar(0,100);
resultBarMill.setValue(100);
resultBarMill.setStringPainted(true);

progressBarDuke=new JProgressBar(0,100);
progressBarDuke.setValue(0);
progressBarDuke.setStringPainted(true);

JPanel drillPanel=new JPanel();
drillPanel.setBorder(new LineBorder(Color.gray,2,false));

drillPanel.setLayout(new GridLayout(2,2,7,2));
drillPanel.add(new JLabel("<html>Drill optimsation progress</html>",
                  SwingConstants.RIGHT)); // html allows wrap
drillPanel.add(progressBarDrill);
drillPanel.add(new JLabel("<html>Filesize</html>",
                  SwingConstants.RIGHT));
drillPanel.add(resultBarDrill);

JPanel millPanel=new JPanel();
millPanel.setBorder(new LineBorder(Color.gray,2,false));

millPanel.setLayout(new GridLayout(2,2,7,2));
millPanel.add(new JLabel("<html>Mill optimsation progress</html>",
                  SwingConstants.RIGHT));
millPanel.add(progressBarMill);
millPanel.add(new JLabel("<html>Filesize</html>",
                  SwingConstants.RIGHT));
millPanel.add(resultBarMill);

JPanel dukePanel=new JPanel();
dukePanel.setBorder(new LineBorder(Color.gray,2,false));

dukePanel.setLayout(new GridLayout(1,2,7,2));
dukePanel.add(new JLabel("<html>Curve smoothing progress</html>",
                  SwingConstants.RIGHT)); 
dukePanel.add(progressBarDuke);

progressPanel=new JPanel();
progressPanel.setBorder(new LineBorder(Color.gray,5,false));
    
progressPanel.setLayout(new GridLayout(6,1,7,2)); 

JLabel label=new JLabel("Cisolate Progress Dashboard",
                    SwingConstants.CENTER);
label.setFont(label.getFont().deriveFont(label.getFont().getStyle()|Font.BOLD));  
progressPanel.add(label);
                
progressPanel.add(new JLabel(""));
gen=new JLabel("Automata generation 1",SwingConstants.CENTER);
progressPanel.add(gen);
progressPanel.add(drillPanel);
progressPanel.add(millPanel);
progressPanel.add(dukePanel);

win=new Draggable(frame);
        
win.setSize(300,380);
win.setLocation(100,50);
win.getContentPane().add(progressPanel,"Center");
 
win.setVisible(true);

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
skeleton=new Skeleton(bimg,pool,this);
if (stop) return;
if (flipped<0) xoffset=(img.getWidth()*xmmPerPixel);
else           xoffset=0.0;
Route2D.initialise(skeleton.lasttouch,xoffset,yoffset,
                   xmmPerPixel*flipped,ymmPerPixel);

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

aChange();  // Time to update GUI

if (stop) return;
System.out.println("\n\n"+skeleton.drills.size()+" drill points found");
log.append(skeleton.drills.size()+" drill points found"+nL);
System.out.println(skeleton.threeWays.size()+" three-way intersections found");
System.out.println(skeleton.fourWays.size()+" four-way intersections found\n");

// ------------------------------------------------------------
// Initiate thread to optimise drilling order

anneal=new Anneal(skeleton.drills, // last two params are tuneable
              new PathOrder(skeleton.drills.size()),tsd,0.002,0.9);
log.append("Drilling optimisation replications="+tsd+nL);

Future< ? > tsDoneFuture;
tsDoneFuture=pool.submit(anneal);

if (tsd==0)
  System.out.println("No drill order optimisation requested");
else 
  System.out.println("Drill order optimisation starting "+new Date());
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

log.append("Milling optimisation replications="+tsm+nL);

tspDoneFuture=pool.submit(annealPair);

System.out.println("\nFound "+skeleton.routes.size()+" routes to optimise");
progressBarDuke.setMaximum(skeleton.routes.size());

if ((skeleton.drills.size()/(skeleton.routes0w+1)) < 20.0) { 
// ratio of drill points to circuits (+1 to avoid div by zero)
  System.out.println("*** The ratio between drill points and circuits "+
          "suggests the copper colour may be wrongly assigned ***");
  log.append("*** The ratio between drill points and circuits suggests "+
          "the copper colour may be wrongly assigned ***"+nL);
}
if (stop) return; 
// ------------------------------------------------------------
// Put the jobs for route smoothing into the pool
Route2D.resetCount();
route_done_future=new ArrayList<Future<?>>();  
int bigIndex=0;
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
  }
  if (stop) return;
  route_done_future.add(pool.submit(skeleton.routes.get(bigIndex))); // Biggest one
  submitOrder[bigIndex]=index++;

  // Put the rest in, never having more than max processors running
  // While optimisations are still running, we will have maxprocs+2
  // threads going

  try {
    for (int i=0;i<skeleton.routes.size();i++) {
      if (i!=bigIndex) {
        do {
          int active=0;
          for (int j=0;j<index;j++) {
            if (done[j]) continue;

            try { route_done_future.get(j).get(50, TimeUnit.MILLISECONDS); 
                  done[j]=true;
            } catch (TimeoutException e) { 
              active++; 

              updateDrill();
              updateMill();       
              updateDuke();
              
              if (stop) skeleton.routes.get(j).gracefulExit();             
              if (stop) skeleton.routes.get(bigIndex).gracefulExit();             
            } catch (CancellationException e) { /* Just asynchronous */ }
          }
          if (active < maxprocs) break;
          Thread.sleep(150);  // Don't test again too soon
        } while (true);

        if (stop) return;

        route_done_future.add(pool.submit(skeleton.routes.get(i))); 
        submitOrder[i]=index++;
      }
    }
  }
  catch (InterruptedException e) { System.out.println("A Interruption **** "+e); }
  catch (ExecutionException e)   { System.out.println("A Exec ERROR **** "+e);e.printStackTrace(); }
}
pool.shutdown(); // No more submissions

// ------------------------------------------------------------
// Display the drill travelling salesman result

// Wait for the drill travelling salesman result
boolean finished=false;

do {
  if (stop) return;

  updateDrill();
  updateMill();       
  updateDuke();
  try { finished|=(tsDoneFuture.get(100,TimeUnit.MILLISECONDS)==null); }
  catch (TimeoutException e) { /* Again, then */ }
  catch (CancellationException e) { /* Just asynchronous */ }
  catch (InterruptedException e) { System.out.println("TS Int ERROR ****");  }
  catch (ExecutionException e)   { System.out.println("TS Exec ERROR ****"); }  
} while(!finished);

if (tsd!=0) {
  System.out.println(nL+"Drill order optimisation complete "+new Date());

  log.append("G code drilling transits have been reduced to "+
     String.format("%.1f",anneal.getFraction()*100.0)+"% of original"+nL);
} 
if (!stop && drillCode) {
  try {
    String fd=mydir+"/drill."+sExt;
    File fid=new File(fd);
    PrintWriter pwd=new PrintWriter(fid); 

    if (!overwrite && fid.exists()) overwriteFail(fd);

    pwd.println(filePreamble("Gcode for drilling",fname,(flipped<0)));
    pwd.println("F"+Integer.toString(plungeRate));  
    writeDrillCode(skeleton.drills,pwd,drillTransit,drillPlunge); 
    pwd.println("M05 (Spindle off)");  
    pwd.close();
    
    fd=mydir+"/constellation."+sExt;
    fid=new File(fd);
    pwd=new PrintWriter(fid); 

    if (!overwrite && fid.exists()) overwriteFail(fd);

    pwd.println(filePreamble("Gcode for alignment constellation",fname,false));
    pwd.println("F"+Integer.toString(plungeRate));  
    writeConstellationCode(pwd,drillTransit,drillPlunge,false); 
    pwd.println("M05 (Spindle off)");  
    pwd.close();
    
    fd=mydir+"/noitalletsnoc."+sExt;
    fid=new File(fd);
    pwd=new PrintWriter(fid); 

    if (!overwrite && fid.exists()) overwriteFail(fd);

    pwd.println(filePreamble("Gcode for alignment constellation",fname,true));
    pwd.println("F"+Integer.toString(plungeRate));  
    writeConstellationCode(pwd,drillTransit,drillPlunge,true); 
    pwd.println("M05 (Spindle off)");  
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
aChange();  // Time to update GUI

// ------------------------------------------------------------
// Wait for the milling optimisation to finish - we're stuck without it
finished=false;

do {
  if (stop) return;
  updateMill();       
  updateDuke();
  try { finished|=(tspDoneFuture.get(100,TimeUnit.MILLISECONDS)==null); }
  catch (TimeoutException e) { }
  catch (InterruptedException e) { System.out.println("TS Int ERROR ****");  }
  catch (ExecutionException e)   { System.out.println("TS Exec ERROR ****"); }  
} while(!finished);

if (tsm!=0) { // Only say it's done if we did something
  System.out.println("Mill order optimisation complete "+new Date());
  log.append("G code milling transits have been reduced to "+
     String.format("%.1f",annealPair.getFraction()*100.0)+"% of original"+nL);
}
copyImage(board,img);

int cnc_x=0; // Initially
int cnc_y=0;

if (!stop && millCode) {
  try {
    String f=mydir+"/smoothIsolation."+sExt; 
    File fi=new File(f);
    PrintWriter pw=new PrintWriter(fi); // But won't use if raw

    if (!raw) {
      if (!overwrite && fi.exists()) overwriteFail(f);
      pw.println(filePreamble("G code for smoothed milling",fname,(flipped<0)));
    }

    String fr=mydir+"/rawIsolation."+sExt;
    File fir=new File(fr);
    if (!overwrite && fir.exists()) overwriteFail(fr);
    PrintWriter pwr = new PrintWriter(fir);

    pwr.println(filePreamble("Gcode for un-smoothed milling",fname,(flipped<0)));
  
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

      // Need to see if that specific route has been smoothed yet ...
      if (!raw) { // ... but it won't have been smoothed if we don't want smoothing
      
        try { do {} while (route_done_future.get(submitOrder[baseRoute]).get()!=null); }
        catch (InterruptedException e) { System.out.println("Int ERROR **** "+e); }
        catch (ExecutionException e)   { System.out.println("Exec ERROR **** "+e); }
        catch (CancellationException e) { /* Just asynchronous */ }
        updateDuke();
      }
      boolean attached= // Does it follow on from the last one?
       (skeleton.routes.get(baseRoute).getX(reversed?endIndex:startIndex)==cnc_x &&
        skeleton.routes.get(baseRoute).getY(reversed?endIndex:startIndex)==cnc_y);

      // Set the end for next time 
      cnc_x=skeleton.routes.get(baseRoute).getX(reversed?startIndex:endIndex); 
      cnc_y=skeleton.routes.get(baseRoute).getY(reversed?startIndex:endIndex); 
      if (!raw)
        pw.print(skeleton.routes.get(baseRoute).smoothGcode(reversed,attached,millRate,
           plungeRate,millPlunge,millTransit,doBacklash,backlashRad,skeleton));
        pwr.print(skeleton.routes.get(baseRoute).rawGcode(reversed,attached,millRate,
           plungeRate,millPlunge,millTransit,doBacklash,backlashRad,skeleton));

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
      pw.println("G00 Z"+millTransit);
      pw.println("M05 (Spindle off)");  
      System.out.println(nL+"G code smoothed file is "+
        String.format("%.1f",100.0*(double)fi.length()/fir.length())+
        "% of raw file length.");

      log.append("G code smoothed file is "+
        String.format("%.1f",100.0*(double)fi.length()/fir.length())+
        "% of raw file length."+nL);

      pw.close();
    }
    pwr.println("G00 Z"+millTransit);
    pwr.println("M05 (Spindle off)");  
    pwr.close();
  } catch (IOException e) { e.printStackTrace(); System.exit(0); }

  millPath=makeImage(img);
  writeFile("milling",millPath);
  
  // G code generation is very fast, no need to parallelise
  System.out.println(nL+"Start G-code image generation "+new Date());
  if (raw) {
    gci=new GCodeInterpreter(mydir+"/rawIsolation."+sExt,
         img.getWidth()*xmmPerPixel,img.getHeight()*ymmPerPixel);
  } else { // Use smoothed version if possible
    gci=new GCodeInterpreter(mydir+"/smoothIsolation."+sExt,
         img.getWidth()*xmmPerPixel,img.getHeight()*ymmPerPixel);
  }
  System.out.println("End G-code image generation "+new Date()+nL);
  gcode=makeImage(gci.bi); 
}

// ------------------------------------------------------------
if (stop) return;
if (smoothEtch && etch>0) drawEtch(g2d);
if (!raw && millCode) drawDuke(g2d);
// ------------------------------------------------------------
if (win!=null) win.setVisible(false);
win=null;
aChange();  // Time to update GUI

// Do a sanity check on the DPI based on looking for ICs with 0.1" leg spacing
Points2D [] pitches=detectPitch(skeleton.drills);

copyImage(board,img);
drawCircles(pitches[0],g2d,Color.GREEN,8);     
drawCircles(pitches[1],g2d,Color.ORANGE,12);     

pitch=makeImage(img);
writeFile("pitches",pitch);

complete=true;

aChange();  // Time to update GUI

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
// ---------------------------------------------------------------
private String forceGComment(String input) { 
// Formats an input string into a GCode comment, multi-line if required
// i.e. if the form "(....)+nL" repeated.

StringBuffer sb=new StringBuffer(1000);

int index=0;
while (index<input.length()) {
  sb.append("(");
  int end=input.substring(index).indexOf(nL);
  if (end<0) end=input.length();
  else end+=index;
  System.out.println(input.substring(index,end));
  sb.append(input.substring(index,end));
  sb.append(")"+nL);
  index=end+nL.length();
}
return sb.toString();
}
// ---------------------------------------------------------------
private String filePreamble(String title,String fname,boolean mirror) {

// Note it appears Grbl fails if any of these lines exceed 80 characters
// So trap that case for long filenames

StringBuffer sb=new StringBuffer(2000);

sb.append("("+title+" of PCB created from image : )"+nL);
for (int i=0;i<fname.length();i+=70) {
  int end=Math.min(i+70,fname.length());
  sb.append("("+fname.substring(i,end)+((end!=fname.length())?"+++":"")+")"+nL);
}
if (mirror) sb.append("(N.B. Gcode is flipped Left-Right from that image)"+nL);
sb.append(forceGComment(descriptive));
sb.append("(Created by Cisolate V"+Cisolate.version+" on)"+nL);
sb.append("("+new Date()+")"+nL);
sb.append(defaultG+nL);
sb.append("G21 (Metric)");

return sb.toString();
}
// ---------------------------------------------------------------
public class Draggable extends JWindow {
 
int X;
int Y;
 
Draggable(JFrame f) {
 
super(f);
setBounds(50,50,100,100);
 
addMouseListener(new MouseAdapter() {
  public void mousePressed(MouseEvent me) { X=me.getX();Y=me.getY(); }
});
addMouseMotionListener(new MouseMotionAdapter() {
  public void mouseDragged(MouseEvent me) {
    Point p = getLocation();
    setLocation(p.x+(me.getX()-X),p.y+(me.getY()-Y));
}
});
 
this.setVisible(true);
}
}
// ---------------------------------------------------------------
double gcodeToImageRatio(int forceDPI) 
{ // TODO allow different x,y DPI
if (forceDPI==0) return 0.5*GCodeInterpreter.dpmm/(nativexDPI/INCHASMM);  
else             return 0.5*GCodeInterpreter.dpmm/(forceDPI/INCHASMM);  
}
// ---------------------------------------------------------------
class GCodeInterpreter
{
// This class is a MINIMAL G Code interpreter.  It can only process
// Vertical movements with no concurrent x,y change OR
// Horizontal movements with no concurrent z change.

// It also uses an inflexible syntax, only allowing the formats
// that Cisolate produces.  e.g. "G00 X12.0" will move to X=12.0
// whereas "X12.0" after a G00 on a previous line will not,
// nor will "G0 X12.0" nor "G00 X 12.0"  TODO : improve this.

// However this set of movements are sufficient for PCB milling and
// drilling operations as generated by Cisolate.
// Movements are assumed to be ABSOLUTE

// G00, G01, G02, G03 are supported.

// For these reasons this is currently an inner class of Board, as
// it is currently too bespoke to be a generic class.

// Code is intended to determine the correct order in which to visit 
// pixels on a raster (i.e. appropriate to drive stepper motors)
// always moving in a rookwise (rather than kingwise) motion.
// But some elements (notably final alignment at end of curve/line)
// do currently utilise kingwise.

// Code was originally C and is intended to be used as C again, hence
// is not overly converted to an OO paradigm.

static final int NE=(0);
static final int SE=(1);
static final int SW=(2);
static final int NW=(4);
static final int NO_MOVE=(0);
static final int PLUS_X =(1);
static final int PLUS_Y =(2);
static final int MINUS_X=(3);
static final int MINUS_Y=(4);
static final int PLUS_Z =(5);
static final int MINUS_Z=(6);
static final int LINE_EV  =0;
static final int CIRCLE_EV=1;

static protected final int dpmm=25;  //  dots per mm (25 ~=600 dpi)  Probably needs to be at least 5
double scale=1.0/dpmm;
int direction;
int move=NO_MOVE;
int x=0; // Coordinates +ve to right (East)
int y=0; // +ve up (North)
int z=0; // +ve up (Z)
int x0,y0;
int ii,jj;
int finalx,finaly; // End of line
int event=LINE_EV;
int dividing;
boolean cw;  // Clockwise.  Must enforce CW =0 or 1
boolean vertical;
boolean clear;
boolean fail;
long rsq,rsq2;
double dp,dq;
protected BufferedImage bi;
private ImageWriter writer;

private volatile boolean stop=false;

GCodeInterpreter(String filename,double xsize,double ysize) {

int wi=(int)(xsize*dpmm/2);
int hi=(int)(ysize*dpmm/2);
System.out.println(xsize+" "+wi+" "+ysize+" "+hi);
bi=new BufferedImage(wi,hi,BufferedImage.TYPE_3BYTE_BGR);
dividing=(int)(0.5+dpmm*(millPlunge+millTransit)/2.0);
Gfile gf=new Gfile(filename);
x=y=z=0;
double xis=0.0;
double yis=0.0;
double zis=0.0;
double ris=0.0;
double iis=0.0;
double jis=0.0;

while (gf.hasNext()) {
  String line=gf.next();
  // Remove comments
  StringBuilder sb=new StringBuilder(line.length());
  int brack=0;
  for (int i=0;i<line.length();i++) {
    if (brack==0 && line.charAt(i)!='(') sb.append(line.charAt(i));
    if (line.charAt(i)=='(') brack++;
    if (line.charAt(i)==')') brack--;
    if (brack<0) brack=0;
  }
  line=sb.toString().toUpperCase();  // Case insensitive for rest
  int xat=line.indexOf("X");
  int yat=line.indexOf("Y");
  int zat=line.indexOf("Z");
  int gat=line.indexOf("G");
  int rat=line.indexOf("R");
  int iat=line.indexOf("I");

  boolean containsX=(xat>=0);
  boolean containsY=(yat>=0);
  boolean containsZ=(zat>=0);
  boolean containsG=(gat>=0);
  boolean containsR=(rat>=0);
  boolean containsI=(iat>=0);
  if (containsG) {
    if (containsZ && (containsX || containsY)) {
      System.out.println("G code mixes horizontal and vertical moves");
      System.exit(0);
    }
    if (containsX) xis=getNo(line.substring(xat+1));
    if (containsY) yis=getNo(line.substring(yat+1));
    if (containsZ) zis=getNo(line.substring(zat+1));
    if (containsR) ris=getNo(line.substring(rat+1));
    if (containsI) iis=getNo(line.substring(iat+1));
    jis=yis; // We know we don't use J in the file, so J must equal Y

    try {
      if (z<dividing) bi.setRGB(x/2,-y/2,0xFF<<8);
      else            bi.setRGB(x/2,-y/2,0xFF<<16);
    } catch (ArrayIndexOutOfBoundsException e) {}

    
    if (line.indexOf("G00")>=0 || line.indexOf("G01")>=0) { // A line
      if (containsX || containsY) {
        setupLine(scaled(xis),scaled(yis));
        while (nextEvent()) { /* Dummy */ }
      }
      else if (containsZ) {
        z=(int)(0.5+zis*dpmm);
      }
    }
    else if (line.indexOf("G02")>=0) { // A cw circle
      if (containsI) { // This block should currently be redundant - not using I
        // If the line contains I, it will also contain X and either relate to
        // a 360 degree circle or a 180 degree semi-circle (Cisolate-specific facts)
        int deltaX=Math.abs(scaled(xis)-x); // <5 => full circle
        setupCircle(true,(deltaX<5),scaled(xis+iis),scaled(jis),
            scaled(xis),scaled(yis));
      } else {
        setCentre(scaled(xis),scaled(yis),ris*dpmm,true);
        setupCircle(true,false,ii,jj,scaled(xis),scaled(yis));
      }
      while (nextEvent()) { /* Dummy */ }
    }
    else if (line.indexOf("G03")>=0) { // A ccw circle
      if (containsI) { // This block should currently be redundant - not using I
        // If the line contains I, it will also contain X and either relate to
        // a 360 degree circle or a 180 degree semi-circle (Cisolate-specific facts)
        int deltaX=Math.abs(scaled(xis)-x); // <5 => full circle
        setupCircle(false,(deltaX<5),scaled(xis+iis),scaled(jis),
            scaled(xis),scaled(yis));
      } else {
        setCentre(scaled(xis),scaled(yis),ris*dpmm,false);
        setupCircle(false,false,ii,jj,scaled(xis),scaled(yis));
      }
      while (nextEvent()) { /* Dummy */ }
    }
  }
}
writeFile("gcodePaths",bi);
}
// ---------------------------------------------------------------
private int scaled(double q) { return 2*(int)(0.5+q*dpmm/2.0); } 
// Enforce evenness and scale
// ---------------------------------------------------------------
private final boolean nextEvent() {
  if      (event==CIRCLE_EV) return nextCircle();
  else if (event==LINE_EV)   return nextLine();
  else System.out.println("Selection Error"); // Should not get here
  if (true) System.exit(0);                   // Should not get here
  return false;                               // Should not get here
}
// ---------------------------------------------------------------
private long distancesq(int deltax, int deltay) {
    return ((long)deltax*(long)deltax+(long)deltay*(long)deltay);}
// ---------------------------------------------------------------
private void setCentre(int fx,int fy,double radius,boolean cw) {
// Sets centre (I,J) of G02/G03 curve given x,y and finalx,
// finaly and radius.

double hyp=Math.hypot(fy-y,fx-x);

double fromChord=Math.sqrt(radius*radius-hyp*hyp/4.0);
if (Double.isNaN(fromChord)) { // assume we've just fractionally 
  // exceeded, so -ve sqrt.   Hence this is a diameter. Hence ...
  ii=(x+fx)/2;
  jj=(y+fy)/2;
  return;
}
double intermedX=(fx+x)/2.0;
double intermedY=(fy+y)/2.0;

int centreX=(int)(0.5+intermedX+(cw?1:-1)*fromChord*(fy-y)/hyp);
int centreY=(int)(0.5+intermedY+(cw?1:-1)*fromChord*(x-fx)/hyp);

ii=centreX;
jj=centreY;
}
// ---------------------------------------------------------------
private double getNo(String sub)
{
int end=sub.indexOf(" ");
if (end<0) end=sub.length();
double result=0.0;
try {result=
NumberFormat.getInstance(Locale.getDefault()).parse(sub.substring(0,end)).doubleValue(); }
catch (ParseException e) { System.out.println("internal Error"); e.printStackTrace(); System.exit(0);} 
return result;
}
// ---------------------------------------------------------------
private int getInt(String sub)
{
int end=sub.indexOf(" ");
if (end<0) end=sub.length();
return Integer.parseInt(sub.substring(0,end));
}
// ------------------------------------------------------------------------------------
private void setupLine(int fx,int fy) {
// Sets up parameters for a line from the current x,y to a future fx,fy
// Parameters are vertical,direction,x0,y0,finalx,finaly,dp,dq
int deltax,deltay;

event=LINE_EV;
x0=x;
y0=y;
finalx=fx;
finaly=fy;
deltax=(fx-x);
deltay=(fy-y);
dp=(double)deltax;
dq=(double)deltay;

vertical=(deltax==0);  // Useful flag to avoid later division by zero
if (deltax>0)  direction=(deltay>0)?NE:SE; // Pick quadrant.  Needs no special case
else           direction=(deltay>0)?NW:SW; // for horizontal or vertical
}
// ------------------------------------------------------------------------------------
private final boolean nextLine() {
// Finds the next move for a line whose parameters are already set up
// returning False indicates no further move
int y1;

if (x==finalx && y==finaly) return false;
if (Math.abs(x-finalx)<3 && Math.abs(y-finaly)<3 ) {
  x=finalx; // TODO : Ensure rookwise
  y=finaly;
  return true;
}

if (vertical) { // Avoid division by zero - simple.  Go up or down based on N or S
  switch (direction) {
    case (NE): // Fall-though
    case (NW): move=PLUS_Y;  break;
    case (SE): // Fall-though
    case (SW): move=MINUS_Y; break;
  }
}
else {
  y1=(int)(y0+((x+1-x0)/dp)*dq);

  switch (direction) {
    case (NE): move=(y1<(y+1))? PLUS_X :PLUS_Y;  break;
    case (SE): move=(y1<(y-1))? MINUS_Y:PLUS_X;  break;
    case (SW): move=(y1<(y-1))? MINUS_Y:MINUS_X; break;
    case (NW): move=(y1<(y+1))? MINUS_X:PLUS_Y;  break;
  }
}
try {
  if (z<dividing)  bi.setRGB(x/2,-y/2,0xFF<<8);
  else             bi.setRGB(x/2,-y/2,0xFF<<16);
} catch (ArrayIndexOutOfBoundsException e) {}

switch (move) {
  case (PLUS_X):  x+=2; break;
  case (PLUS_Y):  y+=2; break;
  case (MINUS_X): x-=2; break;
  case (MINUS_Y): y-=2; break;
}
move=NO_MOVE;

return true;
}
// ------------------------------------------------------------------------------------
void setupCircle(boolean clock,boolean full,int i,int j,int fx,int fy) {
// Sets up a circle from our current (x,y) with centre (i,j)
// to an endpoint (fx,fy) - which can be x,y
cw=clock;
clear=false; // Are we clear of startpoint yet?

event=CIRCLE_EV;  // May get overridden later
x0=i;
y0=j;
finalx=fx;
finaly=fy;
int deltax=(x-i);
int deltay=(y-j);

if (!full && (Math.abs(fx-x)+Math.abs(fy-y))<6) {
  setupLine(fx,fy);  // Such a short arc is a line, and treated as a circle
  return;            // they have a nasty tendency to go the wrong direction
} 

if (cw) { if (deltax>0) direction=(deltay>0)?SE:SW;
          else          direction=(deltay>0)?NE:NW;
} else  {
          if (deltax>0) direction=(deltay>0)?NW:NE;
          else          direction=(deltay>0)?SW:SE;
}

rsq =distancesq(x-x0,y-y0);  // The circle radius squared
rsq2=distancesq(fx-x0,fy-y0); 
}
// ---------------------------------------------------------------
private final boolean nextCircle()
{ // Finds the next move for a circle whose parameters have 
  // previously been set up
long r2sq;

r2sq=0;

int deltax=(x-x0);
int deltay=(y-y0);

if (cw) { if (deltax>0) direction=(deltay>0)?SE:SW;
          else          direction=(deltay>0)?NE:NW;
} else {
          if (deltax>0) direction=(deltay>0)?NW:NE;
          else          direction=(deltay>0)?SW:SE;
}

switch (direction) {
  case (NE):
    r2sq=distancesq(x-x0+1,y-y0+1);
	  move=(cw ^ (r2sq > rsq))?PLUS_Y:PLUS_X;
 /*     if (cw  && x/2==x0/2) direction=SE;
      if (!cw && y/2==y0/2) direction=NW;*/
	  break;

  case (SE):
    r2sq=distancesq(x-x0+1,y-y0-1);
	  move=(cw ^ (r2sq > rsq))?PLUS_X:MINUS_Y;
/*      if (cw  && y/2==y0/2) direction=SW;
      if (!cw && x/2==x0/2) direction=NE;*/
	  break;

  case (SW):
    r2sq=distancesq(x-x0-1,y-y0-1);
	  move=(cw ^ (r2sq > rsq))?MINUS_Y:MINUS_X;
   /*   if (cw  && x/2==x0/2) direction=NW;
      if (!cw && y/2==y0/2) direction=SE;*/
	  break;

  case (NW):
    r2sq=distancesq(x-x0-1,y-y0+1);
	  move=(cw ^ (r2sq > rsq))?MINUS_X:PLUS_Y;
 /*     if (cw  && y/2==y0/2) direction=NE;
      if (!cw && x/2==x0/2) direction=SW;*/
	  break;
  }

  if (clear && (x==finalx) && (y==finaly)) return false;

  // Special case to make sure we hit it.
  if (Math.abs(x-finalx)<3 && Math.abs(y-finaly)<3) {
    if (clear) {
      x+=(2*Integer.signum(finalx-x)); // TODO Enforce rookwise
      y+=(2*Integer.signum(finaly-y));
      return true;
    }
  } else { clear=true; } // we have left the startpoint

  
switch (move) {
  case (PLUS_X):  x+=2; break;
  case (PLUS_Y):  y+=2; break;
  case (MINUS_X): x-=2; break;
  case (MINUS_Y): y-=2; break;
}
move=NO_MOVE;

try {
  if (z<dividing) bi.setRGB(x/2,-y/2,0xFF<<8);
  else            bi.setRGB(x/2,-y/2,0xFF<<16);
} catch (ArrayIndexOutOfBoundsException e) {}

return true;
}
// ---------------------------------------------------------------
public void gracefulExit() { stop = true; }
// ---------------------------------------------------------------
class Gfile implements Iterator {

BufferedReader in;
String line;
boolean more;

// --------------------------------------------------------------------------------------
Gfile(String filename)
{
try {  in = new BufferedReader(new FileReader(filename)); }
catch (IOException e) { System.out.println("G Code File not found"); }
more=true;
getLine();
}
// --------------------------------------------------------------------------------------
private void getLine()
{
try { line = in.readLine(); }
catch (IOException e) { more=false; }
if (line==null) more=false;
if (!more) {
  try {  in.close(); }
  catch (IOException e) { }
}
}
// --------------------------------------------------------------------------------------
public boolean hasNext() { return more; }
// --------------------------------------------------------------------------------------
public String next()
{
if (line==null) throw new NoSuchElementException();

String prev=new String(line);
getLine();
return prev;
}
// --------------------------------------------------------------------------------------
public void remove() { getLine(); }
}
// --------------------------------------------------------------------------------------

}
}
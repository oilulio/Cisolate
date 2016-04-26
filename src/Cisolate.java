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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JFrame; 
import javax.swing.Timer; 
import java.awt.geom.AffineTransform;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;
import javax.imageio.plugins.jpeg.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.MenuEvent; 
import javax.swing.event.MenuListener; 
import java.util.Date;
import java.lang.Thread;
import java.util.concurrent.*;
import java.util.Observer;
import java.util.Observable;

class Cisolate extends Component  {

// Produces isolation paths and drill files for PCB designs using a 
// cellular automata thinning method (see Class PcbCellEvolve) on a 
// bitmap image.  Optionally uses simulated annealing to optimise 
// the non-cutting links of the drill and mill paths (travelling 
// salesman problem).  

// Results can be used as images for PCB etch or G-code for PCB milling.
// Optionally (but recommended) smooths the milling path to produce 
// concise G code (use option -r [raw] to suppress smoothing).

// Isolation paths are effectively midway between copper traces meaning
// a mill need only make one cut; or (even with etch) the traces have
// maximum current carrying capacity.

// Smoothing reduces quality (albeit slightly) whereas the optimisation
// reduces cutting/drilling time substantially and reduces machine wear-
// and tear.

// Makes fairly extensive use of multiple processors, if available, but
// runs at low priority.  Writes much output to terminal - parallelism
// can mean the messages look a little jumbled (use -mp1 to stick to
// one processor).  Partly for this reason, prints a summary at the end.

/* Cisolate advantages.

 1.  Uses native Java libraries, no need to install anything beyond JRE.
     (This was a design intent and is intended to remain so)
 2.  Can operate purely from an image, so can be useful for repair etc
 (e.g. could use a cleaned up, scanned PCB image). 
 3.  Produces multiple alternate image views.  Good for validation.
 4.  Undertakes optimisation of Gcode for fewer transits (reduced manufacture
 time and less wear-and-tear) and shorter code.
 5.  Produces a CNC Drill file (as well as a CNC mill file and/or an etch image)
 6.  Attempts to warn if scale or copper assumptions look wrong.
 7.  Uses all machine processors in parallel (but at nice priority)
 
Disadvantages/Weaknesses

 1.  Does not work from Gerber source, needs image. (However other offline programs 
     can make this conversion)
 2.  Image must have drill points present, so that traces have 'lassoo' topology.
     Spade topology collapses to nothing.
 3.  Optimisations may still need optimising themselves for speed
 4.  Not all file types supported (just bmp/jpg) and has some constraints
     (e.g. input should be 24 bit colour)
 5.  Some variable parameters (cut depth, cut speed, etch width etc) currently hardcoded,
     likewise Gcode output will be METRIC (G21)

*/

private static final long serialVersionUID = 1L;

private static final String nL = System.getProperty("line.separator");

private ImageWriter writer;
private String descriptive;
private StringBuffer log;
private StringBuffer imgProperties;
private boolean millCode=true;
private boolean drillCode=true;
private int assessedCu=Board.CU_GUESS;
private BufferedImage img;
private Board board;
private JFrame f;
private JFileChooser fc;

private final double ZOOM_FACTOR=0.85;

JTabbedPane pane;

PCBPanel boardPanel,cutsPanel,drillPanel,drillPathPanel,heatPanel,junctionPanel,
         millPathPanel,pitchPanel,dukePanel,etchPanel;

final int BOARD_PANEL=0; // Change these numbers to change order of panels
final int CUTS_PANEL=1;  // ensure the numbering runs 0 to n with no gaps
final int DRILL_PANEL=2;
final int DRILL_PATH_PANEL=3;
final int HEAT_PANEL=4;
final int JUNCTION_PANEL=5;
final int MILL_PATH_PANEL=6;
final int PITCH_PANEL=7;
final int DUKE_PANEL=8;
final int ETCH_PANEL=9;

final Dimension screenSize;

final int [] panels= {BOARD_PANEL,CUTS_PANEL,DRILL_PANEL,DRILL_PATH_PANEL,HEAT_PANEL,
        JUNCTION_PANEL,MILL_PATH_PANEL,PITCH_PANEL,DUKE_PANEL,ETCH_PANEL};

BufferedImage sboard,scuts,sdrill,sdrillPath,sheat,sjunction,smillPath,spitch,sduke,setch;

private AffineTransform at;
private AffineTransformOp scaleOp;

private double xscale=1.0;
private double yscale=1.0;
private int xoffset=0;
private int yoffset=0;
private int etchWidth=1;

private JButton processButton;
protected boolean processing=false;

public static BufferedImage copySubImage(BufferedImage source,
                 int x,int y,double w,double h){
    int wi=(int)(0.5+w);
    int hi=(int)(0.5+h);

    BufferedImage b = new BufferedImage(wi,hi,BufferedImage.TYPE_INT_ARGB);
    Graphics g = b.getGraphics();
    g.drawImage(source,0,0,wi,hi,x,y,x+wi,y+hi,null);
    g.dispose();
    return b;
}
// ---------------------------------------------------------------
Cisolate() {

screenSize = Toolkit.getDefaultToolkit().getScreenSize();

f = new JFrame("Cisolate PCB route extraction"); 
f.addWindowListener(new WindowAdapter()  { 
   public void windowClosing(WindowEvent e) { System.exit(0); } });

f.setIconImage(new ImageIcon(getClass().getClassLoader().getResource(
   "resources/cisolateIcon.jpg")).getImage()); 

// Make like a native
try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } 
catch (Exception e) {  }

SwingUtilities.updateComponentTreeUI(f);
 
FileFilter filter = new FileNameExtensionFilter("jpg","jpeg","bmp"); // TODO not working
fc = new JFileChooser();
fc.addChoosableFileFilter(filter);

JMenuBar menuBar = new JMenuBar();
f.setJMenuBar(menuBar);
final JMenu jmFile    =new JMenu("File");
final JMenu jmView    =new JMenu("View");
final JMenu jmCreate  =new JMenu("Create");
final JMenu jmSettings=new JMenu("Settings");
final JMenu jmOptimise=new JMenu("Optimisations");
      JMenu jmHelp    =new JMenu("Help");

ButtonGroup radioGroup=new ButtonGroup();

processButton = new JButton("Start processing");
processButton.setEnabled(false);

final JSpinner drillOpt = new JSpinner(new SpinnerNumberModel(20, 0, 500, 1));
final JSpinner millOpt  = new JSpinner(new SpinnerNumberModel(20, 0, 500, 1));

final JPanel optPanel = new JPanel();
final JPanel millOPanel = new JPanel();
final JPanel drillOPanel = new JPanel();
JButton mButton=new JButton("Mill Optimise");
millOPanel.add(mButton,BorderLayout.WEST);
millOPanel.add(millOpt,BorderLayout.EAST);
JButton dButton=new JButton("Drill Optimise");
drillOPanel.add(dButton,BorderLayout.WEST);
drillOPanel.add(drillOpt,BorderLayout.EAST);
dButton.setEnabled(false);
mButton.setEnabled(false);

optPanel.add(drillOPanel);
optPanel.add(millOPanel);

final int myProcs=Runtime.getRuntime().availableProcessors();
final JComboBox<String> comboProcs = new JComboBox<>();

for (int i=1;i<=myProcs;i++)
  comboProcs.addItem("Cores : "+Integer.toString(i));

comboProcs.setSelectedIndex(myProcs-1); // Use maximum by default

//...................................... PROPERTIES .................................
final JMenuItem properties=new JMenuItem(new AbstractAction("Properties") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (board!=null) // Should never fail, when properties not greyed
      JOptionPane.showMessageDialog(f, board.imgProperties,
         "Cisolate : Image properties",JOptionPane.INFORMATION_MESSAGE); 
  }
});
//...................................... CLOSE ......................................
final JMenuItem close=new JMenuItem(new AbstractAction("Close file") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (JOptionPane.showConfirmDialog(f,"Are you sure you want to close the file?")==
        JOptionPane.YES_OPTION) {
      this.setEnabled(false); 
      jmView.setEnabled(false);
      properties.setEnabled(false);
      processButton.setBackground(Color.gray);
      processButton.setEnabled(false);
      board=null;
//      f.repaint();
    }
  }
});
//...................................... LOAD .......................................
JMenuItem load=new JMenuItem(new AbstractAction("Load file") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {

    // close old first, or is overwriting board enough?

    int returnVal = fc.showOpenDialog(f);

    if (returnVal == JFileChooser.APPROVE_OPTION) {
      board=new Board(fc.getSelectedFile()); //,Cisolate.this);
      f.getContentPane().setPreferredSize(Toolkit.getDefaultToolkit().getScreenSize());
//                      new Dimension());
//toolkit.getScreenSize()
//(int)(board.board.getWidth()/xscale),(int)(board.board.getHeight()/yscale)));
      close.setEnabled(true);
      jmView.setEnabled(true);
      properties.setEnabled(true); 
      processButton.setBackground(Color.green);
      processButton.setEnabled(true);

      xoffset=yoffset=0;
      at = new AffineTransform();

      double tmp=(double)board.board.getWidth()/(screenSize.getWidth()-50);
      double tmp2=(double)board.board.getHeight()/(screenSize.getHeight()-150);

      xscale=(tmp>tmp2)?tmp:tmp2;
if (xscale<1.0) xscale=1.0; // TODO not rigjht
      yscale=xscale; // Keep square (for now)

      at.scale(xscale,yscale); 
      scaleOp = 
        new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
   
      board.addObserver(new BoardObserver());
      board.aChange();
      processButton.repaint();
      if (board!=null) 
         JOptionPane.showMessageDialog(f, "Loaded "+board.imgProperties,
         "Cisolate : Image properties",JOptionPane.INFORMATION_MESSAGE); 
    } 
  }
});
//...................................... EXIT .....................................
JMenuItem exit=new JMenuItem(new AbstractAction("Exit") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (JOptionPane.showConfirmDialog(f,"Are you sure you want to exit?")==
        JOptionPane.YES_OPTION) 
      System.exit(0); 
  }
});
//.................................................................................
jmFile.add(load);
jmFile.add(close);
jmFile.add(properties);
jmFile.add(exit);
//...................................... ABOUT ....................................
JMenuItem about=new JMenuItem(new AbstractAction("About Cisolate") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
       JOptionPane.showMessageDialog(f, "Cisolate V1.0 : PCB trace and drill program."+nL+
       "Uses a cellular automata as the base method to extract features."+nL+nL+
       "Converts PCB image into G-code or etch images.  Traces are expanded"+nL+
       "to use all space.  Also determines drill points for a G-code drill file."+nL+
       "Undertakes optimisations of machine tool movements if required."+nL+nL+
       "By Stuart Combes, 2014-2016",
       "Cisolate",JOptionPane.INFORMATION_MESSAGE); 
     }
   });
jmHelp.add(about);
//.....................................................................................
final JRadioButtonMenuItem copperColourB=new JRadioButtonMenuItem("Copper Black.",true);
final JRadioButtonMenuItem copperColourW=new JRadioButtonMenuItem("Copper White.",false);

close.setEnabled(false);
properties.setEnabled(false);

final JCheckBoxMenuItem drillG =new JCheckBoxMenuItem("G Code for Drilling",true); 
final JCheckBoxMenuItem millG  =new JCheckBoxMenuItem("G Code for Milling",true); 
final JCheckBoxMenuItem etching=new JCheckBoxMenuItem("Image for Etching",true); 
final JCheckBoxMenuItem images =new JCheckBoxMenuItem("Diagnostic Images",false); 
jmCreate.add(drillG);
jmCreate.add(millG);
jmCreate.add(etching);
jmCreate.add(images);

final JCheckBoxMenuItem optD=new JCheckBoxMenuItem(
                        "Drilling G-code transits (faster for tool)",true); 
final JCheckBoxMenuItem optM=new JCheckBoxMenuItem(
                         "Milling G-code transits (faster for tool)",true); 
final JCheckBoxMenuItem optG=new JCheckBoxMenuItem(
          "Smoothed Milling G-code (less accurate, but much smaller file)",true); 
final JCheckBoxMenuItem optE=new JCheckBoxMenuItem(
          "Smoothed Etch file (less accurate, purely cosmetic)",false); 
jmOptimise.add(optD);
jmOptimise.add(optM);
jmOptimise.add(optG);
jmOptimise.add(optE);

jmSettings.add(copperColourB);
jmSettings.add(copperColourW);
radioGroup.add(copperColourB);
radioGroup.add(copperColourW);

//...................................... ZOOM IN ..........................................
final JMenuItem zoomin=new JMenuItem(new AbstractAction("Zoom in") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
      xscale/=ZOOM_FACTOR;
      yscale/=ZOOM_FACTOR;
      xoffset+=0.5*board.board.getWidth()*(1.0-ZOOM_FACTOR)/xscale;
      yoffset+=0.5*board.board.getHeight()*(1.0-ZOOM_FACTOR)/yscale;

      at = new AffineTransform();
      at.scale(xscale,yscale); 
      scaleOp = 
        new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);

      forceRedraw();
  }
});
//...................................... ZOOM OUT .........................................
final JMenuItem zoomout=new JMenuItem(new AbstractAction("Zoom out") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
      xoffset-=0.5*board.board.getWidth()*(1.0-ZOOM_FACTOR)/xscale;
      yoffset-=0.5*board.board.getHeight()*(1.0-ZOOM_FACTOR)/yscale;
      xscale*=ZOOM_FACTOR;
      yscale*=ZOOM_FACTOR;

      at = new AffineTransform();
      at.scale(xscale,yscale); 
      scaleOp = 
        new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);

      forceRedraw();
  }
});
jmView.add(zoomin);
jmView.add(zoomout);

pane=new JTabbedPane();

final BufferedImage cuts;

try {
  Insets insets = pane.getInsets();

  boardPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sboard!=null)
        g.drawImage(sboard,0,0,this);
    }
  };

  cutsPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (scuts!=null)
        g.drawImage(scuts,0,0,this);
    }
  };

  drillPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrill!=null)
        g.drawImage(sdrill,0,0,this);
    }
  };

  drillPathPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrillPath!=null)
        g.drawImage(sdrillPath,0,0,this);
    }
  };

  drillPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrill!=null)
        g.drawImage(sdrill,0,0,this);
    }
  };

  heatPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sheat!=null)
        g.drawImage(sheat,0,0,this);
    }
  };
  junctionPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sjunction!=null)
        g.drawImage(sjunction,0,0,this);
    }
  };
  millPathPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (smillPath!=null)
        g.drawImage(smillPath,0,0,this);
    }
  };
  pitchPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (spitch!=null)
        g.drawImage(spitch,0,0,this);
    }
  };
  dukePanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sduke!=null)
        g.drawImage(sduke,0,0,this);
    }
  };
  etchPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (setch!=null)
        g.drawImage(setch,0,0,this);
    }
  };

} catch(Exception e) { throw new RuntimeException(e);  }

int csum=0;
for (int p : panels) {
  csum+=p;
  switch (p) {
    case (BOARD_PANEL):       pane.addTab("Board",null,boardPanel,"The PCB as provided.  Does need to contain drill points to 'anchor' copper traces.");      break;
    case (CUTS_PANEL):        pane.addTab("Isolations",null,cutsPanel,"The computed (unsmoothed) isolation paths (green) between copper traces on the PCB "); break;
    case (DRILL_PANEL):       pane.addTab("Drill",null,drillPanel,"The identified points (purple) where the machine should drill holes.");           break;
    case (DRILL_PATH_PANEL):  pane.addTab("Drill Paths",null,drillPathPanel,"The transits that will be made between drill points."+
       " Good optimisation will show the paths often moving to nearest point."); break;
    case (HEAT_PANEL):        pane.addTab("Heat Map",null,heatPanel,"Bright (white) areas are where cut has most room to vary while"+
       " staying clear of copper.  Informs file size optimisation.");  break;
    case (JUNCTION_PANEL):    pane.addTab("Junctions",null,junctionPanel,"4-way (pink) and 3-way (blue) junctions for cuts.  Places"+
       " where optimiser can break (e.g. reverse or interrupt) cuts.  3-way junction can only be milled with extra transit.");    break;
    case (MILL_PATH_PANEL):   pane.addTab("Mill Paths",null,millPathPanel,"Extra transits (red) between cuts (green).  Good optimisation has little red.");  break;
    case (PITCH_PANEL):       pane.addTab("Pitch",null,pitchPanel,"Closest drill points highlighted in yellow.  Points with nearest"+
       " neighbour at ~0.1\" pitch are green.  Informs assessment that board size is correct.");    break;
    case (DUKE_PANEL):        pane.addTab("Duked",null,dukePanel,"Comparison of curves before and after straigtening.  Control points in red.");  break;
    case (ETCH_PANEL):        pane.addTab("Etch mask",null,etchPanel,"The mask to be used for etching the board rather than milling it.");  break;
    default:
  }
}
if ((pane.getTabCount()*(pane.getTabCount()-1)/2)!=csum) {
  System.out.println("Configuration error : board numbers do not run 0-n without gaps");
  System.exit(0);
}
for (int i=0;i<pane.getTabCount();i++)
  if (i!=BOARD_PANEL)
    pane.setEnabledAt(i,false); // All except board aren't ready yet

f.getContentPane().add(pane);

menuBar.add(jmFile);
menuBar.add(jmView);
menuBar.add(jmCreate);
menuBar.add(jmOptimise);
menuBar.add(jmSettings);
menuBar.add(jmHelp);
menuBar.add(processButton);
menuBar.add(drillOPanel);
menuBar.add(millOPanel);
menuBar.add(comboProcs);
jmView.setEnabled(false);

//...................................... PROCESS (or cancel) ..............................
processButton.addActionListener(new ActionListener() {

  public void actionPerformed(ActionEvent e) {  

    if (processing) {  // Cancel
      processing=false;
      board.gracefulExit();
      processButton.setBackground(Color.green);
      processButton.setText("Start processing");
      processButton.repaint();
      jmFile.setEnabled(true);
      jmCreate.setEnabled(true);
      jmSettings.setEnabled(true);
      jmOptimise.setEnabled(true);
      millOpt.setEnabled(true);
      drillOpt.setEnabled(true);
      comboProcs.setEnabled(true);

      f.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

    }
    else { // Initiate
      processing=true;

    // Confirm if our assessment of copper colour isn't the one set
    if ((copperColourW.isSelected()?Board.CU_WHITE:Board.CU_BLACK) != board.assessedCu)
      if (JOptionPane.showConfirmDialog(f,"You have set copper to be "+
        ((board.assessedCu==Board.CU_BLACK)?"white":"black")+
        " but this seems possibly wrong.  Change colour?",
        "Checking copper colour",JOptionPane.YES_NO_OPTION)==
            JOptionPane.YES_OPTION) 
        if (copperColourW.isSelected()) copperColourW.setSelected(false); 
        else                            copperColourW.setSelected(true);

    board.drillCode=drillG.isSelected(); 
    board.millCode = millG.isSelected(); 
    board.etch = etching.isSelected()?etchWidth:0; 
    board.verbose = images.isSelected(); 
    board.copper=copperColourW.isSelected()?Board.CU_WHITE:Board.CU_BLACK;
    board.tsd=(optD.isSelected()?(Integer)drillOpt.getValue():0);
    board.tsm=(optM.isSelected()?(Integer)millOpt.getValue():0);
    board.raw=(!optG.isSelected() && !optE.isSelected());
    board.smoothEtch=(optE.isSelected());

    board.maxprocs=comboProcs.getSelectedIndex()+1;

    processButton.setBackground(Color.red);
    processButton.setText("Cancel run");
    processButton.repaint();

    jmFile.setEnabled(false);
    jmCreate.setEnabled(false);
    jmSettings.setEnabled(false);
    jmOptimise.setEnabled(false);
    millOpt.setEnabled(false);
    drillOpt.setEnabled(false);
    comboProcs.setEnabled(false);

    f.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new Thread(board).start();
    }
  }
});

f.pack(); 

f.setVisible(true);
f.setResizable(true);

}
// ---------------------------------------------------------------
public void progress() { System.out.println(board.anneal.getProgress()+"   "+board.anneal.getFraction()/*+"  "+board.annealPair.getProgress()*/); } // TODO
// ---------------------------------------------------------------
private void forceRedraw() 
{
sboard=null; 
scuts=null;
sdrill=null;
sdrillPath=null;
sheat=null;
sjunction=null;
smillPath=null;
spitch=null;
sduke=null;
setch=null;
board.aChange();
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
public void paint(Graphics g) {
} 
// ---------------------------------------------------------------
class PCBPanel extends JPanel {

private static final long serialVersionUID = 1L;
int startx=-1;  
int starty=-1;
int offsetx=0;  
int offsety=0;

public PCBPanel(GridBagLayout gbl) {

  super(gbl);
  enableEvents(AWTEvent.MOUSE_EVENT_MASK |
               AWTEvent.MOUSE_MOTION_EVENT_MASK);
}
// ---------------------------------------------------------------
@Override
public void processMouseEvent(MouseEvent event) { 
  
  if (event.getID()==MouseEvent.MOUSE_PRESSED) {
    startx=event.getX();
    starty=event.getY();
  }
  else if (event.getID()==MouseEvent.MOUSE_RELEASED) {
    forceRedraw();
  }
  else
    super.processMouseEvent(event);
} 
// ---------------------------------------------------------------
@Override
public void processMouseMotionEvent(MouseEvent event) { 
  
  if (board==null) return;

  if (event.getID()==MouseEvent.MOUSE_DRAGGED) {

    xoffset+=(startx-event.getX());
    yoffset+=(starty-event.getY());

    startx=event.getX();
    starty=event.getY();
    int index=pane.getSelectedIndex();

    switch(index) {
      case (BOARD_PANEL):       sboard=null;      break;
      case (CUTS_PANEL):        scuts=null;       break;
      case (DRILL_PANEL):       sdrill=null;      break;
      case (DRILL_PATH_PANEL):  sdrillPath=null;  break;
      case (HEAT_PANEL):        sheat=null;       break;
      case (JUNCTION_PANEL):    sjunction=null;   break;
      case (MILL_PATH_PANEL):   smillPath=null;   break;
      case (PITCH_PANEL):       spitch=null;      break;
      case (DUKE_PANEL):        sduke=null;       break;
      case (ETCH_PANEL):        setch=null;       break;
      default: // Catch -1, nothing selected
    }
    board.aChange();
  }
  else
    super.processMouseEvent(event);
} 
// ---------------------------------------------------------------
}
public static void main(String args[]) 
{
Cisolate cisolate;

String fname="";
if (args.length==0) {
  System.out.println("Cisolate.  Command line usage java Cisolate imagefilename flags or java -jar Cisolate.jar imagefilename flags");
  System.out.println("Flags");
  System.out.println("-q     : Quiet output; does not write pictures to subdirectory.");
  System.out.println("-OdXX  : Optimisation of drill path with XX iterations; default -Od20");
  System.out.println("-OmXX  : Optimisation of mill path with XX iterations; default -Om20");
  System.out.println("[Suppress optimisation with -Om0 -Od0]"); 
  System.out.println("-cb    : Copper traces are black in image");
  System.out.println("-cw    : Copper traces are white in image");
  System.out.println("-r     : Raw output; cuts are not smoothed.  Default is smoothed.");
  System.out.println("-mpXX  : Maximum processor cores to use, default is all.");
  System.out.println("-eXX   : Produce PCB etch file with line width XX pixels.");
  System.out.println("-o     : Overwrite existing files (otherwise fail).");
  System.out.println("-fXX   : Force to use particular DPI."+nL);

  System.out.println("Example 'java cisolate/Cisolate pcb.bmp -cb -Od40 -o'");
  System.out.println("or 'java cisolate/Cisolate' which starts GUI");
  System.out.println("or 'java -jar Cisolate.jar' also starts GUI");


  cisolate=new Cisolate();   // Set up GUI
} 
else {
  fname=args[0];

  boolean [] known=new boolean[args.length];

  boolean verbose=true;
  int tsd=20; // Default
  int tsm=20;
  int copper=Board.CU_GUESS;
  int maxprocs=999;
  boolean raw=false;
  boolean overwrite=false;
  int etch=0;
  int forceDPI=0;

  for (int i=1;i<args.length;i++) {
    if (args[i].equals("-q")) {
      verbose=false;
      known[i]=true;
    }
    else if (args[i].equals("-r")) {
      raw=true;
      known[i]=true;
    }
    else if (args[i].equals("-o")) {
      overwrite=true;
      known[i]=true;
    }
    else if (args[i].length() < 2) { }

    else if (args[i].length() < 3) { }
    else if (args[i].substring(0,3).equals("-cw")) {
      copper=Board.CU_WHITE;
      known[i]=true;
    }
    else if (args[i].substring(0,3).equals("-cb")) {
      copper=Board.CU_BLACK;
      known[i]=true;
    }
    else if (args[i].substring(0,3).equals("-mp")) {
      maxprocs=Integer.parseInt(args[i].substring(3));
      if (maxprocs>0)
        known[i]=true;
      else
        maxprocs=1;  // Must use at least one.
    }
    else if (args[i].substring(0,3).equals("-Od")) {
      tsd=Integer.parseInt(args[i].substring(3));
      if (tsd>=0)
        known[i]=true;
    }
    else if (args[i].substring(0,3).equals("-Om")) {
      tsm=Integer.parseInt(args[i].substring(3));
      if (tsm>=0)
        known[i]=true;
    }
    else if (args[i].substring(0,2).equals("-e")) {
      etch=Integer.parseInt(args[i].substring(2));
      if (etch>=0)
        known[i]=true;
    }
    else if (args[i].substring(0,2).equals("-f")) {
      forceDPI=Integer.parseInt(args[i].substring(2));
      if (forceDPI>=0)
        known[i]=true;
    }
  }

  for (int i=1;i<args.length;i++) 
    if (!known[i]) {
      System.out.println("Unknown option "+args[i]+" : Ignoring it.");
 // TODO    cisolate.log.append("Unknown option "+args[i]+" : Ignored."+nL);
    }

  Board sboard=new Board(fname,verbose,tsd,tsm,copper,
    maxprocs,raw,etch,overwrite,forceDPI);

  new Thread(sboard).start();
  }
}
// ----------------------------------------------------------------
private class BoardObserver implements Observer {

public void update(Observable obs, Object obj) {

 Board obsBoard=(Board)obs;

 if (obsBoard==null) return;

 if (obsBoard.complete) {
   obsBoard.complete=false;

   processButton.setBackground(Color.green);
   processButton.setText("Start processing");
   processButton.repaint();
   processing=false;
   f.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

   JOptionPane.showMessageDialog(f, obsBoard.log,
      "Cisolate : Run summary",JOptionPane.INFORMATION_MESSAGE); 
  }

 int x0=xoffset<0?0:xoffset;
 int y0=yoffset<0?0:yoffset;

 if (obsBoard.board!=null && sboard==null) {

   BufferedImage b=copySubImage(obsBoard.board,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sboard=scaleOp.filter(b,null);
   boardPanel.repaint();
 }

 if (obsBoard.cuts!=null && scuts==null) { 
   BufferedImage b=copySubImage(obsBoard.cuts,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   scuts=scaleOp.filter(b,null);
   pane.setEnabledAt(CUTS_PANEL,true);
   cutsPanel.repaint();
 }
 if (obsBoard.drill!=null && sdrill==null) { 
   BufferedImage b=copySubImage(obsBoard.drill,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sdrill=scaleOp.filter(b,null);
   pane.setEnabledAt(DRILL_PANEL,true);
   drillPanel.repaint();
 }
 if (obsBoard.drillPath!=null && sdrillPath==null) { 
   BufferedImage b=copySubImage(obsBoard.drillPath,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sdrillPath=scaleOp.filter(b,null);
   pane.setEnabledAt(DRILL_PATH_PANEL,true);
   drillPathPanel.repaint();
 }
 if (obsBoard.heat!=null && sheat==null) { 
   BufferedImage b=copySubImage(obsBoard.heat,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sheat=scaleOp.filter(b,null);
   pane.setEnabledAt(HEAT_PANEL,true);
   heatPanel.repaint();
 }
 if (obsBoard.junction!=null && sjunction==null) { 
   BufferedImage b=copySubImage(obsBoard.junction,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sjunction=scaleOp.filter(b,null);
   pane.setEnabledAt(JUNCTION_PANEL,true);
   junctionPanel.repaint();
 }
 if (obsBoard.millPath!=null && smillPath==null) { 
   BufferedImage b=copySubImage(obsBoard.millPath,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   smillPath=scaleOp.filter(b,null);
   pane.setEnabledAt(MILL_PATH_PANEL,true);
   millPathPanel.repaint();
 }
 if (obsBoard.pitch!=null && spitch==null) { 
   BufferedImage b=copySubImage(obsBoard.pitch,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   spitch=scaleOp.filter(b,null);
   pane.setEnabledAt(PITCH_PANEL,true);
   pitchPanel.repaint();
 }
 if (obsBoard.duke!=null && sduke==null) { 
   BufferedImage b=copySubImage(obsBoard.duke,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   sduke=scaleOp.filter(b,null);
   pane.setEnabledAt(DUKE_PANEL,true);
   dukePanel.repaint();
 }
 if (obsBoard.etched!=null && setch==null) { 
   BufferedImage b=copySubImage(obsBoard.etched,x0,y0,obsBoard.board.getWidth()/xscale,
                                obsBoard.board.getHeight()/yscale);
   setch=scaleOp.filter(b,null);
   pane.setEnabledAt(ETCH_PANEL,true);
   etchPanel.repaint();
 }
} 
// ---------------------------------------------------------------
}
}
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

import org.w3c.dom.*;
import java.awt.*;
import javax.swing.*;
import java.awt.image.*; 
import java.awt.event.*; 
import java.io.*; 
import java.net.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.geom.AffineTransform;
import javax.imageio.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.util.prefs.Preferences;
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

// Code is compilable at various Java versions, but uses 'observer' which
// is deprecated in Java 9.  Currently recommended compilation is at Java 7
// which allows use on existing JVM machines 7,8,9 (and probably 10+).

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
 5.  Gcode output will be METRIC (G21)

*/

private static final long serialVersionUID = 1L;

private static final String nL = System.getProperty("line.separator");

private static String disclaim=
         "THIS PROGRAM PRODUCES CUTTING PATHS FOR MACHINE TOOLS AND THEREFORE HAS"+nL+
         "SAFETY IMPLICATIONS. USE AT YOUR OWN RISK. IT IS ADVISABLE TO INSPECT A"+nL+
         "PATH IN A VIEWER BEFORE OPERATING A MACHINE."+nL+nL+
         "Therefore, as stated in the licence, this program is distributed in the"+nL+
         "hope that it will be useful, but WITHOUT ANY WARRANTY; without even the"+nL+
         "implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.";

private ImageWriter writer;
private String descriptive;
private StringBuffer log;
private StringBuffer imgProperties;
private boolean millCode=true;
private boolean drillCode=true;
private int assessedCu=Board.CU_GUESS;
private BufferedImage img;
private Board board;
protected JFrame frame;
private JFileChooser fc;

private final double ZOOM_FACTOR=0.85;

JTabbedPane pane;

PCBPanel boardPanel,cutsPanel,drillPanel,drillPathPanel,heatPanel,junctionPanel,
         millPathPanel,pitchPanel,dukePanel,etchPanel,gcodePanel;

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
final int GCODE_PANEL=10;

Dimension screenSize=null;

final int [] panels= {BOARD_PANEL,CUTS_PANEL,DRILL_PANEL,DRILL_PATH_PANEL,HEAT_PANEL,
        JUNCTION_PANEL,MILL_PATH_PANEL,PITCH_PANEL,DUKE_PANEL,ETCH_PANEL,GCODE_PANEL};

BufferedImage sboard,scuts,sdrill,sdrillPath,sheat,sjunction,smillPath,spitch,sduke,setch,sgcode;

private AffineTransform at,atG;
private AffineTransformOp scaleOp;  // Scales the images except ...
private AffineTransformOp scaleOpG; // ... scales the G code image

private double xscale=1.0;
private double yscale=1.0;
private int xoffset=0;
private int yoffset=0;
private int etchWidth=1; // pixels
private int plungeRate=10;       // mm/min for G Code
private int millRate=20;         // mm/min for G Code
private double drillPlunge=-1.0; // mm, for G Code
private double drillTransit=1.0; // mm, for G Code
private double millPlunge=-0.3;  // mm, for G Code
private double millTransit=1.0;  // mm, for G Code
private double backlash=0.4;     // mm, for G Code
private boolean doBacklash=false; // Compensates for machine backlash in G Code
private int drillReps=20;
private int millReps =20;
private Preferences prefs;
private BufferedImage blank;
private int forceDPI=0;  // 0 = use natural DPI
private boolean cuWhite=false;
private static boolean flipped=false;

private JButton processButton;
protected boolean processing=false;
JMenu jmFile;
JMenu jmView;
JMenu jmSettings;
JMenu jmHelp;
JComboBox<String> comboProcs;

// ---------------------------------------------------------------
public static BufferedImage copySubImage(BufferedImage source,
                 int x,int y,double w,double h){
int wi=(int)(0.5+w);
int hi=(int)(0.5+h);

BufferedImage b=new BufferedImage(wi,hi,BufferedImage.TYPE_INT_ARGB);
Graphics g = b.getGraphics();
g.drawImage(source,0,0,wi,hi,x,y,x+wi,y+hi,null);
g.dispose();
return b;
}
// ---------------------------------------------------------------
private void resetImage() 
{
xoffset=yoffset=0;
double tmp=(double)(screenSize.getWidth()-50)/board.board.getWidth();
double tmp2=(double)(screenSize.getHeight()-150)/board.board.getHeight();

xscale=(tmp<tmp2)?tmp:tmp2;
yscale=xscale; // Keep square (for now)
}
// ---------------------------------------------------------------
private void rescale() 
{
at.setToIdentity();
at.scale((flipped?(-xscale):xscale),yscale); 
atG.setToIdentity();

double ratio=board.gcodeToImageRatio(forceDPI);

atG.scale(xscale/ratio,yscale/ratio);   // G code never flipped
if (flipped) at.translate(-(double)board.board.getWidth(),0.0);
scaleOp =new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
scaleOpG=new AffineTransformOp(atG,AffineTransformOp.TYPE_BILINEAR);
forceRedraw();
}
// ---------------------------------------------------------------
private void boardOnly() 
{
for (int i=0;i<pane.getTabCount();i++)
  if (i!=BOARD_PANEL)
  pane.setEnabledAt(i,false); 
pane.setSelectedIndex(BOARD_PANEL);   
}
// ---------------------------------------------------------------
Cisolate() {

prefs = Preferences.userRoot().userNodeForPackage(this.getClass());

etchWidth=prefs.getInt("ETCH_WIDTH", 1); // pixels

plungeRate=prefs.getInt("PLUNGE_RATE",10);    // mm/min, for G Code
millRate=prefs.getInt("MILL_RATE",20);        // mm/min, for G Code

drillPlunge=prefs.getDouble("DRILL_PLUNGE",-1.0);  // mm, for G Code
drillTransit=prefs.getDouble("DRILL_TRANSIT",1.0); // mm, for G Code
millPlunge=prefs.getDouble("MILL_PLUNGE",-0.3);    // mm, for G Code
millTransit=prefs.getDouble("MILL_TRANSIT",1.0);   // mm, for G Code
backlash=prefs.getDouble("BACKLASH",0.4);          // mm, for G Code
doBacklash=prefs.getBoolean("DO_BACKLASH",false);          

at =new AffineTransform();
atG=new AffineTransform();

try { screenSize = Toolkit.getDefaultToolkit().getScreenSize(); }
catch(HeadlessException e) {
  System.out.println(nL+"Starting without command line options invokes the GUI");
  System.out.println("but you seem to be on a headless machine.  Exiting.");
  System.exit(0);
}
blank=new BufferedImage(640,480,BufferedImage.TYPE_INT_ARGB);

frame=new JFrame("Cisolate PCB route extraction"); 
frame.setPreferredSize(new Dimension(600,400));

frame.addWindowListener(new WindowAdapter()  { 
   public void windowClosing(WindowEvent e) { System.exit(0); } });
   
frame.setIconImage(new ImageIcon(getClass().getClassLoader().getResource(
   "resources/cisolateIcon.jpg")).getImage()); 

// Make like a native
try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());} 
catch (Exception e) {  }

SwingUtilities.updateComponentTreeUI(frame);
FileFilter filter=new FileNameExtensionFilter("jpg","jpeg","bmp"); // TODO not working
fc = new JFileChooser(prefs.get("LAST_FOLDER",new File(".").getAbsolutePath()));
fc.addChoosableFileFilter(filter);

JMenuBar menuBar = new JMenuBar();
frame.setJMenuBar(menuBar);
jmFile    =new JMenu("File");
jmView    =new JMenu("View");
jmSettings=new JMenu("Settings");
jmHelp    =new JMenu("Help");

// Sub-menus of settings
//final JMenu jmImgSettings =new JMenu("Image Settings");
final JMenu jmCreate      =new JMenu("Create");
final JMenu jmOptimise    =new JMenu("Optimisations");

final JMenuItem jmImgSettings=new JMenuItem(new AbstractAction("Image Settings") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    
    JTextField forceDPIField=new JTextField(6);
    forceDPIField.setText(String.format("%d",forceDPI));
    ButtonGroup radioGroup=new ButtonGroup();
    JRadioButton copperColourB=new JRadioButton("Copper Black",!cuWhite);
    JRadioButton copperColourW=new JRadioButton("Copper White",cuWhite);
    radioGroup.add(copperColourB);
    radioGroup.add(copperColourW);

    JCheckBox checkbox=new JCheckBox("Flip board image");

    final JPanel myPanel=new JPanel();
    myPanel.setLayout(new BoxLayout(myPanel,BoxLayout.Y_AXIS));
    myPanel.add(copperColourB);
    myPanel.add(copperColourW);
    myPanel.add(new JLabel("Force image DPI to be (0 = use DPI from image) :"));
    myPanel.add(forceDPIField);
    myPanel.add(checkbox);
    checkbox.setSelected(flipped);
    
    int result=JOptionPane.showConfirmDialog(frame,myPanel,
               "Image Settings",JOptionPane.OK_CANCEL_OPTION); 
    if (result==JOptionPane.OK_OPTION) {
      forceDPI=Integer.parseInt(forceDPIField.getText());
      cuWhite=copperColourW.isSelected();
      if (flipped!=checkbox.isSelected()) {
        flipped=(!flipped);
        board.cleanUp();
        resetImage();
        rescale();
        boardOnly();
      }
    }
  }
});

final JMenuItem jmReps=new JMenuItem(new AbstractAction("Replications") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    
    JTextField  drillRepsField=new JTextField(6);
    JTextField  millRepsField =new JTextField(6);

    drillRepsField.setText(String.format("%d",drillReps));
    millRepsField.setText(String.format("%d",millReps));

    final JPanel myPanel=new JPanel();
    myPanel.setLayout(new BoxLayout(myPanel,BoxLayout.Y_AXIS));
    myPanel.add(new JLabel("DRILL optimisation replications :"));
    myPanel.add(drillRepsField);
    myPanel.add(Box.createHorizontalStrut(15)); // a spacer
    myPanel.add(new JLabel("MILL optimisation replications :"));
    myPanel.add(millRepsField);
    
    int result=JOptionPane.showConfirmDialog(frame,myPanel,
               "Replications",JOptionPane.OK_CANCEL_OPTION); 
    if (result==JOptionPane.OK_OPTION) {
      drillReps=Integer.parseInt(drillRepsField.getText());
      millReps=Integer.parseInt(millRepsField.getText());
    }
  }
});

final JMenuItem jmGSettings=new JMenuItem(new AbstractAction("G Code Settings") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    
    JTextField plungeRateField=new JTextField(5);
    JTextField millRateField=new JTextField(5);

    JTextField drillTransitField=new JTextField(7);
    JTextField drillPlungeField =new JTextField(7);
    JTextField  millTransitField=new JTextField(7);
    JTextField  millPlungeField =new JTextField(7);
    JTextField  backlashField =new JTextField(7);
    JCheckBox checkbox=new JCheckBox("Enable backlash compensation");

    plungeRateField.setText(String.format("%d",plungeRate));
    millRateField.setText(String.format("%d",millRate));

    drillTransitField.setText(String.format("%f",drillTransit));
    drillPlungeField.setText(String.format("%f",drillPlunge));
    millTransitField.setText(String.format("%f",millTransit));
    millPlungeField.setText(String.format("%f",millPlunge));
    backlashField.setText(String.format("%f",backlash));
    checkbox.setSelected(doBacklash);
    
    final JPanel myPanel = new JPanel();
    myPanel.setLayout(new BoxLayout(myPanel,BoxLayout.Y_AXIS));
    myPanel.add(new JLabel("Plunge (for drill and mill) rate (mm/min):"));
    myPanel.add(plungeRateField);

    myPanel.add(new JLabel("DRILL transit height (absolute, mm):"));
    myPanel.add(drillTransitField);
    myPanel.add(Box.createHorizontalStrut(15)); // a spacer
    myPanel.add(new JLabel("Plunge to (absolute, mm):"));
    myPanel.add(drillPlungeField);
    myPanel.add(Box.createVerticalStrut(2)); // a spacer
    myPanel.add(new JLabel("Mill rate (mm/min):"));
    myPanel.add(millRateField);

    myPanel.add(new JLabel("MILL transit height (absolute, mm):"));
    myPanel.add(millTransitField);
    myPanel.add(Box.createHorizontalStrut(15)); // a spacer
    myPanel.add(new JLabel("Cut at (absolute, mm):"));
    myPanel.add(millPlungeField);
    myPanel.add(new JLabel("Backlash compensation radius (mm):"));
    myPanel.add(backlashField);
    myPanel.add(checkbox);
    
    int result=JOptionPane.showConfirmDialog(frame,myPanel,
          "G Code parameters",JOptionPane.OK_CANCEL_OPTION); 
    if (result==JOptionPane.OK_OPTION) {
      plungeRate=Integer.parseInt(plungeRateField.getText());
      millRate=Integer.parseInt(millRateField.getText());

      drillTransit=Double.parseDouble(drillTransitField.getText());
      drillPlunge=Double.parseDouble(drillPlungeField.getText());
      millTransit=Double.parseDouble(millTransitField.getText());
      millPlunge=Double.parseDouble(millPlungeField.getText());
      backlash=Double.parseDouble(backlashField.getText());
      doBacklash=checkbox.isSelected();

      prefs.putInt("PLUNGE_RATE",plungeRate); 
      prefs.putInt("MILL_RATE",millRate);
      
      prefs.putDouble("DRILL_PLUNGE",drillPlunge); 
      prefs.putDouble("DRILL_TRANSIT",drillTransit); 
      prefs.putDouble("MILL_PLUNGE",millPlunge); 
      prefs.putDouble("MILL_TRANSIT",millTransit); 
      
      prefs.putDouble("BACKLASH",backlash); 
      prefs.putBoolean("DO_BACKLASH",doBacklash); 
    }
   }
});

final JMenuItem jmEtchSettings=new JMenuItem(new AbstractAction("Etch Settings") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    
    JTextField  etchField =new JTextField(5);
    etchField.setText(String.format("%d",etchWidth));
    final JPanel etchPanel = new JPanel();
    etchPanel.add(new JLabel("Etch width (pixels):"));
    etchPanel.add(etchField);
    
    int result=JOptionPane.showConfirmDialog(frame,etchPanel,
       "Etch parameters",JOptionPane.OK_CANCEL_OPTION); 
    if (result==JOptionPane.OK_OPTION) {
      etchWidth=Integer.parseInt(etchField.getText());
      prefs.putInt("ETCH_WIDTH",etchWidth);    
    }
  }
});

processButton = new JButton("Start processing");
processButton.setEnabled(false);

final int myProcs=Runtime.getRuntime().availableProcessors();
comboProcs=new JComboBox<>();
for (int i=1;i<=myProcs;i++)
  comboProcs.addItem("Cores : "+Integer.toString(i));

comboProcs.setSelectedIndex(myProcs-1); // Use maximum by default

//...................................... PROPERTIES .................................
final JMenuItem properties=new JMenuItem(new AbstractAction("Properties") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (board!=null) // Should never fail, when properties not greyed
      JOptionPane.showMessageDialog(frame,board.imgProperties,
         "Cisolate : Image properties",JOptionPane.INFORMATION_MESSAGE); 
  }
});
//...................................... CLOSE ......................................
final JMenuItem close=new JMenuItem(new AbstractAction("Close file") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (JOptionPane.showConfirmDialog(frame,
       "Are you sure you want to close the file?")==JOptionPane.YES_OPTION) {
      this.setEnabled(false); 
      jmView.setEnabled(false);
      properties.setEnabled(false);
      processButton.setBackground(Color.gray);
      processButton.setEnabled(false);
      board.deleteObservers();  // Important, even though we delete board
      boardOnly();
      board=null;
      boardPanel.repaint();
      forceRedraw();
    }
  }
});
//...................................... LOAD .......................................
JMenuItem load=new JMenuItem(new AbstractAction("Load file") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {

    if (fc.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION) {
      if (board!=null) board.deleteObservers();  // Important
      board=new Board(fc.getSelectedFile(),frame); 
      boardOnly();
      forceRedraw();
      prefs.put("LAST_FOLDER", fc.getSelectedFile().getParent());
      frame.getContentPane().setPreferredSize(Toolkit.getDefaultToolkit().getScreenSize());
      close.setEnabled(true);
      jmView.setEnabled(true);
      properties.setEnabled(true); 
      processButton.setBackground(Color.green);
      processButton.setEnabled(true);

      resetImage();
      rescale();   
      
      board.addObserver(new BoardObserver());
      board.aChange();
      processButton.repaint();
      JOptionPane.showMessageDialog(frame,"Loaded "+board.imgProperties,
        "Cisolate : Image properties",JOptionPane.INFORMATION_MESSAGE);
      forceDPI=0;        
    } 
  }
});
//...................................... EXIT .....................................
JMenuItem exit=new JMenuItem(new AbstractAction("Exit") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    if (JOptionPane.showConfirmDialog(frame,"Are you sure you want to exit?")==
        JOptionPane.YES_OPTION) 
      System.exit(0); 
  }
});
//.................................................................................
jmFile.add(load);
jmFile.add(close);
jmFile.add(properties);
jmFile.add(exit);

//...................................... Licence ....................................
JMenuItem licence=new JMenuItem(new AbstractAction("Licence") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {

    JLabel label = new JLabel();
    Font font = label.getFont();

    StringBuffer style=new StringBuffer("font-family:"+font.getFamily()+";");
    style.append("font-weight:"+(font.isBold()?"bold":"normal")+";");
    style.append("font-size:"+font.getSize()+"pt;");

    JEditorPane ep=new JEditorPane("text/html","<html><body style=\""+style+"\">"+
    "This program is free software: you can redistribute it and/or modify<br>"+
    "it under the terms of the GNU General Public License as published by<br>"+
    "the Free Software Foundation, either version 3 of the License, or<br>"+
    "(at your option) any later version.<br><br>"+
    "This program is distributed in the hope that it will be useful,<br>"+
    "but WITHOUT ANY WARRANTY; without even the implied warranty of<br>"+
    "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the<br>"+
    "GNU General Public License <a href=\"http://www.gnu.org/licenses/\""+
    ">http://www.gnu.org/licenses/</a><br></body></html>");

    ep.addHyperlinkListener(new HyperlinkListener()
    {
      @Override
      public void hyperlinkUpdate(HyperlinkEvent e)
      {
        if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
          try {
            java.awt.Desktop.getDesktop().browse(new URI(e.getURL().toString()));
          } catch(IOException ioe) {
            System.out.println("The system cannot find the file specified");
          } catch(URISyntaxException use) {
            System.out.println("Illegal character in path");
          }
        }
      }
    });
    ep.setEditable(false);
    ep.setBackground(label.getBackground());

    JOptionPane.showMessageDialog(frame,ep,"Licence",
         JOptionPane.INFORMATION_MESSAGE);    
  }
});
jmHelp.add(licence);
//...................................... Disclaimer ....................................
JMenuItem disclaimer=new JMenuItem(new AbstractAction("Disclaimer") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
       JOptionPane.showMessageDialog(frame,disclaim,"Disclaimer",
                                     JOptionPane.WARNING_MESSAGE); 
     }
   });
jmHelp.add(disclaimer);
//...................................... Tips ....................................
JMenuItem tips=new JMenuItem(new AbstractAction("Hints and Tips") {
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    JOptionPane.showMessageDialog(frame, 
      "Results are put in a subdirectory with the same name as the image file."+nL+
      ".tap files contain the generated G-Code"+nL+nL+
      "G Code has (0.0,0.0) as top left of image."+nL+nL+
      "If results look odd, check that copper colour was correctly set."+nL+nL+
      "Cisolate works from the perspective of a system milling/drilling"+nL+
      "from above.  You may need to use the 'flip' option if your image"+nL+
      "is not from that perspective (e.g. with top and bottom images of"+nL+
      "boards that can be overlaid, one must be flipped/mirrored to get"+nL+
      "that perspective)"+nL+nL+
      "Some boards need more than the default 20 iterations to fully optimise paths"+nL+
      "and computer time is likely a better investment that machine time."+nL+nL+ 
      "Once processing has started on a board, other tabs (initially greyed out) become"+nL+
      "selectable as the different elements of the analysis complete."+nL+nL+
      "G Code output is Metric (G21)"+nL+nL+
      "Depths in GCode are absolute.  A convention must be adopted such as"+nL+
      "the upper face of the board being at 0.0, in which case a milling depth"+nL+
      "of 0.3mm should remove 0.3mm of copper and a transit at 1.0mm should"+nL+
      "have 1mm clearance from the board."+nL+nL+
      "Any image must include drill points as otherwise a line of copper that"+nL+
      "does not connect with other copper (a salient) is seen as something that"+nL+
      "could not achieve electrical isolation and is automatically removed."+nL+nL+
      "Backlash compensation will greatly increase milling time and should not "+nL+
      "be used unless necessary."+nL+nL+
      "Machine settings (e.g. feed rates, cut depths) are remembered when program"+nL+
      "is restarted, image specific settings (e.g. copper colour) are not.",
    "Hints and Tips",JOptionPane.INFORMATION_MESSAGE); 
  }
});
jmHelp.add(tips);
//...................................... ABOUT ....................................
JMenuItem about=new JMenuItem(new AbstractAction("About Cisolate") {
// using https://stackoverflow.com/questions/8348063/clickable-links-in-joptionpane
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
    
    JLabel label = new JLabel();
    Font font = label.getFont();

    StringBuffer style=new StringBuffer("font-family:"+font.getFamily()+";");
    style.append("font-weight:"+(font.isBold()?"bold":"normal")+";");
    style.append("font-size:"+font.getSize()+"pt;");

    JEditorPane ep=new JEditorPane("text/html", "<html><body style=\"" + style + "\">" //
            +"Cisolate V2.0 : PCB trace and drill program.<br>"+
       "Allowing machine tools to cut PCBs (isolations and drill paths)<br>"+
       "Uses a cellular automata as the base method to extract features.<br><br>"+
       "Converts PCB image into G-code or etch images.  Traces are expanded<br>"+
       "to use all space.  Also determines drill points for a G-code drill file.<br>"+
       "Undertakes optimisations of machine tool movements if required.<br>"+"Detailed "+
       "<a href=\"https://oilulio.wordpress.com/2016/01/02/cisolate-pcb-construction/\""+
       ">description</a>"+
       "<br><br>By Stuart Combes, 2014-2018</body></html>");

    ep.addHyperlinkListener(new HyperlinkListener()
    {
      @Override
      public void hyperlinkUpdate(HyperlinkEvent e)
      {
        if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
          try {
            java.awt.Desktop.getDesktop().browse(new URI(e.getURL().toString()));
          } catch(IOException ioe) {
            System.out.println("The system cannot find the file specified");
          } catch(URISyntaxException use) {
            System.out.println("Illegal character in path");
          }
        }
      }
    });
    ep.setEditable(false);
    ep.setBackground(label.getBackground());

    JOptionPane.showMessageDialog(frame,ep,"About Cisolate",
         JOptionPane.INFORMATION_MESSAGE);    
  }
});
jmHelp.add(about);
//.....................................................................................
close.setEnabled(false);
properties.setEnabled(false);

final JCheckBoxMenuItem drillG =new JCheckBoxMenuItem("G Code for Drilling",true); 
final JCheckBoxMenuItem millG  =new JCheckBoxMenuItem("G Code for Milling",true); 
final JCheckBoxMenuItem etching=new JCheckBoxMenuItem("Image for Etching",true); 
final JCheckBoxMenuItem images =new JCheckBoxMenuItem("Diagnostic Image Files",false); 
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

//...................................... SCREENFIT ..........................................
final JMenuItem screenfit=new JMenuItem(new AbstractAction("Fit to screen") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
      resetImage(); 
      rescale();
  }
});
//...................................... ZOOM IN ..........................................
final JMenuItem zoomin=new JMenuItem(new AbstractAction("Zoom in") { 
  private static final long serialVersionUID = 1L;
  public void actionPerformed(ActionEvent e) {
      xscale/=ZOOM_FACTOR;
      yscale/=ZOOM_FACTOR;
      xoffset+=0.5*board.board.getWidth()*(1.0-ZOOM_FACTOR)/xscale;
      yoffset+=0.5*board.board.getHeight()*(1.0-ZOOM_FACTOR)/yscale;
      rescale();
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
      rescale();
  }
});
jmView.add(screenfit);
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
      if (sboard==null) g.drawImage(blank, 0,0,this);
      else              g.drawImage(sboard,0,0,this);
    }
  };

  cutsPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (scuts!=null)  g.drawImage(scuts,0,0,this);
    }
  };

  drillPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrill!=null)  g.drawImage(sdrill,0,0,this);
    }
  };

  drillPathPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrillPath!=null)  g.drawImage(sdrillPath,0,0,this);
    }
  };

  drillPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sdrill!=null)  g.drawImage(sdrill,0,0,this);
    }
  };

  heatPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sheat!=null) g.drawImage(sheat,0,0,this);
    }
  };
  junctionPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sjunction!=null) g.drawImage(sjunction,0,0,this);
    }
  };
  millPathPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (smillPath!=null)  g.drawImage(smillPath,0,0,this);
    }
  };
  pitchPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (spitch!=null) g.drawImage(spitch,0,0,this);
    }
  };
  dukePanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sduke!=null) g.drawImage(sduke,0,0,this);
    }
  };
  etchPanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (setch!=null) g.drawImage(setch,0,0,this);
    }
  };
  gcodePanel=new PCBPanel(new GridBagLayout()) {
    private static final long serialVersionUID = 1L;
    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (sgcode!=null) g.drawImage(sgcode,0,0,this);
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
    case (GCODE_PANEL):       pane.addTab("G-code",null,gcodePanel,"Independently generated results of the milling G-code.");  break;
    default:
  }
}
if ((pane.getTabCount()*(pane.getTabCount()-1)/2)!=csum) {
  System.out.println("Configuration error : board numbers do not run 0-n without gaps");
  System.exit(0);
}
boardOnly(); // All except board aren't ready yet

frame.getContentPane().add(pane);

jmSettings.add(jmImgSettings);
jmSettings.add(jmCreate);
jmSettings.add(jmOptimise);
jmSettings.add(jmReps);
jmSettings.add(jmGSettings);
jmSettings.add(jmEtchSettings);

menuBar.add(jmFile);
menuBar.add(jmView);
menuBar.add(jmSettings);
menuBar.add(jmHelp);
menuBar.add(processButton);
menuBar.add(Box.createHorizontalGlue());
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
      jmSettings.setEnabled(true);
      comboProcs.setEnabled(true);
      for (int i=0;i<pane.getTabCount();i++)
        if (i!=BOARD_PANEL)
          pane.setEnabledAt(i,false); 
      pane.setSelectedIndex(BOARD_PANEL);  
      frame.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
    }
    else { // Initiate
      processing=true;
      board.skeleton=null; // Make sure any cancel worked
      board.anneal=null;
      board.annealPair=null;

    // Confirm if our assessment of copper colour isn't the one set
    if ((cuWhite?Board.CU_WHITE:Board.CU_BLACK)!=board.assessedCu) {
      if (JOptionPane.showConfirmDialog(frame,"You have set copper to be "+
        ((board.assessedCu==Board.CU_BLACK)?"white":"black")+
        " but this seems possibly wrong.  Change colour?",
        "Checking copper colour",JOptionPane.YES_NO_OPTION)==
            JOptionPane.YES_OPTION) 
        cuWhite=(!cuWhite);
    }
    board.drillCode=drillG.isSelected(); 
    board.millCode = millG.isSelected(); 
    board.etch = etching.isSelected()?etchWidth:0; 
    board.verbose = images.isSelected(); 
    board.copper=cuWhite?Board.CU_WHITE:Board.CU_BLACK;
    
    if (board.edgeIsMixed()) {
      if (JOptionPane.showConfirmDialog(frame,"The image border is neither solid "+nL+
        "copper nor a complete isolation.  This is likely to result in unexpected "+nL+
        "results.  Cancel?","Problem with board",JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION) 
          return;
    }
    
    for (int i=0;i<pane.getTabCount();i++)  // Hide previous results
      if (i!=BOARD_PANEL)
        pane.setEnabledAt(i,false); 
    pane.setSelectedIndex(BOARD_PANEL);  

    board.tsd=(optD.isSelected()?drillReps:0);
    board.tsm=(optM.isSelected()?millReps:0);
    board.raw=(!optG.isSelected() && !optE.isSelected());
    board.smoothEtch=(optE.isSelected());
    board.drillPlunge=drillPlunge;
    board.drillTransit=drillTransit;
    board.millPlunge=millPlunge;
    board.millTransit=millTransit;
    board.forceDPI=forceDPI;
    board.doBacklash=doBacklash;
    board.backlashRad=backlash;
    board.flipped=(flipped?(-1):1);

    board.maxprocs=comboProcs.getSelectedIndex()+1;

    processButton.setBackground(Color.red);
    processButton.setText("Cancel run");
    processButton.repaint();

    jmFile.setEnabled(false);
    jmSettings.setEnabled(false);
    comboProcs.setEnabled(false);

    frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new Thread(board).start();
    }
  }
});

frame.pack(); 

frame.setVisible(true);
frame.setResizable(true);

System.out.println("***********************************************************************");
System.out.println(disclaim);
System.out.println("***********************************************************************");
JOptionPane.showMessageDialog(frame,disclaim,"Disclaimer",JOptionPane.WARNING_MESSAGE); 
}
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
sgcode=null;
if (board!=null) board.aChange();
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
class PCBPanel extends JPanel {

private static final long serialVersionUID = 1L;
int startx=-1;  
int starty=-1;

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

    xoffset+=((flipped?-1:1)*(startx-event.getX()));
    yoffset+=(starty-event.getY());

    startx=event.getX(); // For next time
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
      case (GCODE_PANEL):       sgcode=null;      break;
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

/*  // Command line options removed from V2.0 now GUI works well
if (args.length==0) {
  System.out.println("Cisolate.  Command line usage java Cisolate imagefilename flags or"); 
  System.out.println("      java -jar Cisolate.jar imagefilename flags");
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
  System.out.println("-o     : Overwrite existing files (otherwise fail)."); // GUI version overwrites
  System.out.println("-fXX   : Force to use particular DPI.");
  System.out.println("-F     : Flip board (e.g. to mill top image)."+nL);

  System.out.println("Example 'java cisolate/Cisolate pcb.bmp -cb -Od40 -o'");
  System.out.println("or 'java cisolate/Cisolate' which starts GUI");
  System.out.println("or 'java -jar Cisolate.jar' also starts GUI");
  
  System.out.println("Command line version reads a machine definition file");
  System.out.println("called machine.xml to set cutting depths/feed rates");
  System.out.println("or uses defaults in its absence");
*/
  cisolate=new Cisolate();   // Set up GUI
/*} 
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
  boolean flipped=false;

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
    else if (args[i].substring(0,2).equals("-F")) {
      flipped=true;
    }
  }  
  
  for (int i=1;i<args.length;i++) {
    if (!known[i]) {
      System.out.println("Unknown option "+args[i]+" : Ignoring it.");
 // TODO    cisolate.log.append("Unknown option "+args[i]+" : Ignored."+nL);
    }
  }
  
  Board myBoard=new Board(fname,verbose,tsd,tsm,copper,
    maxprocs,raw,etch,overwrite,forceDPI);

  new Thread(myBoard).start();
  }*/
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
   jmFile.setEnabled(true);
   jmSettings.setEnabled(true);
   comboProcs.setEnabled(true);
   forceRedraw();
   processing=false;
   frame.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

   JOptionPane.showMessageDialog(frame,obsBoard.log,
      "Cisolate : Run summary",JOptionPane.INFORMATION_MESSAGE); 
  }

 int x0,y0,x0G,y0G;

 double ratio=board.gcodeToImageRatio(forceDPI); 

 if (flipped) { if (xoffset>0) xoffset=0; }
 else         { if (xoffset<0) xoffset=0; }
      
 if (yoffset<0) yoffset=0;

 x0=xoffset;
 y0=yoffset;
 x0G=(flipped?-1:1)*(int)(0.5+ratio*xoffset);
 y0G=(int)(0.5+ratio*yoffset);

 if (obsBoard.board!=null && sboard==null) {

   BufferedImage b=copySubImage(obsBoard.board,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sboard=scaleOp.filter(b,null);
   boardPanel.repaint();
 }

 if (obsBoard.cuts!=null && scuts==null) { 
   BufferedImage b=copySubImage(obsBoard.cuts,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   scuts=scaleOp.filter(b,null);
   pane.setEnabledAt(CUTS_PANEL,true);
   cutsPanel.repaint();
 }
 if (obsBoard.drill!=null && sdrill==null) { 
   BufferedImage b=copySubImage(obsBoard.drill,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sdrill=scaleOp.filter(b,null);
   pane.setEnabledAt(DRILL_PANEL,true);
   drillPanel.repaint();
 }
 if (obsBoard.drillPath!=null && sdrillPath==null) { 
   BufferedImage b=copySubImage(obsBoard.drillPath,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sdrillPath=scaleOp.filter(b,null);
   pane.setEnabledAt(DRILL_PATH_PANEL,true);
   drillPathPanel.repaint();
 }
 if (obsBoard.heat!=null && sheat==null) { 
   BufferedImage b=copySubImage(obsBoard.heat,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sheat=scaleOp.filter(b,null);
   pane.setEnabledAt(HEAT_PANEL,true);
   heatPanel.repaint();
 }
 if (obsBoard.junction!=null && sjunction==null) { 
   BufferedImage b=copySubImage(obsBoard.junction,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sjunction=scaleOp.filter(b,null);
   pane.setEnabledAt(JUNCTION_PANEL,true);
   junctionPanel.repaint();
 }
 if (obsBoard.millPath!=null && smillPath==null) { 
   BufferedImage b=copySubImage(obsBoard.millPath,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   smillPath=scaleOp.filter(b,null);
   pane.setEnabledAt(MILL_PATH_PANEL,true);
   millPathPanel.repaint();
 }
 if (obsBoard.pitch!=null && spitch==null) { 
   BufferedImage b=copySubImage(obsBoard.pitch,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   spitch=scaleOp.filter(b,null);
   pane.setEnabledAt(PITCH_PANEL,true);
   pitchPanel.repaint();
 }
 if (obsBoard.duke!=null && sduke==null) { 
   BufferedImage b=copySubImage(obsBoard.duke,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   sduke=scaleOp.filter(b,null);
   pane.setEnabledAt(DUKE_PANEL,true);
   dukePanel.repaint();
 }
 if (obsBoard.etched!=null && setch==null) { 
   BufferedImage b=copySubImage(obsBoard.etched,x0,y0,obsBoard.board.getWidth(),
                                obsBoard.board.getHeight());
   setch=scaleOp.filter(b,null);
   pane.setEnabledAt(ETCH_PANEL,true);
   etchPanel.repaint();
 }
 if (obsBoard.gcode!=null && sgcode==null) { 
   BufferedImage b=copySubImage(obsBoard.gcode,x0G,y0G,obsBoard.gci.bi.getWidth(),
                                obsBoard.gci.bi.getHeight());
   sgcode=scaleOpG.filter(b,null);
   pane.setEnabledAt(GCODE_PANEL,true);
   gcodePanel.repaint();
 }
} 
// ---------------------------------------------------------------
}
}
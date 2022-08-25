

# Cisolate
A PCB trace isolation program using Cellular Automata 
(an alternative to Visolate)

*** ULTIMATELY THIS PROGRAM PRODUCES CUTTING PATHS FOR MACHINE TOOLS AND
THEREFORE HAS SAFETY IMPLICATIONS.  USER AT YOUR OWN RISK.  IT IS ADVISABLE
TO INSPECT A PATH IN A VIEWER BEFORE OPERATING A MACHINE ***

Therefore, as stated in the licence
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

This has been tested on more than twenty boards and I have good confidence that the 
technical content of this program is effective (i.e. the isolation paths will 
be correctly calculated).  From version 2.0 onwards the GUI is now significantly more
user friendly.

Details are available at https://oilulio.wordpress.com/2016/01/02/cisolate-pcb-construction/

<b>Hints and Tips</b>

The largeExample.bmp needs more than the default 20 iterations to fully optimise drill paths, try 120.

Starting the jar from the command line (java -jar Cisolate.jar) provides useful progress diagnostics
in the console as well as those seen in the GUI.

Once processing has started on a board, other tabs (initially greyed out) become selectable as the
different elements of the analysis complete.

Note : The provided jar files for v2.4 are compiled for Java 1.11 (LTS), and should 
run on that and later JVMs.

<b>Instructions</b>

With suitable permissions, jar file can be clicked on to run GUI.  Alternatively type :
java -jar Cisloate.jar, which starts GUI but also prints command line instructions.

In GUI, initially select a file to open (.bmp or .jpg); select maximum number of processors to use (default
is all); select optimisation replications (Settings-Replications) for Drill and Mill paths respectively (Default 20); 
select (via Settings-Create menu) what files should be produced (e.g. G-Code); select (Settings-Optimisations 
menu) which optimisations should be run; and confirm (Settings-Image) whether copper traces are black or white
in the image.  Then press 'Start Processing'.  May take minutes to complete processing, dependent on processor type and
board size.  Results appear as they are calculated.

<b>Coding Style</b>

It is a design intent that the program works with standard Java classes only; no further installation
required.

I am a believer in compact code.  This makes fitting a concept on a single printed 
sheet, or within a window, possible, which for me makes understanding the code 
much easier that a page that is mostly whitespece and closing braces.  Sometimes 
this means I prefer the format:

```C
if (X)    
  do Y;
```
over

```C
if (X) {  
    do Y;  
}  
```
although sometimes (if Y is compact) it makes more sense to have   
```C
if (X) { do Y; } 
```

Hence be alert for single line if statements, perhaps with no braces.  

Extensive use of vertical alignment is also intended to make code clearer by linking
similar concepts.

For example, I find this ...
```C

  if      (binaryImg[x][y-1])   {       y-=1; trace.add(new Point2D(x,y)); } // N
  else if (binaryImg[x+1][y])   { x+=1;       trace.add(new Point2D(x,y)); } // E
  else if (binaryImg[x][y+1])   {       y+=1; trace.add(new Point2D(x,y)); } // S
  else if (binaryImg[x-1][y])   { x-=1;       trace.add(new Point2D(x,y)); } // W
  else if (binaryImg[x+1][y-1]) { x+=1; y-=1; trace.add(new Point2D(x,y)); } // NE
  else if (binaryImg[x+1][y+1]) { x+=1; y+=1; trace.add(new Point2D(x,y)); } // SE
  else if (binaryImg[x-1][y+1]) { x-=1; y+=1; trace.add(new Point2D(x,y)); } // SW
  else if (binaryImg[x-1][y-1]) { x-=1; y-=1; trace.add(new Point2D(x,y)); } // NW
  else break; // end of the line : space all around us
```

... far easier to understand and to check for errors than something spread over 
three pages which is mostly braces.

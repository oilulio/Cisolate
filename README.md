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

Although I am confident that the technical content of this program is effective 
(i.e. the isolation paths will be correctly calculated), the GUI is less 
well developed.

Details are available at https://oilulio.wordpress.com/2016/01/02/cisolate-pcb-construction/

<b>Hints and Tips</b>

The largeExample.bmp needs more than the default 20 iterations to fully optimise drill paths, try 120.

Starting the jar from the command line (java -jar Cisolate.jar) provides useful progress diagnostics
in the console as well as those seen in the GUI.

Once processing has started on a board, other tabs (initially greyed out) become selectable as the
different elements of the analysis complete.

<b>Instructions</b>

With suitable permissions, jar file can be clicked on to run GUI.  Alternatively type :
java -jar Cisloate.jar, which starts GUI but also prints command line instructions.

In GUI, initially select a file to open (.bmp or .jpg); select maximum number of processors to use (default
is all); select optimisation iterations for Drill and Mill paths respectively (Default 20); select (via
Create menu) what files should be produced (e.g. G-Code); select (Optimisations menu) which
optimisations should be run; and confirm (Settings) whether copper traces are black or white in the image.
Then press 'Start Processing'.  May take minutes to complete processing, dependent on processor type and
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

Hence be alert for single line if statements, with no braces.  

Extensive use of vertical alignment is also intended to make code clearer by linking
similar concepts.

For example, I find this ...

```C
// Wait for the milling optimisation to finish - we're stuck without it
try { {}  while (tspDoneFuture.get()!=null); }
catch (InterruptedException e) { System.out.println("TSP Int ERROR ****");  e.printStackTrace(); }
catch (ExecutionException e)   { System.out.println("TSP Exec ERROR ****"); e.printStackTrace(); }
```

... far more useful than this :

```C
// Wait for the milling optimisation to finish - we're stuck without it
try { 
  {
  } while (tspDoneFuture.get()!=null); 
}
catch (InterruptedException e) {
  System.out.println("TSP Int ERROR ****");  
  e.printStackTrace(); 
}
catch (ExecutionException e)   {
  System.out.println("TSP Exec ERROR ****"); 
  e.printStackTrace(); 
}
```

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

import java.util.concurrent.*;

public class PcbCellEvolve implements Callable<Boolean> {

// Implements the cellular automata thinning method due to Guo and 
// Hall described in Knuth TAoCP 4A (p173) for thinning an image.  
// Knuth states the cell shapes that delete a pixel; have checked 
// these are equivalent to the algorithm A1 in Guo and Hall
// CACM 32 (1989) 359-373 https://dl.acm.org/doi/pdf/10.1145/62065.62074
// noting apparent typo in Rule C1 (p361) = last v (or) should be ^ (and).)

// Optionally extended with cul-de-sac/salient culling patterns of
// my own devising, but following same principles.

// Algorithm A1 is a repeated two-pass algorithm, applying patterns and
// then their 180 degree rotations on the next pass.

// Algorithm A2 : not implmented

// Patterns are encoded as 8 bits; representing the 8 neighbours of
// a pixel Counting from top left in clockwise order they are LSb (0) 
// to MSb (0).  This as an (internal) change form previous versions,
// (so does not affect results) but now aligns closer with Guo and Hall
// documentation, although they use 1-8 

// Call paradigm is to specify a range of columns for the particular
// thread to cover.  Hence min_x (included) and max_x (excluded) 
// specify limits.  Length of row calculated from the array.

// Takes a bitmap as a boolean 2D array and updates the bitmap
// in a duplicate array.  Returns a Boolean flag iff there is a change.

// Maintains a 'lasttouch' record - indicates how late/early a pixel
// was thinned.  Optimiser can later use to understand freedom available.

static final int [] patterns={0x68,0x6A,0x6C,0x6E,0x1A,0x1B,0x1C,0x1E,0x1F,0x9B,0x9F,0x38,0x3A,0x3B,
0x3C,0x3E,0x3F,0x70,0x78,0x7A,0x7C,0x7E,0x28,0x2A,0x2B,0x2C,0x2E,0x2F,
0x8F,0x8B,0xF,0xE,0xB,0xA,0x87,0x83,0x7,
0x20,0x80,0x40,0x1,0xC0,0x60,0x81,0x3 }; // Extras for cul-de-sac

// Precomputed bit rotations.  Allows 'final', perhaps for speed
static final int [] snrettap={0x86,0xA6,0xC6,0xE6,0xA1,0xB1,0xC1,0xE1,0xF1,0xB9,0xF9,0x83,0xA3,0xB3,
0xC3,0xE3,0xF3,0x7,0x87,0xA7,0xC7,0xE7,0x82,0xA2,0xB2,0xC2,0xE2,0xF2,
0xF8,0xB8,0xF0,0xE0,0xB0,0xA0,0x78,0x38,0x70,
0x2,0x8,0x4,0x10,0xC,0x6,0x18,0x30 }; // Extras for cul-de-sac*/

public int min_x,max_x;
boolean [][] bimg_in;
boolean [][] bimg_out;
int [][] lasttouch;
int pass;
boolean down;  // Determines up or down pass

// ---------------------------------------------------------------
public PcbCellEvolve(boolean down,boolean[][] bimg_in,boolean [][]bimg_out,
           int[][] lasttouch,int min_x,int max_x,int pass) {

this.min_x=min_x;  // Inclusive
this.max_x=max_x;  // Exclusive
this.down=down;
this.bimg_in=bimg_in;
this.bimg_out=bimg_out;
this.lasttouch=lasttouch;
this.pass=pass;
}
// ---------------------------------------------------------------
static boolean inPatterns(int abyte) 
{ // Simple - and about as fast as anything else : well done Java optimiser
  for (int i : patterns) 
    if (abyte==i)
      return true;

  return false;
}
// ---------------------------------------------------------------
static boolean inSnrettap(int abyte) // Reversed pattern
{
  for (int i : snrettap) 
    if (abyte==i)
      return true;

  return false;
}
// ---------------------------------------------------------------
public Boolean call() 
{
Boolean change=false;

Thread t = Thread.currentThread();  
t.setPriority(Thread.MIN_PRIORITY);  

// Guo and Hall A1 algorithm, with some extra cases to remove salients
for (int y=1;y<(bimg_in[0].length-1);y++) { 
  for (int x=min_x;x<max_x;x++) {  
      
    bimg_out[x][y]=false; // May change our mind shortly
    if (bimg_in[x][y]) {  // Only black can be black afterwards
    int abyte=  (bimg_in[x-1][y-1]?1<<0:0) | (bimg_in[x][y-1]  ?1<<1:0) |
                (bimg_in[x+1][y-1]?1<<2:0) | (bimg_in[x-1][y]  ?1<<7:0) |
                (bimg_in[x+1][y]  ?1<<3:0) | (bimg_in[x-1][y+1]?1<<6:0) |
                (bimg_in[x][y+1]  ?1<<5:0) | (bimg_in[x+1][y+1]?1<<4:0);
      if ((down)?inPatterns(abyte):inSnrettap(abyte)) {
        change=true;  
      } else  { // Stay black
        bimg_out[x][y]=true;
       lasttouch[x][y]=pass; 
    }
  }
 }
}
return change;
}

}
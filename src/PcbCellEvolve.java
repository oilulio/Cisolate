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
// these are equivalent to the algorithm A1 in Guo and Hall (given 
// apparent typo in Rule C1 = last ^ (or) should be v (and).)

// Optionally extended with cul-de-sac culling patterns of my own devising,
// but following same principles.

// Algorithm is a repeated two-pass algorithm, applying patterns and
// then their 180 degree rotations on the next pass.

// Patterns are encoded as 8 bits; representing the 8 neighbours of
// a pixel Counting from top left in 'book' order they are MSb (7) 
// to LSb (0), skipping the middle pixel (the one being assessed).
// This representation means that the 180 degree rotation is 
// equivalent to reversing the bits, but this is not exploited.

// Call paradigm is to specify a range of columns for the particular
// thread to cover.  Hence min_x (included) and max_x (excluded) 
// specify limits.  Length of row calculated from the array.

// Takes a bitmap as a boolean 2D array and updates the bitmap
// in a duplicate array.  Returns a Boolean flag iff there is a change.

// Maintains a 'lasttouch' record - indicates how late/early a pixel
// was thinned.  Optimiser can later use to understand freedom available.

static final int [] patterns={0x70,0x72,0x74,0x76,0x92,0x93,0x94,0x96,
       0x97,0x9B,0x9F,0xD0,0xD2,0xD3,0xD4,0xD6,0xD7,0xE0,0xF0,
       0xF2,0xF4,0xF6,0x50,0x52,0x53,0x54,0x56,0x57,0x1F,0x1B,
       0x17,0x16,0x13,0x12,0x0F,0x0B,0x07,

       0x40,0x08,0x20,0x01,0x28,0x60,0x09,0x03 }; // Extras for cul-de-sac

// Precomputed bit rotations.  Allows 'final', perhaps for speed
static final int [] snrettap={0xe ,0x4e,0x2e,0x6e,0x49,0xc9,0x29,0x69,
       0xe9,0xd9,0xf9,0x0b,0x4b,0xcb,0x2b,0x6b,0xeb,0x07,0x0f,
       0x4f,0x2f,0x6f,0x0a,0x4a,0xca,0x2a,0x6a,0xea,0xf8,0xd8,
       0xe8,0x68,0xc8,0x48,0xf0,0xd0,0xe0,

       0x02,0x10,0x04,0x80,0x14,0x06,0x90,0xc0 }; // Extras for cul-de-sac

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

for (int y=1;y<(bimg_in[0].length-1);y++) { 
  for (int x=min_x;x<max_x;x++) {  
      
    bimg_out[x][y]=false; // May change our mind shortly
    if (bimg_in[x][y]) {  // Only black can be black afterwards
      int abyte=(bimg_in[x-1][y-1]?1<<7:0) | (bimg_in[x][y-1]  ?1<<6:0) |
                (bimg_in[x+1][y-1]?1<<5:0) | (bimg_in[x-1][y]  ?1<<4:0) |
                (bimg_in[x+1][y]  ?1<<3:0) | (bimg_in[x-1][y+1]?1<<2:0) |
                (bimg_in[x][y+1]  ?1<<1:0) | (bimg_in[x+1][y+1]?1<<0:0);

      if ((down)?inPatterns(abyte):inSnrettap(abyte)) {
        change=true;  
      }
    else  { // Stay black
      bimg_out[x][y]=true;
      lasttouch[x][y]=pass; 
    }
  }
 }
}
return change;
}

}
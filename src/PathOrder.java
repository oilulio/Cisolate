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

import java.lang.System;
import java.util.Random;
import java.io.*;

public class PathOrder extends Seeker {   
// A mapping of coordinates to their order in a particular path (n[.])
// Does not contain the coordinates themselves, just the order they should
// be mapped to.  

protected int [] n;               
protected static Random random = new Random();
static String nL = System.getProperty("line.separator");

PathOrder(int moves) { this(moves,false); }
// ---------------------------------------------------------------------------
PathOrder(int moves,boolean empty) 
{
n = new int[moves];  
if (!empty)  // Faster
  for (int i=0;i<moves;i++)  n[i]=i; // Default order
}
// ---------------------------------------------------------------------------
PathOrder(PathOrder p) // copy constructor 
{
n = new int[p.n.length];
System.arraycopy(p.n,0,n,0,n.length);
}
// ---------------------------------------------------------------------------
public int mapping(int nth) { return n[nth];} 
// ---------------------------------------------------------------------------
public PathOrder cycle() 
{ // Suggests a mutated form by randomly starting at a new point (still in order)
double result=0.0;

int start;

PathOrder candidate=new PathOrder(n.length,true);

start=random.nextInt(n.length); 

System.arraycopy(n,start,candidate.n,0,n.length-start);
System.arraycopy(n,0,candidate.n,n.length-start,start);

return candidate;
}
// ---------------------------------------------------------------------------
public PathOrder mutate() // Suggests a mutated form by swapping a random segment
{  // Could pick start or stop randomly - will favour shorter segments
// or pick start and length for uniform distribution of lengths

double result=0.0;
int start,stop;

PathOrder candidate=new PathOrder(n.length,true);

start=random.nextInt(n.length); 
stop=random.nextInt(n.length); 

System.arraycopy(n,0,candidate.n,0,start);
for (int i=start;i<(stop+1);i++)
  candidate.n[i]=n[stop+(start-i)];
System.arraycopy(n,stop+1,candidate.n,stop+1,n.length-(stop+1));

return candidate;
}
// ---------------------------------------------------------------------------
public String toString() {

StringBuffer sb=new StringBuffer("Path order : Point, Sequence"+nL);
for (int i=0;i<n.length;i++)
  sb.append(Integer.toString(i)+","+Integer.toString(n[i])+nL);

return sb.toString();
}
// ---------------------------------------------------------------------------
public PathOrder neighbour() 
{ 
if (random.nextInt(10)!=0)  return mutate();  
else                        return cycle();
}
// ---------------------------------------------------------------------------
public PathOrder deepCopy()         { return new PathOrder(this); }
// ---------------------------------------------------------------------------
public PathOrder [] neighbourhood(int ignored) 
{ 
PathOrder[] po=new PathOrder[1];
po[0]=neighbour(); 
return po;
}

}
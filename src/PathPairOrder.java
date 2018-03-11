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

class PathPairOrder extends PathOrder {   // Travelling salesman path in pairs

// A mapping of coordinates to their order in a particular path (n[.])
// where each set are in pairs - i.e., a set of curves with beginnings/ends.  
// The curves can be in any order or direction, but each curve must have 
// its ends adjacent in the array.

// Assumed metric is the aggregate length from start to finish (ignoring the curves,
// which are assumed invariant)

PathPairOrder(int moves)               { super(moves,false); }
PathPairOrder(int moves,boolean empty) { super(moves,empty); }
// -------------------------------------------------------------------
PathPairOrder(PathPairOrder p) { 
super(p.n.length,true); 
System.arraycopy(p.n,0,n,0,n.length);
}
// -------------------------------------------------------------------
public PathPairOrder cycle() 
{ // Suggests a mutated form by randomly starting at a new point (still in order)

int start;

PathPairOrder candidate=new PathPairOrder(n.length,true);

start=2*random.nextInt(n.length/2); // constrains to evens

System.arraycopy(n,start,candidate.n,0,n.length-start);
System.arraycopy(n,0,candidate.n,n.length-start,start);

return candidate;
}
// -------------------------------------------------------------------
public PathPairOrder mutate() 
{  // Suggests a mutated form by swapping a random segment. 

int start,stop;

PathPairOrder candidate=new PathPairOrder(n.length,true);

do {
  start=2*random.nextInt(n.length/2);              // start is even 
  stop=(2*random.nextInt(n.length/2)+1)%n.length;  // stop is odd 
} while (start > stop);                            // enforce start < stop

System.arraycopy(n,0,candidate.n,0,start);
for (int i=start;i<(stop+1);i++)
  candidate.n[i]=n[stop+(start-i)];
System.arraycopy(n,stop+1,candidate.n,stop+1,n.length-(stop+1));

return candidate;
}
// ---------------------------------------------------------------------------
@Override
public String toString() {

StringBuffer sb=new StringBuffer();
for (int i=0;i<n.length;i+=2)
  sb.append(Integer.toString(i)+":"+Integer.toString(n[i])+
                                " "+Integer.toString(n[i+1])+nL);

return sb.toString();
}
// ---------------------------------------------------------------------------
@Override
public PathPairOrder neighbour() 
{ 
int r=random.nextInt(10);
if (r!=0)  return mutate();  
else       return cycle();
}
// ---------------------------------------------------------------------------
@Override
public PathPairOrder deepCopy()         { return new PathPairOrder(this); }
// ---------------------------------------------------------------------------
@Override
public PathPairOrder [] neighbourhood(int ignored) { 
// TODO Later ignored will specify how many neighbours

PathPairOrder[] ppo=new PathPairOrder[1];
ppo[0]=neighbour(); 
return ppo;
}
}
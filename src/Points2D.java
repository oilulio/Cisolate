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

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

class Points2D implements Iterable<Point2D>,Optimisable  {

// An extensible, iterable list of 2D points with no connotation they
// are consecutive etc.
// Optimising against PathOrder gets a shortest route through the points 
// i.e. the travelling salesman problem.

protected List<Point2D> points;
protected PathOrder optimum;

Points2D()  {  points=new ArrayList<Point2D>();  }

public void add(Point2D p)     { points.add(p);  }

public void remove(int r)      { points.remove(r); }

public String toString() { return ("Set of "+points.size()+" points"); }

public int getX(int i)     { return points.get(i).getX(); }
public int getY(int i)     { return points.get(i).getY(); }
public Point2D get(int i)  { return points.get(i); }
public int size()          { return points.size(); }

@Override
public double score(Seeker s) 
{
// Distance between consecutive points (as mapped by the proposed
// PathOrder of the points.) i.e. metric for travelling salesman problem  

PathOrder po=(PathOrder)s;

double length=0.0;
for (int i=0;i<points.size()-1;i++) {
  length-=Math.sqrt(Math.pow(points.get(po.mapping(i)).getX()-
                             points.get(po.mapping(i+1)).getX(),2)+
                    Math.pow(points.get(po.mapping(i)).getY()-
                             points.get(po.mapping(i+1)).getY(),2));
}
return length; // -ve because short is good
}

@Override
public void setSolution(Seeker po) { optimum=(PathOrder)po; }

@Override
public Seeker getSolution(Seeker po) { return (PathOrder)optimum; }

@Override
public Iterator<Point2D> iterator() { return points.iterator(); }

}
/*
Copyright (C) 2016-18  S Combes

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

class Point2D {  // Simple class representing a point in 2D space, i.e. (x,y)

private final int x;
private final int y;

Point2D(int x,int y)  { this.x=x;    this.y=y;    }
Point2D(Point2D pt)   { this.x=pt.x; this.y=pt.y; }

public String toString() { return ("Point at ("+x+","+y+")"); }

public int getX() { return x; }
public int getY() { return y; }
public boolean equals(Point2D pt) 
    { return (this.x==pt.getX() && this.y==pt.getY()); }
public boolean equals(int x,int y) 
    { return (this.x==x && this.y==y); }
    
public double distance(Point2D point) 
{
return Math.sqrt(Math.pow(getX()-point.getX(),2)+
                 Math.pow(getY()-point.getY(),2));
  
}
}
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

class Lines2D extends Points2D  {

Lines2D()  {  super();  }

Lines2D(Points2D p2d) { super(); this.points=p2d.points; }

PathPairOrder optimum; // Deliberate override

//@Override
public double score(PathPairOrder ppo) 
{
// Distance between consecutive points (as mapped by the proposed
// PathOrder of the points.) i.e. metric for travelling salesman problem  

double length=0.0;
for (int i=0;i<points.size()-1;i++) {
  length-=Math.sqrt(Math.pow(points.get(ppo.mapping(i)).getX()-
                             points.get(ppo.mapping(i+1)).getX(),2)+
                    Math.pow(points.get(ppo.mapping(i)).getY()-
                             points.get(ppo.mapping(i+1)).getY(),2));
}
return length; // -ve because short is good  
}

@Override
public void setSolution(Seeker ppo) { optimum=(PathPairOrder)ppo; }

//public void setSolution(PathPairOrder ppo) { optimum=ppo; }
@Override
public Seeker getSolution(Seeker ppo) { return (PathPairOrder)optimum; }

}
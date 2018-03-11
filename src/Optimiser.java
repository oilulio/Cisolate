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

import java.util.Random;

public abstract class Optimiser implements Runnable  {

protected Optimisable optimee;
protected Seeker start;
public Seeker best;
protected volatile boolean stop = false;
protected double startScore;

protected static Random random = new Random();

// ------------------------------------------------------------------------
public Optimiser(Optimisable optimee,Seeker seeker,int priority) 
{
this.optimee=optimee;
this.start=seeker.deepCopy();  // Defensive copy
stop=false;
}
// ------------------------------------------------------------------------
@Override
public void run() { // Optimisation is hard work, likely to want background

stop=false;
Thread t = Thread.currentThread();  
t.setPriority(Thread.MIN_PRIORITY);  

this.optimise();  
}
// ------------------------------------------------------------------------
public void gracefulExit() { stop=true; }
// ------------------------------------------------------------------------
abstract void optimise();
public abstract double getFraction();
public double getProgress() { return -1.0; } // Not supported unless overridden
}
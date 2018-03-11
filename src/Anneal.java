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

public class Anneal extends Optimiser {

// An optimisation class based on simulated annealing

// Takes an 'Optimisable' problem and uses a Seeker, which represents
// both a potential solution and the way in which that solution can vary,
// and usually greedily climbs the hill towards a solution, dependent on
// the 'temperature' - at high temperatures, a move away from an
// apparent optimum is more likely, which helps avoid vulnerability to
// local maxima.  Hence a stochastic algorithm.

private int reps;            // replications to try
private int rep;             // rep we're at
private int candidates;      // candidate neighbours to try each time
private int lastChange=0;
private double temperature;
private double coolRate;
private double bestScore;
private double startScore;

public Anneal(Optimisable optimee,Seeker seeker,int reps,double temperature,
              double coolRate) 
{ this(optimee,seeker,reps,temperature,coolRate,Thread.MIN_PRIORITY);}
// ------------------------------------------------------------------------
public Anneal(Optimisable optimee,Seeker seeker,int reps,double temperature,
              double coolRate,int priority) 
{
super(optimee,seeker,priority); 
this.reps=reps;
this.temperature=temperature;
this.coolRate=coolRate;

if (coolRate > 1.0 || coolRate <= 0.0)
  throw new IllegalArgumentException("Cooling rate out of range");
if (temperature <= 0.0)
  throw new IllegalArgumentException("Temperature out of range");

rep=0; // Not running
}
// ------------------------------------------------------------------------
public int completedBy() { return lastChange; } // Only valid once done
// ------------------------------------------------------------------------
public void optimise()
{ 
lastChange=0;
best=start.deepCopy();
Seeker last=start.deepCopy();
bestScore=optimee.score(best);
double lastScore=optimee.score(start);
startScore=lastScore;

for (rep=1;rep<=reps;rep++) {
  if (stop) return; // Note we are running in parallel - stop can be set outside.
  for (int subrep=0;subrep<30000;subrep++) {

    Seeker candidate=last.neighbour();
 
    double score=optimee.score(candidate);

    if (score>bestScore) { // Greedily grab
      best=candidate.deepCopy();
      bestScore=optimee.score(best);
//      System.out.println(rep+" "+subrep+" "+bestScore+" "+temperature);
      last=candidate;
      lastScore=score;
      lastChange=rep;
    }
    else if (score>lastScore) { // Greedy grab
      last=candidate;
      lastScore=score;
    }
    else {

      double delta=(score-lastScore)/Math.abs(lastScore); // Will be -ve
      double prob=Math.exp(delta/temperature);

      if (prob > random.nextDouble()) {  // Grab it anyway
        last=candidate;
        lastScore=score;
      }
    }
  }
  temperature*=coolRate;
}
rep=0;  // Done
optimee.setSolution(best);
}
// -------------------------------------------------------------------------
@Override
public double getProgress() { return (double)(rep)/(double)reps; }
// -------------------------------------------------------------------------
public double getFraction() { return bestScore/startScore; }
// -------------------------------------------------------------------------
public boolean running() { return (rep!=0); }
// -------------------------------------------------------------------------
}
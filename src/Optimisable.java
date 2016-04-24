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

public interface Optimisable {

  public abstract  double score(Seeker s); 
  // Optimisers will maximise score, given the Seeker
  // To minimise some function, f(x), set score = -f(x).

// Note both of the below can be implemented taking account
// of the type passed in to allow optimisation against different
// classes of seeker.

  public abstract void setSolution(Seeker s);
  // Opportunity to write solution back into Optimee structure.
  // Implement as simple return if not wanted.

  public Seeker getSolution(Seeker s);
  // Gets a seeker solution from the Optimee if we want to use, or
  // improve, a previous optimisation.
}